package io.trazo.core

import kotlin.math.abs

/** Actualiza patrones de ejemplo guardados con versiones anteriores sin perder lo que el usuario cambió. */
object PatternUpgrades {
    /**
     * Completa en los patrones de ejemplo ([InkPattern.builtIn]) los campos nuevos que falten: la categoría y,
     * si la salida sigue siendo la original, la barra elástica. Ejemplos de gesto, pausa, umbral y estado
     * activado se conservan tal cual; los patrones propios del usuario no se tocan.
     */
    fun upgrade(list: List<InkPattern>, defaults: List<InkPattern> = DefaultPatterns.all()): List<InkPattern> {
        val byId = defaults.associateBy { it.id }
        return list.map { p ->
            val d = byId[p.id]
            if (!p.builtIn || d == null) p
            else p.copy(
                category = p.category ?: d.category,
                stretch = p.stretch ?: d.stretch?.takeIf { sameInk(p.outputInk, d.outputInk) },
            )
        }
    }

    /** Misma geometría de tinta (ids, colores y grosores pueden variar). */
    fun sameInk(a: List<InkStroke>, b: List<InkStroke>, tol: Float = 0.01f): Boolean =
        a.size == b.size && a.zip(b).all { (sa, sb) ->
            sa.points.size == sb.points.size &&
                sa.points.zip(sb.points).all { (pa, pb) -> abs(pa.x - pb.x) <= tol && abs(pa.y - pb.y) <= tol }
        }
}
