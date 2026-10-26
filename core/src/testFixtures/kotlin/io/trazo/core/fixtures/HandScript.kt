package io.trazo.core.fixtures

import io.trazo.core.DefaultPatterns
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Recorridos de mano simulada para pruebas y la demo automatizada.
 *
 * Son SOLO la entrada del lápiz sintético (lo que haría la mano): cada carácter se convierte en
 * movimientos del lápiz que el motor registra como tinta normal. La app no contiene ninguna
 * fuente ni glifo; esto vive en testFixtures y nunca se empaqueta en la APK.
 */
object HandScript {
    /** Un trazo = lista de puntos en una caja de 1 de alto (y hacia abajo). */
    private class Glyph(val advance: Float, val strokes: List<List<Pair<Float, Float>>>)

    private fun line(vararg p: Pair<Float, Float>) = p.toList()
    private fun smooth(vararg p: Pair<Float, Float>) = DefaultPatterns.curve(*p, perSegment = 6)
    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<Pair<Float, Float>> =
        (0..28).map { i -> val a = -PI / 2 - 2 * PI * i / 28; (cx + rx * cos(a)).toFloat() to (cy + ry * sin(a)).toFloat() }

    private val glyphs: Map<Char, Glyph> = mapOf(
        '0' to Glyph(0.62f, listOf(ellipse(0.3f, 0.5f, 0.24f, 0.48f))),
        '1' to Glyph(0.5f, listOf(line(0.12f to 0.2f, 0.32f to 0.02f, 0.32f to 1f))),
        '2' to Glyph(0.62f, listOf(smooth(0.06f to 0.22f, 0.18f to 0.04f, 0.36f to 0f, 0.52f to 0.12f, 0.5f to 0.36f, 0.06f to 1f, 0.58f to 1f))),
        '3' to Glyph(0.62f, listOf(smooth(0.05f to 0.1f, 0.3f to 0f, 0.5f to 0.12f, 0.45f to 0.35f, 0.25f to 0.47f, 0.5f to 0.6f, 0.55f to 0.82f, 0.35f to 1f, 0.05f to 0.92f))),
        '4' to Glyph(0.62f, listOf(line(0.42f to 1f, 0.42f to 0f, 0f to 0.68f, 0.6f to 0.68f))),
        '5' to Glyph(0.62f, listOf(smooth(0.55f to 0f, 0.14f to 0f, 0.09f to 0.45f, 0.35f to 0.4f, 0.55f to 0.6f, 0.5f to 0.88f, 0.3f to 1f, 0.05f to 0.92f))),
        '6' to Glyph(0.62f, listOf(smooth(0.5f to 0.05f, 0.25f to 0.15f, 0.08f to 0.55f, 0.15f to 0.9f, 0.35f to 1f, 0.52f to 0.85f, 0.5f to 0.6f, 0.3f to 0.5f, 0.1f to 0.65f))),
        '7' to Glyph(0.62f, listOf(line(0.02f to 0f, 0.58f to 0f, 0.25f to 1f))),
        '8' to Glyph(0.62f, listOf(smooth(0.3f to 0.48f, 0.08f to 0.3f, 0.15f to 0.05f, 0.3f to 0f, 0.48f to 0.08f, 0.5f to 0.28f, 0.3f to 0.48f, 0.05f to 0.7f, 0.12f to 0.95f, 0.32f to 1f, 0.52f to 0.9f, 0.55f to 0.7f, 0.3f to 0.48f))),
        '9' to Glyph(0.62f, listOf(smooth(0.5f to 0.3f, 0.3f to 0.45f, 0.1f to 0.3f, 0.15f to 0.05f, 0.4f to 0f, 0.52f to 0.2f, 0.48f to 1f))),
        'x' to Glyph(0.62f, listOf(line(0.05f to 0.4f, 0.52f to 1f), line(0.52f to 0.4f, 0.05f to 1f))),
        'd' to Glyph(0.62f, listOf(smooth(0.48f to 0.6f, 0.3f to 0.45f, 0.1f to 0.6f, 0.08f to 0.85f, 0.25f to 1f, 0.48f to 0.88f), line(0.5f to 0f, 0.5f to 1f))),
        '+' to Glyph(0.62f, listOf(line(0.05f to 0.62f, 0.55f to 0.62f), line(0.3f to 0.37f, 0.3f to 0.87f))),
        '-' to Glyph(0.56f, listOf(line(0.05f to 0.62f, 0.5f to 0.62f))),
        '=' to Glyph(0.62f, listOf(line(0.05f to 0.52f, 0.52f to 0.52f), line(0.05f to 0.74f, 0.52f to 0.74f))),
        '(' to Glyph(0.42f, listOf(smooth(0.34f to 0f, 0.12f to 0.3f, 0.1f to 0.7f, 0.34f to 1f))),
        ')' to Glyph(0.42f, listOf(smooth(0.08f to 0f, 0.3f to 0.3f, 0.32f to 0.7f, 0.08f to 1f))),
        '[' to Glyph(0.42f, listOf(line(0.34f to 0f, 0.1f to 0f, 0.1f to 1f, 0.34f to 1f))),
        ']' to Glyph(0.42f, listOf(line(0.08f to 0f, 0.32f to 0f, 0.32f to 1f, 0.08f to 1f))),
        ' ' to Glyph(0.38f, emptyList()),
    )

    /**
     * Convierte texto en recorridos de lápiz. `^c` escribe el carácter siguiente como superíndice
     * y `_c` como subíndice. Devuelve los trazos y la posición x final.
     */
    fun write(text: String, x: Float, y: Float, size: Float, seed: Int = 1): Pair<List<List<Pair<Float, Float>>>, Float> {
        val random = Random(seed)
        val out = mutableListOf<List<Pair<Float, Float>>>()
        var cx = x
        var i = 0
        while (i < text.length) {
            var ch = text[i]
            var scale = 1f
            var dy = 0f
            if ((ch == '^' || ch == '_') && i + 1 < text.length) {
                scale = 0.55f
                dy = if (ch == '^') -0.25f * size else 0.6f * size
                i++
                ch = text[i]
            }
            val g = glyphs[ch] ?: error("Sin recorrido para '$ch'")
            val s = size * scale
            val slant = 0.08f
            for (stroke in g.strokes) {
                out += stroke.map { (gx, gy) ->
                    val jx = (random.nextFloat() - 0.5f) * 0.02f * s
                    val jy = (random.nextFloat() - 0.5f) * 0.02f * s
                    (cx + gx * s + (1f - gy) * slant * s + jx) to (y + dy + gy * s + jy)
                }
            }
            cx += g.advance * s
            i++
        }
        return out to cx
    }

    /** Gesto rápido de integral, a mano alzada (no es la plantilla exacta). */
    fun integralGesture(x: Float, y: Float, height: Float): List<Pair<Float, Float>> {
        val s = height / 100f
        return smooth(78f to 4f, 62f to 3f, 52f to 12f, 48f to 28f, 48f to 46f, 44f to 62f, 36f to 80f, 22f to 95f, 8f to 93f)
            .map { (gx, gy) -> (x + gx * s) to (y + gy * s) }
    }

    /** Raíz en un solo trazo (tilde, bajada, subida y barra). */
    fun rootGesture(x: Float, y: Float, height: Float, barLength: Float): List<Pair<Float, Float>> {
        val s = height / 70f
        return listOf(4f to 50f, 13f to 46f, 27f to 74f, 46f to 6f).map { (gx, gy) -> (x + gx * s) to (y + gy * s) } +
            listOf((x + 46f * s + barLength) to (y + 7f * s))
    }

    fun fractionGesture(x: Float, y: Float, width: Float): List<Pair<Float, Float>> =
        listOf(x to y, (x + width * 0.5f) to (y + 1.5f), (x + width) to (y - 1f))
}
