package io.trazo.core

import io.trazo.core.InkReplacement.adopting
import kotlin.math.max
import kotlin.math.min

/**
 * Raíz que sigue el trazo de la mano: el ✓ conserva la forma, el tamaño y el lugar que dibujó el usuario
 * (solo se suaviza), y la barra sale recta desde la punta del ✓ hasta donde terminó el gesto. Así la raíz no
 * se agranda ni tapa los números que tiene al lado, como pasaba al escalar la plantilla.
 */
object RootFit {

    /**
     * @param gesture trazos del gesto en orden.
     * @param dy corrimiento vertical del alineado automático (0 = donde se dibujó).
     * @return trazos de salida (✓ y barra) o null si el gesto no tiene forma de raíz (se usa la plantilla).
     */
    fun fit(
        pattern: InkPattern,
        gesture: List<InkStroke>,
        ids: IdGenerator,
        startTime: Long,
        style: InkStyle?,
        dy: Float = 0f,
    ): List<InkStroke>? {
        if (pattern.outputInk.size < 2 || gesture.isEmpty()) return null
        val pts = gesture.flatMap { it.points }
        if (pts.size < 4) return null
        val b = Bounds.of(pts)
        val h = b.height
        if (h < 1f || b.width < 1f) return null
        // Punta del ✓: el primer punto que llega arriba después de haber bajado.
        val top = b.top
        var wentDown = false
        var peak = -1
        for (i in pts.indices) {
            val y = pts[i].y
            if (y > top + 0.4f * h) wentDown = true
            if (wentDown && y <= top + 0.12f * h) { peak = i; break }
        }
        if (peak < 2) return null
        val check = smooth(pts.subList(0, peak + 1)).map { it.copy(y = it.y + dy) }
        val tip = check.last()
        val end = max(b.right, tip.x + 0.3f * h)
        val bar = (0..BAR_POINTS).map { k ->
            val u = k / BAR_POINTS.toFloat()
            InkPoint(tip.x + (end - tip.x) * u, tip.y, pressure = taper(u), timeMs = tip.timeMs + k * 8L)
        }
        val checkTpl = pattern.outputInk.first()
        val barTpl = pattern.outputInk.last()
        val group = ids.next()
        return listOf(checkTpl.copy(points = check), barTpl.copy(points = bar)).map { s ->
            s.adopting(style).copy(id = ids.next(), startTime = startTime, groupId = group, patternId = pattern.id)
        }
    }

    /** Media móvil de 3 puntos (los extremos quedan fijos); afina la presión en las puntas. */
    private fun smooth(src: List<InkPoint>): List<InkPoint> {
        val n = src.size
        return src.mapIndexed { i, p ->
            val u = if (n > 1) i / (n - 1f) else 0f
            val pr = min(p.pressure.coerceIn(0.2f, 1f), taper(u))
            if (i == 0 || i == n - 1) p.copy(pressure = pr)
            else p.copy(
                x = (src[i - 1].x + p.x + src[i + 1].x) / 3f,
                y = (src[i - 1].y + p.y + src[i + 1].y) / 3f,
                pressure = pr,
            )
        }
    }

    private fun taper(u: Float): Float = (0.45f + 0.55f * min(1f, min(u, 1f - u) * 6f)).coerceIn(0.3f, 1f)

    /** El trazo más plano y más a la derecha de una raíz: su barra. */
    fun barOf(group: List<InkStroke>): InkStroke? =
        group.filter { val b = it.bounds; b.height < 0.2f * b.width.coerceAtLeast(1f) }.maxByOrNull { it.bounds.right }

    private const val BAR_POINTS = 12
}
