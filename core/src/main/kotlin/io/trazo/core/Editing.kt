package io.trazo.core

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** Borrador parcial: recorta tramos de tinta dentro de un círculo. */
object Eraser {
    /**
     * @return null si el trazo no se toca; si se toca, las piezas que sobreviven (puede ser vacía).
     */
    fun erase(stroke: InkStroke, cx: Float, cy: Float, radius: Float, ids: IdGenerator): List<InkStroke>? {
        val r = radius + stroke.width / 2f
        if (stroke.bounds.inflate(r).let { !it.contains(cx, cy) }) return null
        val dense = densify(stroke.points, max(radius / 3f, 0.5f))
        if (dense.none { hypot(it.x - cx, it.y - cy) <= r }) return null
        val pieces = mutableListOf<List<InkPoint>>()
        var run = mutableListOf<InkPoint>()
        for (p in dense) {
            if (hypot(p.x - cx, p.y - cy) <= r) {
                if (run.size >= 2) pieces += run
                run = mutableListOf()
            } else {
                run += p
            }
        }
        if (run.size >= 2) pieces += run
        return pieces.mapIndexed { i, pts ->
            val t0 = pts.first().timeMs
            stroke.copy(
                id = if (i == 0 && pieces.size == 1 && pts.size == dense.size) stroke.id else ids.next(),
                points = pts.map { it.copy(timeMs = it.timeMs - t0) },
                startTime = stroke.startTime + t0,
            )
        }
    }

    /** ¿El trazo pasa a menos de [radius] del punto? (borrador de trazo completo). */
    fun hits(stroke: InkStroke, cx: Float, cy: Float, radius: Float): Boolean {
        val r = radius + stroke.width / 2f
        if (!stroke.bounds.inflate(r).contains(cx, cy)) return false
        val pts = stroke.points
        if (pts.size == 1) return hypot(pts[0].x - cx, pts[0].y - cy) <= r
        for (i in 1 until pts.size) if (segmentDistance(cx, cy, pts[i - 1], pts[i]) <= r) return true
        return false
    }

    /** Subdivide segmentos largos para que el recorte sea preciso. Interpola presión y tiempo. */
    fun densify(points: List<InkPoint>, maxStep: Float): List<InkPoint> {
        if (points.size < 2) return points
        val out = ArrayList<InkPoint>(points.size * 2)
        out += points[0]
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val n = ceil(dist(a, b) / maxStep).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toFloat() / n
                out += InkPoint(
                    a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                    a.pressure + (b.pressure - a.pressure) * t,
                    a.timeMs + ((b.timeMs - a.timeMs) * t).toLong(),
                )
            }
        }
        return out
    }

    private fun segmentDistance(px: Float, py: Float, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9f) return hypot(px - a.x, py - a.y)
        val t = (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }
}

/** Selección con lazo. */
object Lasso {
    /** Trazos con al menos [minFraction] de sus puntos dentro del polígono. */
    fun select(strokes: List<InkStroke>, polygon: List<InkPoint>, minFraction: Float = 0.5f): List<InkStroke> {
        if (polygon.size < 3) return emptyList()
        val pb = Bounds.of(polygon)
        return strokes.filter { s ->
            if (pb.gapTo(s.bounds) > 0f) return@filter false
            val inside = s.points.count { contains(polygon, it.x, it.y) }
            inside >= max(1f, s.points.size * minFraction)
        }
    }

    /** Regla par-impar (ray casting). */
    fun contains(polygon: List<InkPoint>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x) inside = !inside
            j = i
        }
        return inside
    }
}
