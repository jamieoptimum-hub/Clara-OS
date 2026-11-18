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
import com.jarvis.smarthomelauncher.ui.popups.ExecutiveVehicleCarIllustration
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

@Composable
fun SfHouseVehiclesGarageCard(
    allTiles: List<SmartTile>,
    onUpdateTile: (SmartTile) -> Unit,
    onActivateHeroDial: (SmartTile) -> Unit,
    onOpenSetupWizard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vehicleTiles = remember(allTiles) {
        allTiles.filter { it.isVehicleTile }
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = Icons.Outlined.DirectionsCar,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(19.dp)
                    )
                    Text(
                        text = "Executive Garage",
                        color = GhTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                }
                Surface(
                    color = GhTileSurfaceElevated,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, GhCardBorder),
                    modifier = Modifier.clickable { onOpenSetupWizard() }
                ) {
                    val linkedCount = vehicleTiles.count { it.isConfiguredAndLinked }
                    Text(
                        text = if (linkedCount == 0) "Tap to Link" else "$linkedCount Linked",
                        color = if (linkedCount == 0) GhAccentWarm else GhAccentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            if (vehicleTiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExecutiveVehicleCarIllustration(
                            isJaguar = true,
                            isLocked = true,
                            isClimateActive = false,
                            isCharging = false,
                            accentColor = Color(0xFF00E5A3),
                            modifier = Modifier
                                .width(150.dp)
                                .height(54.dp)
                        )
                        Text(
                            text = "Tap to Link Jaguar InControl or Mercedes me",
                            color = GhTextSecondary,
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onOpenSetupWizard() },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Link Jaguar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            FilledTonalButton(
                                onClick = { onOpenSetupWizard() },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Link Mercedes", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(vehicleTiles, key = { "garage_veh_${it.id}" }) { car ->
                        val isJaguar = car.activeProviderId.contains("JAGUAR", ignoreCase = true) ||
                                car.companionAppPackage.contains("jaguar", ignoreCase = true) ||
                                car.title.contains("Jaguar", ignoreCase = true)
                        val isLinked = car.isConfiguredAndLinked
                        val isOnline = car.isOnline
                        val isLocked = car.vehicleLocked
                        val accent = if (car.vehicleIsEv || isJaguar) Color(0xFF00E5A3) else Color(0xFF00C6FF)
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    !isLinked -> GhAccentWarm.copy(alpha = 0.6f)
                                    !isOnline -> GhAccentRed.copy(alpha = 0.6f)
                                    isLocked -> accent.copy(alpha = 0.5f)
                                    else -> GhAccentRed
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isLinked) {
                                        onOpenSetupWizard()
                                    } else {
                                        onActivateHeroDial(car)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Point 7: Custom Car Illustration on Jaguar & Mercedes Garage Cards
                                ExecutiveVehicleCarIllustration(
                                    isJaguar = isJaguar,
                                    isLocked = isLocked,
                                    isClimateActive = car.vehicleClimateActive,
                                    isCharging = car.vehicleIsCharging,
                                    accentColor = accent,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(48.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = car.title,
                                        color = GhTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Point 5: Explicit "Tap to Link" or "Offline" status + verified DVLA Tax/MOT & range summary
                                    val verifiedComplianceSuffix = buildString {
                                        if (car.vehicleMotExpiryDateIso.isNotBlank()) append(" • MOT: ${car.vehicleMotStatus}")
                                        if (car.vehicleTaxDueDateIso.isNotBlank()) append(" • Tax: ${car.vehicleTaxStatus}")
                                    }
                                    Text(
                                        text = when {
                                            !isLinked -> "Tap to Link ${car.type.brandName}"
                                            !isOnline -> "Offline • Tap to Wake / Sync"
                                            else -> "${car.vehicleFuelOrBatteryPct}% (${car.vehicleRangeMiles} mi)$verifiedComplianceSuffix"
                                        },
                                        color = when {
                                            !isLinked -> GhAccentWarm
                                            !isOnline -> GhAccentRed
                                            isLocked -> accent
                                            else -> GhAccentRed
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!isLinked) {
                                    Surface(
                                        color = GhAccentWarm.copy(alpha = 0.22f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, GhAccentWarm),
                                        modifier = Modifier.clickable { onOpenSetupWizard() }
                                    ) {
                                        Text(
                                            text = "Tap to Link",
                                            color = GhAccentWarm,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                        )
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            color = if (isLocked) accent.copy(alpha = 0.22f) else GhAccentRed.copy(alpha = 0.22f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                val nextLocked = !isLocked
                                                onUpdateTile(
                                                    car.copy(
                                                        vehicleLocked = nextLocked,
                                                        isActive = nextLocked,
                                                        hvacMode = "VEHICLE_LOCK"
                                                    )
                                                )
                                            }
                                        ) {
                                            Text(
                                                text = if (isLocked) "Locked" else "Unlock",
                                                color = if (isLocked) accent else GhAccentRed,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Surface(
                                            color = if (car.vehicleClimateActive) NestHeatingOrange.copy(alpha = 0.25f) else GhTileSurface,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                val nextClimate = !car.vehicleClimateActive
                                                onUpdateTile(
                                                    car.copy(
                                                        vehicleClimateActive = nextClimate,
                                                        hvacMode = "VEHICLE_CLIMATE"
                                                    )
                                                )
                                            }
                                        ) {
                                            Text(
                                                text = if (car.vehicleClimateActive) "Climate ON" else "Climate",
                                                color = if (car.vehicleClimateActive) NestHeatingOrange else GhTextSecondary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
}
