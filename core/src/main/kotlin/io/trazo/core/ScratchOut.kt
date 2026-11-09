package io.trazo.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Tachar para borrar: un garabato en zigzag apretado encima de lo escrito lo borra (como en Goodnotes o Nebo).
 *
 * El trazo cuenta como tachado si va y vuelve muchas veces sobre la misma zona: al menos [MIN_REVERSALS] vueltas
 * a lo largo de su eje principal, cada pasada cruzando buena parte del ancho, y un recorrido varias veces más
 * largo que la diagonal de su caja. Solo se borran trazos que quedan casi enteros dentro del tachado y que el
 * tachado de verdad pisa; si no hay ninguno, el trazo se guarda como tinta normal (un zigzag en blanco es dibujo).
 */
object ScratchOut {
    const val MIN_REVERSALS = 4

    /** Cuánto más largo que la diagonal de su caja tiene que ser el recorrido. */
    const val MIN_DENSITY = 3.5f

    /** Parte de los puntos de un trazo que tiene que quedar dentro del tachado para borrarlo. */
    const val MIN_COVERED = 0.75f

    /** Un tachado más chico que esto (en las unidades de los puntos) es un garabato de letra, no un tachado. */
    const val MIN_SIZE_PX = 14f

    /** ¿Tiene forma de tachado? (independiente de lo que haya debajo). */
    fun isScratch(points: List<InkPoint>, minSizePx: Float = MIN_SIZE_PX): Boolean {
        if (points.size < 8) return false
        val b = Bounds.of(points)
        if (b.maxSide < minSizePx) return false
        var length = 0f
        for (i in 1 until points.size) length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        val diagonal = max(hypot(b.width, b.height), 1f)
        if (length < MIN_DENSITY * diagonal) return false
        return max(reversals(points) { it.x }, reversals(points) { it.y }) >= MIN_REVERSALS
    }

    /**
     * Vueltas a lo largo de un eje, con histéresis: una vuelta cuenta solo si la pasada anterior recorrió al menos
     * el 40 % del ancho del trazo en ese eje (el temblor y las curvas de una letra no cuentan).
     */
    internal fun reversals(points: List<InkPoint>, axis: (InkPoint) -> Float): Int {
        val lo = points.minOf(axis)
        val hi = points.maxOf(axis)
        val span = hi - lo
        if (span <= 0f) return 0
        val minRun = 0.4f * span
        var count = 0
        var dir = 0
        var extreme = axis(points[0])
        var runStart = extreme
        for (p in points) {
            val v = axis(p)
            when {
                dir >= 0 && v > extreme -> { extreme = v; if (dir == 0 && v - runStart >= minRun / 4) dir = 1 }
                dir <= 0 && v < extreme -> { extreme = v; if (dir == 0 && runStart - v >= minRun / 4) dir = -1 }
                dir == 1 && extreme - v >= minRun / 4 -> {
                    if (abs(extreme - runStart) >= minRun) count++
                    runStart = extreme; extreme = v; dir = -1
                }
                dir == -1 && v - extreme >= minRun / 4 -> {
                    if (abs(extreme - runStart) >= minRun) count++
                    runStart = extreme; extreme = v; dir = 1
                }
            }
        }
        return count
    }

    /**
     * Los trazos de [candidates] que borra el tachado [scratch]: casi todos sus puntos dentro de la caja del
     * tachado (agrandada [slop]) y al menos un punto a menos de [reach] del recorrido del tachado.
     * Vacío si [scratch] no tiene forma de tachado.
     */
    fun targets(scratch: List<InkPoint>, candidates: List<InkStroke>, slop: Float, reach: Float, minSizePx: Float = MIN_SIZE_PX): List<InkStroke> {
        if (!isScratch(scratch, minSizePx)) return emptyList()
        val box = Bounds.of(scratch).inflate(slop)
        return candidates.filter { s ->
            if (s.points.isEmpty() || box.gapTo(s.bounds) > 0f) return@filter false
            val inside = s.points.count { box.contains(it.x, it.y) }
            inside >= MIN_COVERED * s.points.size && s.points.any { near(it, scratch, reach) }
        }
    }

    private fun near(p: InkPoint, path: List<InkPoint>, reach: Float): Boolean {
        for (i in 1 until path.size) if (segmentDistance(p, path[i - 1], path[i]) <= reach) return true
        return path.size == 1 && hypot(p.x - path[0].x, p.y - path[0].y) <= reach
    }

    private fun segmentDistance(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
