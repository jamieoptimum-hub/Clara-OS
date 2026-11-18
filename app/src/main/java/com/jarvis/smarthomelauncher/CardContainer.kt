package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Room
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// TRUE 3D SUNKEN WELL & RAISED INNER CARD CONTAINER (Fixes #1 & #2)
// - Creates a realistic recessed bezel carved into the #202226 background:
//   1. Outer Rim Bevel: Dark top-left overhang shadow (#0A0C0F) transitioning
//      to a crisp bottom-right light catch-lip (#3A404D).
//   2. Recessed Well Groove: Angled inset shadow gradient inside a tight 4.dp
//      sunken channel so it reads as depth rather than a thick black border.
//   3. Raised Inner Surface: Uses an opaque base color with shadowElevation = 0.dp
//      to prevent Android's RenderNode from drawing a translucent square artifact
//      in the center of tiles.
// ---------------------------------------------------------------------------
@Composable
fun SfHouseSunkenTileContainer(
    modifier: Modifier = Modifier,
    outerCornerRadius: Dp = 20.dp,
    innerCornerRadius: Dp = 15.dp,
    sunkenInsetPadding: Dp = 4.dp,
    innerBorderColor: Color = GhCardBorder,
    innerBackgroundColor: Color = GhTileSurface,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = ClaraThemeManager.isDarkMode

    // Directional sunken cavity colors (top-left shadow -> bottom-right ambient)
    val wellTopLeftShadow = if (isDark) Color(0xFF0E1014) else Color(0xFFC4CCD6)
    val wellBottomRightBase = if (isDark) Color(0xFF1B1E24) else Color(0xFFE1E6EE)

    // Outer cutout rim: dark top-left overhang, bright bottom-right catch-light lip
    val outerRimTopLeft = if (isDark) Color(0xFF090B0E) else Color(0xFFB4BDC8)
    val outerRimBottomRight = if (isDark) Color(0xFF39404E) else Color(0xFFFFFFFF)

    // Inner raised card bevel: crisp top-left highlight edge, subtle bottom-right shade
    val innerRimTopLeft = if (innerBorderColor != GhCardBorder) {
        innerBorderColor.copy(alpha = 0.82f)
    } else if (isDark) {
        Color(0xFF434A5A)
    } else {
        Color.White
    }

    val innerRimBottomRight = if (innerBorderColor != GhCardBorder) {
        innerBorderColor.copy(alpha = 0.35f)
    } else if (isDark) {
        Color(0xFF1B1E25)
    } else {
        Color(0xFFCFD6E0)
    }

    val cardTopLeftTint = if (innerBackgroundColor != GhTileSurface) {
        innerBackgroundColor
    } else if (isDark) {
        Color(0xFF2E333E)
    } else {
        Color(0xFFFFFFFF)
    }

    val cardBottomRightTint = if (innerBackgroundColor != GhTileSurface) {
        innerBackgroundColor
    } else if (isDark) {
        Color(0xFF22252D)
    } else {
        Color(0xFFF2F5FA)
    }

    // Compact sunken groove (3.5dp..4.5dp) so it looks like a 3D recessed slot, not a thick border
    val effectiveInset = sunkenInsetPadding.coerceIn(3.dp, 4.5.dp)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(outerCornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(wellTopLeftShadow, wellBottomRightBase),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 900f)
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(outerRimTopLeft, outerRimBottomRight),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 900f)
                ),
                shape = RoundedCornerShape(outerCornerRadius)
            )
            .padding(effectiveInset)
    ) {
        Surface(
            color = innerBackgroundColor,
            shape = RoundedCornerShape(innerCornerRadius),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(innerCornerRadius))
                .border(
                    width = 1.1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(innerRimTopLeft, innerRimBottomRight),
                        start = Offset(0f, 0f),
                        end = Offset(800f, 800f)
                    ),
                    shape = RoundedCornerShape(innerCornerRadius)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                cardTopLeftTint,
                                innerBackgroundColor,
                                cardBottomRightTint
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(800f, 800f)
                        )
                    )
                    .drawWithContent {
                        drawContent()
                        // Subtle inner top-edge catch-light line for 3D depth without any center square artifact
                        val cornerPx = innerCornerRadius.toPx()
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDark) 0.10f else 0.45f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = size.height * 0.35f
                            ),
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
            ) {
                content()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// STANDARDIZED HERO CARD ACTION BUTTONS
// ---------------------------------------------------------------------------
@Composable
internal fun StackedHeroOptionBox(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isSelected) 1.4.dp else 1.dp,
            color = if (isSelected) activeColor else GhCardBorder
        ),
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.38f),
                            activeColor.copy(alpha = 0.16f)
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
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                stateDescription = if (isSelected) "Selected" else "Not selected"
            }
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) activeColor else GhTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    color = if (isSelected) Color.White else GhTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = GhFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(activeColor)
                )
            }
        }
    }
}

@Composable
internal fun HeroRoomOptionBox(
    roomName: String,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    dropdownContent: @Composable () -> Unit
) {
    val hasAssignedRoom = roomName.isNotBlank()
    Box(modifier = modifier) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (hasAssignedRoom) activeColor.copy(alpha = 0.55f) else GhCardBorder
            ),
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(
                        colors = if (hasAssignedRoom) {
                            listOf(
                                activeColor.copy(alpha = 0.24f),
                                GhTileSurfaceElevated
                            )
                        } else {
                            listOf(
                                GhTileSurfaceElevated,
                                GhTileSurfaceElevated.copy(alpha = 0.78f)
                            )
                        }
                    )
                )
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Room,
                        contentDescription = "Assign Room",
                        tint = if (hasAssignedRoom) activeColor else GhTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = roomName.ifBlank { "Room" },
                        color = if (hasAssignedRoom) GhTextPrimary else GhTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (hasAssignedRoom) FontWeight.Bold else FontWeight.SemiBold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = if (hasAssignedRoom) activeColor else GhTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        dropdownContent()
    }
}

@Composable
internal fun EmbeddedTelemetryBadge(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurfaceElevated.copy(alpha = 0.68f),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = modifier.height(28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = GhTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = value,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun HeroSocketPowerButtonCard(
    title: String,
    subtitle: String,
    isOn: Boolean,
    accentColor: Color,
    activeGradientColors: List<Color> = listOf(accentColor, accentColor.copy(alpha = 0.7f)),
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isOn) 1.5.dp else 1.dp,
            color = if (isOn) Color.White.copy(alpha = 0.48f) else GhCardBorder
        ),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isOn) {
                    Brush.verticalGradient(colors = activeGradientColors)
                } else {
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.12f),
                            GhTileSurfaceElevated
                        ),
                        radius = 220f
                    )
                }
            )
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOn) Color.White.copy(alpha = 0.24f) else accentColor.copy(alpha = 0.16f)
                    )
                    .border(
                        width = 1.2.dp,
                        color = if (isOn) Color.White.copy(alpha = 0.65f) else accentColor.copy(alpha = 0.45f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = title,
                    tint = if (isOn) Color.White else accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                color = if (isOn) Color.White else GhTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GhFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                color = if (isOn) Color.White.copy(alpha = 0.9f) else GhTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GhFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun HeroRemoteQuickBtn(
    label: String,
    icon: ImageVector,
    isHighlighted: Boolean = false,
    accentColor: Color = GhAccentGreen,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isHighlighted) 1.4.dp else 1.dp,
            color = if (isHighlighted) accentColor else GhCardBorder
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isHighlighted) {
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.34f),
                            accentColor.copy(alpha = 0.14f)
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
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                stateDescription = if (isHighlighted) "Active" else "Inactive"
            }
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isHighlighted) accentColor else GhTextPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = if (isHighlighted) Color.White else GhTextPrimary,
                fontSize = 10.5.sp,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = GhFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// SF-HOUSE COMPACT MINT / ACCENT PILL SWITCH
// ---------------------------------------------------------------------------
@Composable
fun SfHouseMintPillSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color = GhAccentGreen,
    modifier: Modifier = Modifier
) {
    val trackColor = if (checked) activeColor else Color(0xFF2D3036)
    val thumbColor = if (checked) Color(0xFF1C1E22) else Color(0xFF8A9099)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "On" else "Off"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(21.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
                .padding(horizontal = 3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(thumbColor)
            )
        }
    }
}
