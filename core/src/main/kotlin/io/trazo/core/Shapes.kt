package io.trazo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Formas de la herramienta de formas. Se guardan como trazos de tinta, igual que lo escrito a mano. */
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE, AXES }

object Shapes {
    /** Bajo esta diferencia relativa entre ancho y alto, el rectángulo y la elipse se vuelven cuadrado y círculo. */
    private const val SQUARE_SNAP = 0.08f

    /**
     * Trazos de la forma arrastrada de (x0, y0) a (x1, y1), con el estilo dado. Comparten un
     * [InkStroke.groupId] para moverse juntos y nunca forman parte de un gesto.
     */
    fun build(
        kind: ShapeKind,
        x0: Float, y0: Float, x1: Float, y1: Float,
        style: InkStyle,
        ids: IdGenerator,
        startTime: Long = 0L,
        groupId: String = ids.next(),
    ): List<InkStroke> = paths(kind, x0, y0, x1, y1).map { path ->
        val dense = densify(path, max(style.width, 3f))
        InkStroke(
            id = ids.next(),
            points = dense.mapIndexed { i, (x, y) -> InkPoint(x, y, 0.85f, i * 4L) },
            color = style.color,
            width = style.width,
            startTime = startTime,
            groupId = groupId,
            brush = style.brush,
        )
    }

    /** Recorridos de la forma en coordenadas de documento (cada uno será un trazo). */
    fun paths(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float): List<List<Pair<Float, Float>>> {
        val l = min(x0, x1)
        val r = max(x0, x1)
        val t = min(y0, y1)
        val b = max(y0, y1)
        return when (kind) {
            ShapeKind.LINE -> {
                val (ex, ey) = snapLine(x0, y0, x1, y1)
                listOf(listOf(x0 to y0, ex to ey))
            }
            ShapeKind.ARROW -> {
                val (ex, ey) = snapLine(x0, y0, x1, y1)
                listOf(listOf(x0 to y0, ex to ey), arrowHead(x0, y0, ex, ey))
            }
            ShapeKind.RECTANGLE -> {
                val (w, h) = squared(r - l, b - t)
                val rr = if (x1 >= x0) l + w else r
                val ll = rr - w
                val bb = if (y1 >= y0) t + h else b
                val tt = bb - h
                listOf(listOf(ll to tt, rr to tt, rr to bb, ll to bb, ll to tt))
            }
            ShapeKind.ELLIPSE -> {
                val (w, h) = squared(r - l, b - t)
                val cx = if (x1 >= x0) l + w / 2 else r - w / 2
                val cy = if (y1 >= y0) t + h / 2 else b - h / 2
                val n = 72
                // Empieza arriba y gira como se escribe una "o".
                listOf((0..n).map { i ->
                    val a = -PI / 2 - 2 * PI * i / n
                    (cx + w / 2 * cos(a)).toFloat() to (cy + h / 2 * sin(a)).toFloat()
                })
            }
            ShapeKind.TRIANGLE -> listOf(listOf((l + r) / 2 to t, r to b, l to b, (l + r) / 2 to t))
            ShapeKind.AXES -> {
                val cx = (l + r) / 2
                val cy = (t + b) / 2
                listOf(
                    listOf(l to cy, r to cy),
                    arrowHead(l, cy, r, cy),
                    listOf(cx to b, cx to t),
                    arrowHead(cx, b, cx, t),
                )
            }
        }
    }

    /** Extremo de la línea con el ángulo llevado a múltiplos de 45° si está a menos de [snapDegrees]. */
    fun snapLine(x0: Float, y0: Float, x1: Float, y1: Float, snapDegrees: Float = 6f): Pair<Float, Float> {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 1e-3f) return x1 to y1
        val deg = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
        val snapped = (deg / 45.0).roundToInt() * 45.0
        if (abs(deg - snapped) > snapDegrees) return x1 to y1
        val rad = Math.toRadians(snapped)
        return (x0 + len * cos(rad)).toFloat() to (y0 + len * sin(rad)).toFloat()
    }

    /** Punta de flecha en (x1, y1) como un solo trazo en V. */
    private fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float): List<Pair<Float, Float>> {
        val len = hypot(x1 - x0, y1 - y0)
        val head = (len * 0.18f).coerceIn(8f, 36f).coerceAtMost(len * 0.6f)
        val a = atan2(y1 - y0, x1 - x0)
        val spread = (28.0 * PI / 180).toFloat()
        fun wing(sign: Int) = (x1 - head * cos(a + sign * spread)) to (y1 - head * sin(a + sign * spread))
        return listOf(wing(1), x1 to y1, wing(-1))
    }

    private fun squared(w: Float, h: Float): Pair<Float, Float> {
        val m = max(w, h)
        return if (m > 0f && abs(w - h) <= SQUARE_SNAP * m) m to m else w to h
    }

    private fun densify(path: List<Pair<Float, Float>>, step: Float): List<Pair<Float, Float>> {
        val out = mutableListOf(path.first())
        for (i in 1 until path.size) {
            val (ax, ay) = path[i - 1]
            val (bx, by) = path[i]
            val n = ceil(hypot(bx - ax, by - ay) / step).toInt().coerceAtLeast(1)
            for (k in 1..n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
        }
        return out
    }
}
