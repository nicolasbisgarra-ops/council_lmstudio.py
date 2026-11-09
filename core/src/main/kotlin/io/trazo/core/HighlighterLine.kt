package io.trazo.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Un resaltado que se hizo «casi recto» queda recto: se reemplaza por el segmento entre sus extremos
 * (horizontal si el ángulo es de pocos grados). Uno ondulado o en zigzag queda como se dibujó.
 */
object HighlighterLine {
    private const val MAX_DEVIATION = 0.06f
    private const val MAX_DETOUR = 1.15f
    private const val SNAP_DEGREES = 4.0

    fun straighten(stroke: InkStroke): InkStroke {
        val pts = stroke.points
        if (pts.size < 3) return stroke
        val a = pts.first()
        val b = pts.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val chord = hypot(dx, dy)
        if (chord < 2f * stroke.width) return stroke
        if (stroke.length > MAX_DETOUR * chord) return stroke
        val limit = maxOf(MAX_DEVIATION * chord, 0.35f * stroke.width)
        val far = pts.maxOf { abs((it.x - a.x) * dy - (it.y - a.y) * dx) / chord }
        if (far > limit) return stroke
        val pressure = pts.map { it.pressure }.average().toFloat()
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val horizontal = abs(degrees) <= SNAP_DEGREES || abs(degrees) >= 180 - SNAP_DEGREES
        val y = (a.y + b.y) / 2f
        val start = if (horizontal) InkPoint(a.x, y, pressure, a.timeMs) else a.copy(pressure = pressure)
        val end = if (horizontal) InkPoint(b.x, y, pressure, b.timeMs) else b.copy(pressure = pressure)
        return stroke.copy(points = listOf(start, end))
    }
}
