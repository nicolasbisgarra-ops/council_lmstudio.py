package io.trazo.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Patrones de muestra (integral, raíz, fracción) construidos con VECTORES DE TRAZOS.
 * Cada uno tiene un gesto rápido distinto de su salida "pasada en limpio". El usuario puede
 * redibujar ambos en el editor de patrones; estos son solo un punto de partida.
 */
object DefaultPatterns {
    const val INTEGRAL = "integral"
    const val ROOT = "raiz"
    const val FRACTION = "fraccion"

    /** Los tres patrones de partida (integral, raíz, barra). */
    fun core(): List<InkPattern> = listOf(integral(), root(), fraction())

    fun all(): List<InkPattern> = core()

    fun integral() = InkPattern(
        id = INTEGRAL,
        name = "Integral",
        gestureExamples = listOf(
            // S alargada rápida, de arriba-derecha a abajo-izquierda.
            listOf(stroke("g-int-1", curve(80f to 2f, 64f to 2f, 51f to 11f, 47f to 25f, 49f to 44f, 45f to 60f, 35f to 80f, 20f to 96f, 7f to 95f))),
        ),
        outputInk = listOf(
            stroke("o-int-1", curve(66f to 6f, 60f to 0f, 52f to 1f, 47f to 9f, 45f to 24f, 44f to 50f, 42f to 76f, 39f to 91f, 32f to 99f, 24f to 98f, 20f to 92f), taper = true, width = 3.4f),
        ),
        sizing = OutputSizing.MATCH_HEIGHT,
        outputScale = 1.1f,
        builtIn = true,
        category = PatternCategory.CALCULUS,
    )

    fun root() = InkPattern(
        id = ROOT,
        name = "Raíz",
        gestureExamples = listOf(
            // Un trazo: tilde + subida + barra.
            listOf(stroke("g-rt-1", polyline(4f to 55f, 12f to 50f, 27f to 79f, 47f to 10f, 97f to 10f))),
            // Un trazo con barra larga (la barra crece con el radicando).
            listOf(stroke("g-rt-3", polyline(4f to 55f, 12f to 50f, 27f to 79f, 47f to 10f, 160f to 10f))),
            // Dos trazos: ✓ y barra horizontal.
            listOf(
                stroke("g-rt-2a", polyline(4f to 55f, 12f to 50f, 27f to 79f, 47f to 10f)),
                stroke("g-rt-2b", polyline(47f to 10f, 72f to 11f, 97f to 10f)),
            ),
        ),
        outputInk = listOf(
            stroke("o-rt-1", curve(2f to 60f, 9f to 55f, 14f to 58f, 22f to 80f, 27f to 92f, 32f to 70f, 39f to 38f, 46f to 10f, 49f to 4f), taper = true, width = 3.2f),
            stroke("o-rt-2", wavy(49f, 4f, 150f, 1.2f), taper = true, width = 3.0f),
        ),
        sizing = OutputSizing.MATCH_HEIGHT,
        outputScale = 1.15f,
        builtIn = true,
        category = PatternCategory.CALCULUS,
        // La barra (desde el final del ✓) llega hasta donde terminó el gesto.
        stretch = Stretch(Axis.HORIZONTAL, from = 0.24f),
    )

    fun fraction() = InkPattern(
        id = FRACTION,
        name = "Fracción",
        gestureExamples = listOf(listOf(stroke("g-fr-1", polyline(2f to 51f, 50f to 50f, 98f to 49f)))),
        outputInk = listOf(stroke("o-fr-1", wavy(0f, 50f, 100f, 0.9f), taper = true, width = 3.0f)),
        sizing = OutputSizing.MATCH_WIDTH,
        outputScale = 1.15f,
        builtIn = true,
        category = PatternCategory.OPERATORS,
    )

    // ------------------------------------------------------------- utilidades de construcción

    private fun stroke(id: String, pts: List<Pair<Float, Float>>, taper: Boolean = false, width: Float = DEFAULT_INK_WIDTH): InkStroke {
        val n = pts.size
        return InkStroke(
            id = id,
            width = width,
            points = pts.mapIndexed { i, (x, y) ->
                val u = if (n == 1) 0.5f else i.toFloat() / (n - 1)
                val p = if (taper) (0.35f + 0.65f * sin(PI * u).toFloat()).coerceIn(0.3f, 1f) else 0.8f
                InkPoint(x, y, p, (i * 8).toLong())
            },
        )
    }

    private fun polyline(vararg pts: Pair<Float, Float>): List<Pair<Float, Float>> {
        val out = mutableListOf(pts[0])
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]
            val (bx, by) = pts[i]
            for (k in 1..8) out += (ax + (bx - ax) * k / 8f) to (ay + (by - ay) * k / 8f)
        }
        return out
    }

    /** Curva Catmull-Rom que pasa por los puntos de control (aspecto manuscrito suave). */
    fun curve(vararg c: Pair<Float, Float>, perSegment: Int = 8): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until c.size - 1) {
            val p0 = c[maxOf(i - 1, 0)]
            val p1 = c[i]
            val p2 = c[i + 1]
            val p3 = c[minOf(i + 2, c.size - 1)]
            for (k in 0 until perSegment) {
                val t = k.toFloat() / perSegment
                val t2 = t * t
                val t3 = t2 * t
                fun cr(a: Float, b: Float, cc: Float, d: Float) =
                    0.5f * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)
                out += cr(p0.first, p1.first, p2.first, p3.first) to cr(p0.second, p1.second, p2.second, p3.second)
            }
        }
        out += c.last()
        return out
    }

    private fun wavy(x0: Float, y0: Float, length: Float, amplitude: Float): List<Pair<Float, Float>> =
        (0..24).map { i ->
            val u = i / 24f
            (x0 + length * u) to (y0 + amplitude * sin(u * 2.3f * PI.toFloat()) - 0.8f * u)
        }
}
