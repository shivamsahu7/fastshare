package com.fastshare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// -------------------------------------------------------------------------------------
// Professional Crimson / Ruby Red Palette (Brand Colors for both Dark & Light)
// -------------------------------------------------------------------------------------
val RubyRed = Color(0xFFE11D48)         // Primary Electric Crimson
val RubyRedLight = Color(0xFFFF3366)    // Vivid Ruby Rose
val RubyRedDark = Color(0xFFBE123C)     // Deep Velvet Ruby
val RubyRedContainer = Color(0xFF3B0B17)
val AccentRed = RubyRed

// Primary Brand Palette (User-Requested Professional Ruby Red)
val XenderGreen = RubyRed                // Professional Electric Crimson (0xFFE11D48)
val XenderGreenLight = RubyRedLight      // Vivid Ruby Rose (0xFFFF3366)
val XenderGreenDark = RubyRedDark        // Deep Velvet Ruby (0xFFBE123C)
val XenderOrange = RubyRed               // Vibrant Crimson
val XenderOrangeDark = RubyRedDark       // Deep Ruby

// Accent Complementary Colors
val AccentCyan = Color(0xFF00E5FF)
val AccentPurple = Color(0xFF8B5CF6)
val AccentEmerald = Color(0xFF10B981)
val AccentAmber = Color(0xFFF59E0B)

// Gradients
val RubyGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFFFF3366), Color(0xFFE11D48))
)
val SendButtonGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFFFF2A55), Color(0xFFD90429))
)
val CardGlowGradient = Brush.verticalGradient(
    colors = listOf(RubyRed.copy(alpha = 0.12f), Color.Transparent)
)

// -------------------------------------------------------------------------------------
// Theme Colors Interface & Palettes (Dark and Light Modes)
// -------------------------------------------------------------------------------------
class AppColorPalette(
    val bgVoid: Color,
    val surfaceBase: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val isDark: Boolean
)

val DarkAppColors = AppColorPalette(
    bgVoid = Color(0xFF090C12),
    surfaceBase = Color(0xFF101520),
    surfaceCard = Color(0xFF161D2B),
    surfaceElevated = Color(0xFF1F273A),
    borderSubtle = Color(0xFF28334A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    isDark = true
)

val LightAppColors = AppColorPalette(
    bgVoid = Color(0xFFF8FAFC),        // Slate 50
    surfaceBase = Color(0xFFFFFFFF),    // Clean pure white
    surfaceCard = Color(0xFFFFFFFF),    // Card surface
    surfaceElevated = Color(0xFFF1F5F9),// Slate 100
    borderSubtle = Color(0xFFE2E8F0),   // Slate 200
    textPrimary = Color(0xFF0F172A),    // Slate 900
    textSecondary = Color(0xFF475569),  // Slate 600
    textMuted = Color(0xFF94A3B8),      // Slate 400
    isDark = false
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

// Global Theme State
object ThemeState {
    var isDarkMode by mutableStateOf(true)

    fun toggle() {
        isDarkMode = !isDarkMode
    }
}

// -------------------------------------------------------------------------------------
// Dynamic Composable Color Accessors (Seamless Dark/Light Switch)
// -------------------------------------------------------------------------------------
val BgVoid: Color @Composable get() = LocalAppColors.current.bgVoid
val SurfaceBase: Color @Composable get() = LocalAppColors.current.surfaceBase
val SurfaceCard: Color @Composable get() = LocalAppColors.current.surfaceCard
val SurfaceElevated: Color @Composable get() = LocalAppColors.current.surfaceElevated
val BorderSubtle: Color @Composable get() = LocalAppColors.current.borderSubtle
val TextPrimary: Color @Composable get() = LocalAppColors.current.textPrimary
val TextSecondary: Color @Composable get() = LocalAppColors.current.textSecondary
val TextMuted: Color @Composable get() = LocalAppColors.current.textMuted

// Backward Compatibility Aliases
val ZapyaOrange = RubyRed
val ZapyaBg: Color @Composable get() = BgVoid
val ZapyaSurface: Color @Composable get() = SurfaceBase
val ZapyaCard: Color @Composable get() = SurfaceCard
val ZapyaElevated: Color @Composable get() = SurfaceElevated
val ZapyaBorder: Color @Composable get() = BorderSubtle
val ZapyaTextPrimary: Color @Composable get() = TextPrimary
val ZapyaTextSecondary: Color @Composable get() = TextSecondary
val ZapyaTextMuted: Color @Composable get() = TextMuted
val ZapyaCyan = AccentCyan
val ZapyaPurple = AccentPurple
val ZapyaEmerald = AccentEmerald
val ZapyaGradient = RubyGradient

private val DarkColorScheme = darkColorScheme(
    primary = RubyRed,
    onPrimary = Color.White,
    primaryContainer = RubyRedContainer,
    onPrimaryContainer = Color(0xFFFFD5DD),
    secondary = AccentCyan,
    onSecondary = Color.Black,
    background = Color(0xFF090C12),
    onBackground = Color.White,
    surface = Color(0xFF101520),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF161D2B),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFEF4444)
)

private val LightColorScheme = lightColorScheme(
    primary = RubyRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E6),
    onPrimaryContainer = RubyRedDark,
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626)
)

@Composable
fun FastShareTheme(
    darkTheme: Boolean = ThemeState.isDarkMode,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkAppColors else LightAppColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
