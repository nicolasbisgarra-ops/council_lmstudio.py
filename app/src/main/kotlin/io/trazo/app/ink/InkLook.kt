package io.trazo.app.ink

import io.trazo.core.Brush
import kotlin.math.abs
import kotlin.math.pow

/** Colores de la hoja y del escritorio que la rodea (ARGB). */
data class PaperColors(
    val desk: Int,
    val sheet: Int,
    val line: Int,
    val margin: Int,
    val shadow: Int,
    val dark: Boolean,
    /** Contorno de la hoja (0 = sin contorno): en negro puro marca dónde termina la hoja. */
    val border: Int = 0,
) {
    companion object {
        val LIGHT = PaperColors(
            desk = 0xFFE4E1D9.toInt(), sheet = 0xFFFBFAF5.toInt(), line = 0xFFD7E3EA.toInt(),
            margin = 0xFFF0C8C8.toInt(), shadow = 0x28000000, dark = false,
        )
        val DARK = PaperColors(
            desk = 0xFF0E0F12.toInt(), sheet = 0xFF1A1B20.toInt(), line = 0xFF2E3138.toInt(),
            margin = 0xFF4E3036.toInt(), shadow = 0x50000000, dark = true,
        )
        /** OLED: hoja negra pura sobre negro puro, con contorno blanco para ver el borde. */
        val BLACK = PaperColors(
            desk = 0xFF000000.toInt(), sheet = 0xFF000000.toInt(), line = 0xFF24262B.toInt(),
            margin = 0xFF40262C.toInt(), shadow = 0, dark = true, border = 0xE6FFFFFF.toInt(),
        )
    }
}

/**
 * Cómo se ve la tinta según la hoja. Solo cambia el pintado: el color guardado en el trazo es el
 * que eligió el usuario. Sobre hoja oscura las tintas muy oscuras se aclaran conservando el tono
 * (y sobre hoja clara las muy claras se oscurecen), como hacen las apps de notas en modo oscuro.
 */
object InkLook {
    /** Opacidad por omisión del resaltador (0..255). */
    const val HIGHLIGHTER_ALPHA = 0x66

    /**
     * Opacidad de un trazo de resaltador: va en el canal alfa de su color. Un color opaco (FF, como
     * guardaban las versiones anteriores) usa la opacidad por omisión.
     */
    fun highlighterAlpha(color: Int): Int = (color ushr 24).let { if (it == 0xFF || it == 0) HIGHLIGHTER_ALPHA else it }

    /** Color con el que se pinta un resaltador (color con su opacidad). */
    fun highlighterPaint(color: Int): Int = withAlpha(color, highlighterAlpha(color))

    fun displayColor(color: Int, darkPaper: Boolean, brush: Brush = Brush.PEN): Int {
        if (brush == Brush.HIGHLIGHTER) return color
        val lum = luminance(color)
        val hsl = when {
            darkPaper && lum < 0.18f -> argbToHsl(color).also { it[2] = (1f - it[2] * 0.6f).coerceIn(0.70f, 0.93f) }
            !darkPaper && lum > 0.80f -> argbToHsl(color).also { it[2] = ((1f - it[2]) * 0.6f + 0.08f).coerceIn(0.08f, 0.30f) }
            else -> return color
        }
        return (color and 0xFF000000.toInt()) or (hslToArgb(hsl[0], hsl[1], hsl[2]) and 0xFFFFFF)
    }

    /** Luminancia relativa (sRGB) 0..1. */
    fun luminance(color: Int): Float {
        fun lin(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * lin(color shr 16 and 0xFF) + 0.7152f * lin(color shr 8 and 0xFF) + 0.0722f * lin(color and 0xFF)
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

/** Tono (0..360), saturación y luminosidad (0..1) de un color ARGB. */
fun argbToHsl(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
    val s = (d / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
    val h = when (mx) {
        r -> 60f * ((g - b) / d).mod(6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return floatArrayOf(h.mod(360f), s, l)
}

/** Color opaco a partir de tono, saturación y luminosidad. */
fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val c = (1f - abs(2f * l - 1f)) * s.coerceIn(0f, 1f)
    val hp = h.mod(360f) / 60f
    val x = c * (1f - abs(hp.mod(2f) - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l.coerceIn(0f, 1f) - c / 2f
    fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
}
