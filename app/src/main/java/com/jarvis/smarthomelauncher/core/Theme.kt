package com.jarvis.smarthomelauncher.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

const val DEFAULT_ACCENT_COLOR_ARGB: Long = 0xFFA3F0C1L

enum class AppThemeMode(val label: String) {
    DARK("Dark Mode (SF-House Sunken Slate)"),
    LIGHT("Light Mode"),
    AUTO_DAYLIGHT("Auto (Daylight / Night)")
}

enum class AppAccentColorPreset(
    val label: String,
    val darkHex: Long,
    val lightHex: Long,
    val activeSurfaceDarkHex: Long
) {
    MINT_GREEN("Mint Green (Default)", 0xFFA3F0C1, 0xFF1E8E5A, 0xFF202D27),
    ELECTRIC_BLUE("Electric Blue", 0xFF58A6FF, 0xFF1A73E8, 0xFF1E2938),
    CYBER_LIME("Cyber Lime", 0xFFB8F236, 0xFF5A9E00, 0xFF262E1A),
    SUNSET_ORANGE("Sunset Orange", 0xFFFF8A47, 0xFFE65100, 0xFF33241B),
    ROYAL_PURPLE("Royal Purple", 0xFFC084FC, 0xFF7E22CE, 0xFF2A2136),
    CORAL_RED("Coral Red", 0xFFFF6B6B, 0xFFD93025, 0xFF362022),
    ROSE_PINK("Rose Pink", 0xFFF472B6, 0xFFDB2777, 0xFF34202C),
    WARM_GOLD("Warm Gold", 0xFFFBBF24, 0xFFD97706, 0xFF332B1A),
    TEAL_CYAN("Teal Cyan", 0xFF2DD4BF, 0xFF0D9488, 0xFF1C2E2E)
}

enum class ClockFaceStyle(val label: String, val subtitle: String) {
    RADIAL_ORB("Radial Dial Clock", "SF-House tick-mark ring with sweeping seconds arc"),
    MINIMAL_DIGITAL("Minimal Oversized Digital", "Clean oversized digital time with seconds pill"),
    CLASSIC_ANALOG("Classic Analog Watch", "Precision hour, minute & sweeping second hands"),
    FLIP_RETRO("Split Flip Cards", "Dual recessed flip-card panels for hours & minutes"),
    NEON_RING("Concentric Progress Rings", "Triple glowing rings for hours, minutes & seconds"),
    EDITORIAL_STACKED("Editorial Stacked", "Oversized stacked hours & minutes with accent date bar"),
    CHRONOGRAPH_PRO("Chronograph Pro", "Dual-subdial precision analog & digital telemetry watch")
}

object ClaraThemeManager {
    var isDarkMode by mutableStateOf(true)
    var accentPreset by mutableStateOf(AppAccentColorPreset.MINT_GREEN)
    var customAccentColorArgb by mutableLongStateOf(DEFAULT_ACCENT_COLOR_ARGB)
    var heroClockSplitRatio by mutableStateOf(0.50f)

    fun loadHeroClockSplitRatio(context: android.content.Context): Float {
        val saved = DashboardLayoutStore.getHeroClockSplitRatio(context).coerceIn(0.25f, 0.75f)
        heroClockSplitRatio = saved
        return saved
    }

    fun updateHeroClockSplitRatio(context: android.content.Context, newRatio: Float) {
        val clamped = newRatio.coerceIn(0.25f, 0.75f)
        heroClockSplitRatio = clamped
        DashboardLayoutStore.setHeroClockSplitRatio(context, clamped)
    }

    val activeAccentColor: Color
        get() = Color(customAccentColorArgb.toInt())

    val activeSurfaceColor: Color
        get() {
            val base = activeAccentColor
            return if (isDarkMode) {
                Color(
                    red = (base.red * 0.18f + 0.12f).coerceIn(0f, 1f),
                    green = (base.green * 0.18f + 0.13f).coerceIn(0f, 1f),
                    blue = (base.blue * 0.18f + 0.15f).coerceIn(0f, 1f),
                    alpha = 1f
                )
            } else {
                Color(
                    red = (base.red * 0.15f + 0.85f).coerceIn(0f, 1f),
                    green = (base.green * 0.15f + 0.85f).coerceIn(0f, 1f),
                    blue = (base.blue * 0.15f + 0.85f).coerceIn(0f, 1f),
                    alpha = 1f
                )
            }
        }
}

// Google Nest Signature Heating Orange Palette (Used when any Thermostat/Heater is actively heating)
val NestHeatingOrange = Color(0xFFFF6D00)
val NestHeatingDeepOrange = Color(0xFFE65100)
val NestHeatingGlowSurface = Color(0xFF382214)

// Layer 0: Main App Background (#202226 - deep matte charcoal matching SF-House)
val GhBackground: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF202226) else Color(0xFFF2F4F8)

// Floating Navigation Pill surface (#282B31)
val GhNavSurface: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF282B31) else Color(0xFFFFFFFF)

// Layer 1: Soft Recessed Well / Moat (#17191C - subtle step darker than #202226, never pitch black)
val GhSunkenWell: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF17191C) else Color(0xFFDCE1E8)

// Top Inner Well Shadow (#131518) & Bottom Lip Catch-Light (#2E323A)
val GhSunkenWellShadow: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF131518) else Color(0xFFC8CFD8)

val GhSunkenWellHighlight: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF2E323A) else Color(0xFFFFFFFF)

// Layer 2: Raised Inner Card Surface (#262930 - stepped up above the #17191C well and #202226 background)
val GhTileSurface: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF262930) else Color(0xFFFFFFFF)

// Layer 3: Inner Icon Badge & Elevated Control Surface (#31353E)
val GhTileSurfaceElevated: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF31353E) else Color(0xFFE7EBF0)

// Subtle Level Card Hairline Border (#343842)
val GhCardBorder: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF343842) else Color(0xFFDCE1E8)

val GhTileWarmActive: Color
    get() = ClaraThemeManager.activeSurfaceColor
val GhTileBlueActive: Color
    get() = ClaraThemeManager.activeSurfaceColor
val GhTileGreenActive: Color
    get() = ClaraThemeManager.activeSurfaceColor
val GhTilePurpleActive: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF2B2E38) else Color(0xFFEEE6FF)
val GhTileRedActive: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF382226) else Color(0xFFFFE5E7)

// Dynamic User-Selected Primary Accent Colour (Driven by the RGB Wheel)
val GhAccentLime: Color
    get() = ClaraThemeManager.activeAccentColor
val GhAccentGreen: Color
    get() = ClaraThemeManager.activeAccentColor
val GhAccentBlue: Color
    get() = ClaraThemeManager.activeAccentColor

val GhAccentWarm: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFFFF9E44) else Color(0xFFE65100)
val GhAccentPurple: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFFB5C7F2) else Color(0xFF673AB7)
val GhAccentRed: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFFFF6B6B) else Color(0xFFD93025)

// Crisp White-Silver Primary Text (#F2F4F7) & Muted Grey Secondary Text (#8A909A)
val GhTextPrimary: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFFF2F4F7) else Color(0xFF1B1E24)
val GhTextSecondary: Color
    get() = if (ClaraThemeManager.isDarkMode) Color(0xFF8A909A) else Color(0xFF5F6368)

val GhFont = FontFamily.SansSerif

// Multi-colour spectrum gradient stops for the thermostat & ambient arc
internal val ThermostatSpectrumColors = listOf(
    Color(0xFF00E5A3), // Mint Teal
    Color(0xFF00C6FF), // Electric Cyan
    Color(0xFF7C4DFF), // Deep Violet
    Color(0xFFFF4081), // Rose Pink
    Color(0xFFFF6D00), // Warm Heating Orange
    Color(0xFF00E5A3)
)

internal val FullRgbWheelColors = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000)
)

