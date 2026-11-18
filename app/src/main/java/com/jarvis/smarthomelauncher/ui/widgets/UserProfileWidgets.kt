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
// POINT 12 & 13: USER PROFILE & PARENTAL LOCK MANAGEMENT MODAL
// ---------------------------------------------------------------------------
@Composable
fun UserProfileManagementModal(
    bridgeSettings: BridgeSettings,
    onSaveBridgeSettings: (BridgeSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val profiles = remember(bridgeSettings.userProfiles) {
        mutableStateListOf<UserProfile>().apply {
            addAll(bridgeSettings.userProfiles.ifEmpty { DEFAULT_USER_PROFILES })
        }
    }
    var activeProfileId by remember(bridgeSettings.activeUserProfileId) {
        mutableStateOf(bridgeSettings.activeUserProfileId)
    }
    val hasExistingPin = remember(bridgeSettings) {
        DeviceRepository.hasConfiguredOwnerPin(bridgeSettings)
    }
    var masterPinDraft by remember(bridgeSettings.rolePinCode) {
        mutableStateOf(
            if (bridgeSettings.rolePinCode.startsWith("PIN_SHA256_V1:", ignoreCase = true)) "" else bridgeSettings.rolePinCode
        )
    }
    var pinValidationError by remember { mutableStateOf("") }
    var requirePinAdminAndVehicles by remember(bridgeSettings.requirePinForAdminAndVehicles) {
        mutableStateOf(bridgeSettings.requirePinForAdminAndVehicles)
    }
    var newUserNameDraft by remember { mutableStateOf("") }
    var newUserRoleDraft by remember { mutableStateOf(UserRole.FAMILY) }
    var newUserParentalLock by remember { mutableStateOf(false) }

    val hasValidOwnerPinConfigured = hasExistingPin || masterPinDraft.trim().length >= 4

    val compactInputStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary)

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
                .fillMaxHeight(0.88f)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ManageAccounts,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Household Users & Parental Locks",
                                color = GhTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "Parental-locked profiles require a PIN to switch users and block Edit Mode, deleting tiles/cards, unlocking vehicles/doors, and Control Center",
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

                // Master Parental Lock PIN Field
                Surface(
                    color = GhTileSurfaceElevated.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GhCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = masterPinDraft,
                                onValueChange = {
                                    masterPinDraft = it.filter { c -> c.isDigit() }.take(8)
                                    pinValidationError = ""
                                },
                                label = {
                                    Text(
                                        text = if (hasExistingPin && masterPinDraft.isBlank()) {
                                            "Household PIN Configured (Enter 4-8 Digits to Change)"
                                        } else {
                                            "Household Parental / Admin PIN (4-8 Digits)"
                                        },
                                        fontSize = 11.sp
                                    )
                                },
                                textStyle = compactInputStyle,
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = claraFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = requirePinAdminAndVehicles,
                                onClick = {
                                    if (!requirePinAdminAndVehicles && !hasValidOwnerPinConfigured) {
                                        pinValidationError = "Set a 4–8 digit Household PIN above before requiring PIN protection."
                                    } else {
                                        pinValidationError = ""
                                        requirePinAdminAndVehicles = !requirePinAdminAndVehicles
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                label = {
                                    Text(
                                        text = if (requirePinAdminAndVehicles) "Always Require PIN \u2713" else "Require PIN on Locked Profiles",
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                        if (pinValidationError.isNotBlank()) {
                            Text(
                                text = pinValidationError,
                                color = GhAccentRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Add New User Profile Row
                Surface(
                    color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Add Household User Profile",
                            color = GhAccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newUserNameDraft,
                                onValueChange = { newUserNameDraft = it },
                                label = { Text("User Name (e.g. Alex, Kids)", fontSize = 11.sp) },
                                textStyle = compactInputStyle,
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = claraFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = newUserParentalLock,
                                onClick = {
                                    if (!newUserParentalLock && !hasValidOwnerPinConfigured) {
                                        pinValidationError = "Set a 4–8 digit Household PIN above before enabling Parental Lock."
                                    } else {
                                        pinValidationError = ""
                                        newUserParentalLock = !newUserParentalLock
                                        newUserRoleDraft = if (newUserParentalLock) UserRole.CHILD else UserRole.FAMILY
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                label = {
                                    Text(
                                        text = if (newUserParentalLock) "Parental Lock ON" else "Standard Access",
                                        fontSize = 11.sp
                                    )
                                }
                            )
                            Button(
                                onClick = {
                                    val cleanName = newUserNameDraft.trim()
                                    if (cleanName.isNotBlank()) {
                                        val lockAllowed = newUserParentalLock && hasValidOwnerPinConfigured
                                        val paletteHex = listOf(0xFFA3F0C1L, 0xFF00C6FFL, 0xFFFF9E44L, 0xFFC084FCL, 0xFFFF6B6BL)
                                        val nextColor = paletteHex[profiles.size % paletteHex.size]
                                        val created = UserProfile(
                                            name = cleanName,
                                            role = if (lockAllowed) UserRole.CHILD else newUserRoleDraft,
                                            avatarColorHex = nextColor,
                                            isParentalLocked = lockAllowed,
                                            requirePinToSwitchOut = lockAllowed
                                        )
                                        profiles.add(created)
                                        newUserNameDraft = ""
                                    }
                                },
                                enabled = newUserNameDraft.trim().isNotBlank(),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF141518)
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add User", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Existing User Profiles List
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(profiles, key = { it.id }) { prof ->
                        val isCurrent = prof.id == activeProfileId
                        val badgeColor = Color((prof.avatarColorHex or 0xFF000000L).toInt())
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
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { activeProfileId = prof.id }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(badgeColor.copy(alpha = 0.24f))
                                            .border(1.2.dp, badgeColor, CircleShape)
                                    ) {
                                        Text(
                                            text = prof.name.take(1).uppercase(Locale.getDefault()),
                                            color = badgeColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = prof.name,
                                                color = GhTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (isCurrent) {
                                                Surface(
                                                    color = GhAccentGreen.copy(alpha = 0.22f),
                                                    shape = RoundedCornerShape(50)
                                                ) {
                                                    Text(
                                                        text = "Active",
                                                        color = GhAccentGreen,
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (prof.isParentalLocked) {
                                                "${prof.role.label} \u2022 Parental Lock Shield Active (Blocks Edit Mode, Delete & Unlock)"
                                            } else {
                                                "${prof.role.label} \u2022 ${prof.role.subtitle}"
                                            },
                                            color = if (prof.isParentalLocked) GhAccentWarm else GhTextSecondary,
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = prof.isParentalLocked,
                                        onClick = {
                                            val idx = profiles.indexOfFirst { it.id == prof.id }
                                            if (idx >= 0) {
                                                val nextLocked = !prof.isParentalLocked
                                                if (nextLocked && !hasValidOwnerPinConfigured) {
                                                    pinValidationError = "Set a 4–8 digit Household PIN above before locking '${prof.name}'."
                                                } else {
                                                    pinValidationError = ""
                                                    profiles[idx] = prof.copy(
                                                        isParentalLocked = nextLocked,
                                                        requirePinToSwitchOut = nextLocked,
                                                        role = if (nextLocked && prof.role in listOf(UserRole.OWNER, UserRole.ADMIN)) {
                                                            UserRole.CHILD
                                                        } else if (!nextLocked && prof.role == UserRole.CHILD) {
                                                            UserRole.FAMILY
                                                        } else {
                                                            prof.role
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (prof.isParentalLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                                contentDescription = null,
                                                tint = if (prof.isParentalLocked) GhAccentWarm else GhTextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = if (prof.isParentalLocked) "Locked" else "Unlocked",
                                                fontSize = 10.5.sp
                                            )
                                        }
                                    )
                                    if (profiles.size > 1 && prof.role != UserRole.OWNER) {
                                        IconButton(
                                            onClick = {
                                                profiles.removeAll { it.id == prof.id }
                                                if (activeProfileId == prof.id) {
                                                    activeProfileId = profiles.first().id
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete User",
                                                tint = GhAccentRed,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = GhTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cleanPinDraft = masterPinDraft.trim()
                            if (cleanPinDraft.isNotBlank() && cleanPinDraft.length < 4) {
                                pinValidationError = "Household PIN must be 4 to 8 digits."
                                return@Button
                            }
                            val resolvedPin = when {
                                cleanPinDraft.length >= 4 -> DeviceRepository.hashPinCode(cleanPinDraft)
                                else -> bridgeSettings.rolePinCode
                            }
                            val pinReady = resolvedPin.isNotBlank()
                            val sanitizedProfiles = profiles.map { p ->
                                if (!pinReady && p.isParentalLocked) {
                                    p.copy(isParentalLocked = false, requirePinToSwitchOut = false)
                                } else {
                                    p
                                }
                            }
                            val chosenActive = sanitizedProfiles.firstOrNull { it.id == activeProfileId } ?: sanitizedProfiles.first()
                            val updatedBridge = bridgeSettings.copy(
                                userProfiles = sanitizedProfiles,
                                activeUserProfileId = chosenActive.id,
                                activeUserRole = chosenActive.role,
                                rolePinCode = resolvedPin,
                                requirePinForAdminAndVehicles = requirePinAdminAndVehicles && pinReady
                            )
                            onSaveBridgeSettings(updatedBridge)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF141518)
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Profiles & Locks", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
