package com.fastshare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// -------------------------------------------------------------------------------------
// Brand palette: a restrained blue primary with muted, functional accents.
// Historical names (RubyRed, Xender*, Zapya*) are kept so every screen picks it up.
// -------------------------------------------------------------------------------------
val RubyRed = Color(0xFF2F6BDB)         // Primary blue
val RubyRedLight = Color(0xFF5E8DE6)    // Lighter primary (icons/text on dark surfaces)
val RubyRedDark = Color(0xFF2353B0)     // Deeper primary (pressed states)
val RubyRedContainer = Color(0xFF17243B)
val AccentRed = Color(0xFFD14343)       // Destructive actions

val XenderGreen = RubyRed
val XenderGreenLight = RubyRedLight
val XenderGreenDark = RubyRedDark
val XenderOrange = RubyRed
val XenderOrangeDark = RubyRedDark

// Muted functional accents
val AccentCyan = Color(0xFF3A9FB5)      // Receiving
val AccentPurple = Color(0xFF7A72C9)
val AccentEmerald = Color(0xFF2F9E6E)   // Success
val AccentAmber = Color(0xFFC98A2B)     // Warning

// Near-flat fills, kept as brushes for existing call sites
val RubyGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF3270DE), Color(0xFF2B64D2))
)
val SendButtonGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF3270DE), Color(0xFF2B64D2))
)
val CardGlowGradient = Brush.verticalGradient(
    colors = listOf(RubyRed.copy(alpha = 0.06f), Color.Transparent)
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
    bgVoid = Color(0xFF121212),
    surfaceBase = Color(0xFF1A1A1A),
    surfaceCard = Color(0xFF1F1F1F),
    surfaceElevated = Color(0xFF292929),
    borderSubtle = Color(0xFF2E2E2E),
    textPrimary = Color(0xFFEDEDED),
    textSecondary = Color(0xFFA3A3A3),
    textMuted = Color(0xFF737373),
    isDark = true
)

val LightAppColors = AppColorPalette(
    bgVoid = Color(0xFFF6F7F9),
    surfaceBase = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEFF1F4),
    borderSubtle = Color(0xFFE3E6EA),
    textPrimary = Color(0xFF1B1F24),
    textSecondary = Color(0xFF57606A),
    textMuted = Color(0xFF8C959F),
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
    onPrimaryContainer = Color(0xFFD6E2FA),
    secondary = AccentCyan,
    onSecondary = Color.White,
    background = DarkAppColors.bgVoid,
    onBackground = DarkAppColors.textPrimary,
    surface = DarkAppColors.surfaceBase,
    onSurface = DarkAppColors.textPrimary,
    surfaceVariant = DarkAppColors.surfaceCard,
    onSurfaceVariant = DarkAppColors.textSecondary,
    outline = DarkAppColors.borderSubtle,
    error = Color(0xFFE5484D)
)

private val LightColorScheme = lightColorScheme(
    primary = RubyRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFC),
    onPrimaryContainer = RubyRedDark,
    secondary = Color(0xFF2D8299),
    onSecondary = Color.White,
    background = LightAppColors.bgVoid,
    onBackground = LightAppColors.textPrimary,
    surface = LightAppColors.surfaceBase,
    onSurface = LightAppColors.textPrimary,
    surfaceVariant = LightAppColors.surfaceElevated,
    onSurfaceVariant = LightAppColors.textSecondary,
    outline = LightAppColors.borderSubtle,
    error = Color(0xFFCD2B31)
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
