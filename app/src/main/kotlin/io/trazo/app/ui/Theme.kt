package io.trazo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.trazo.app.data.DEFAULT_ACCENT
import io.trazo.app.data.DEFAULT_ACCENT_2
import io.trazo.app.data.PaperTone
import io.trazo.app.data.ThemeMode
import io.trazo.app.ink.PaperColors
import io.trazo.app.ink.argbToHsl
import io.trazo.app.ink.hslToArgb
import io.trazo.core.DEFAULT_INK_COLOR
import kotlin.math.max
import kotlin.math.min

/** La interfaz está en modo oscuro (por ajuste o por el sistema). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Tema negro puro (pantallas OLED). */
val LocalOledTheme = staticCompositionLocalOf { false }

/** Preajuste de color: el acento (herramienta activa, botones) y un segundo tono para detalles. */
data class AccentPreset(val name: String, val accent: Int, val second: Int)

val AccentPresets = listOf(
    AccentPreset("Mañana", DEFAULT_ACCENT, DEFAULT_ACCENT_2),
    AccentPreset("Atardecer", 0xFFE8833A.toInt(), 0xFF9B7FE6.toInt()),
    AccentPreset("Mar", 0xFF1FA67A.toInt(), 0xFF5B7B8C.toInt()),
    AccentPreset("Bosque", 0xFF8DB33A.toInt(), 0xFF6E8A6A.toInt()),
    AccentPreset("Tierra", 0xFFC9A04A.toInt(), 0xFF8C7A6B.toInt()),
    AccentPreset("Lavanda", 0xFFB57EDC.toInt(), 0xFF8A6CF0.toInt()),
    AccentPreset("Lago", 0xFFB5A42E.toInt(), 0xFF3AA6C9.toInt()),
    AccentPreset("Desierto", 0xFFE07A4A.toInt(), 0xFF9C8A5A.toInt()),
)

/** Tintas de la pluma (ARGB): oscuras para hoja clara; en hoja oscura se muestran aclaradas. */
val PenPalette: List<Int> = listOf(
    DEFAULT_INK_COLOR, 0xFF1B1B1B, 0xFF6B6F73, 0xFF1E4FA0, 0xFF1E88E5,
    0xFF00897B, 0xFF2E7D32, 0xFF7CB342, 0xFFF9A825, 0xFFEF6C00,
    0xFFB3261E, 0xFFC2185B, 0xFF6A3FA0, 0xFF6D4C41, 0xFFFFFFFF,
).map { it.toInt() }

val HighlighterPalette: List<Int> = listOf(
    0xFFFFE066, 0xFFA5F2A0, 0xFF9AD0FF, 0xFFFFA6D8, 0xFFFFC27A, 0xFFCDB4FF,
).map { it.toInt() }

@Composable
fun TrazoTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: Int = DEFAULT_ACCENT,
    accent2: Int = DEFAULT_ACCENT_2,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.OLED -> true
    }
    val oled = mode == ThemeMode.OLED
    val scheme = remember(dark, oled, accent, accent2) { trazoColorScheme(dark, oled, accent, accent2) }
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalOledTheme provides oled) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Colores de la hoja según el ajuste y el tema de la interfaz. */
@Composable
fun paperColors(tone: PaperTone): PaperColors {
    val dark = LocalDarkTheme.current
    val oled = LocalOledTheme.current
    return when {
        tone == PaperTone.LIGHT || tone == PaperTone.THEME && !dark -> PaperColors.LIGHT
        oled -> PaperColors.BLACK
        else -> PaperColors.DARK
    }
}

/** El mismo tono con otra luminosidad (y, si se indica, otra saturación). */
private fun tone(argb: Int, lightness: Float, saturation: Float? = null): Color {
    val hsl = argbToHsl(argb)
    return Color(hslToArgb(hsl[0], saturation ?: hsl[1], lightness))
}

/**
 * Esquema de Material 3 a partir del acento: superficies neutras (gris azulado en oscuro, papel
 * cálido en claro) y los roles de color derivados del acento y del segundo tono.
 */
fun trazoColorScheme(dark: Boolean, oled: Boolean, accent: Int, second: Int): ColorScheme {
    val s1 = argbToHsl(accent)[1]
    val s2 = argbToHsl(second)[1]
    return if (dark) {
        val black = oled
        darkColorScheme(
            primary = tone(accent, 0.70f, min(1f, s1 + 0.12f)),
            onPrimary = tone(accent, 0.12f),
            primaryContainer = tone(accent, 0.27f, min(1f, s1 + 0.05f)),
            onPrimaryContainer = tone(accent, 0.88f),
            inversePrimary = tone(accent, 0.40f),
            secondary = tone(second, 0.72f, min(1f, s2 + 0.08f)),
            onSecondary = tone(second, 0.13f),
            secondaryContainer = tone(second, 0.26f),
            onSecondaryContainer = tone(second, 0.88f),
            tertiary = tone(second, 0.72f, min(1f, s2 + 0.08f)),
            onTertiary = tone(second, 0.13f),
            tertiaryContainer = tone(second, 0.26f),
            onTertiaryContainer = tone(second, 0.88f),
            background = if (black) Color.Black else Color(0xFF121317),
            onBackground = Color(0xFFE6E4DF),
            surface = if (black) Color.Black else Color(0xFF16171B),
            onSurface = Color(0xFFE6E4DF),
            surfaceVariant = if (black) Color(0xFF1C1C21) else Color(0xFF2A2B31),
            onSurfaceVariant = Color(0xFFA9A8A3),
            surfaceTint = Color(0xFF8A8C96),
            inverseSurface = Color(0xFFE6E4DF),
            inverseOnSurface = Color(0xFF26272C),
            outline = Color(0xFF5A5B63),
            outlineVariant = if (black) Color(0xFF2A2A30) else Color(0xFF34353C),
            surfaceBright = if (black) Color(0xFF26262C) else Color(0xFF34353C),
            surfaceDim = if (black) Color.Black else Color(0xFF121317),
            surfaceContainerLowest = if (black) Color.Black else Color(0xFF0E0F12),
            surfaceContainerLow = if (black) Color(0xFF0B0B0D) else Color(0xFF1A1B20),
            surfaceContainer = if (black) Color(0xFF111114) else Color(0xFF1E1F24),
            surfaceContainerHigh = if (black) Color(0xFF18181C) else Color(0xFF25262C),
            surfaceContainerHighest = if (black) Color(0xFF202025) else Color(0xFF2D2E35),
        )
    } else {
        val l1 = argbToHsl(accent)[2]
        lightColorScheme(
            primary = tone(accent, min(0.42f, max(0.30f, l1)), min(1f, s1 + 0.08f)),
            onPrimary = Color.White,
            primaryContainer = tone(accent, 0.86f, min(1f, s1 + 0.15f)),
            onPrimaryContainer = tone(accent, 0.15f),
            inversePrimary = tone(accent, 0.72f),
            secondary = tone(second, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = tone(second, 0.88f, min(1f, s2 + 0.1f)),
            onSecondaryContainer = tone(second, 0.15f),
            tertiary = tone(second, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = tone(second, 0.88f, min(1f, s2 + 0.1f)),
            onTertiaryContainer = tone(second, 0.15f),
            background = Color(0xFFF3F1EC),
            onBackground = Color(0xFF1C1D1F),
            surface = Color(0xFFFAF9F6),
            onSurface = Color(0xFF1C1D1F),
            surfaceVariant = Color(0xFFE4E2DC),
            onSurfaceVariant = Color(0xFF4A4B50),
            surfaceTint = Color(0xFF7A7B80),
            inverseSurface = Color(0xFF2E2F33),
            inverseOnSurface = Color(0xFFF1F0EC),
            outline = Color(0xFF7A7B80),
            outlineVariant = Color(0xFFCAC8C2),
            surfaceBright = Color(0xFFFAF9F6),
            surfaceDim = Color(0xFFDCDAD4),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF6F4EF),
            surfaceContainer = Color(0xFFF0EEE8),
            surfaceContainerHigh = Color(0xFFEAE8E2),
            surfaceContainerHighest = Color(0xFFE4E2DC),
        )
    }
}

/** Texto "#9A7C42" de un color ARGB (sin transparencia). */
fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
