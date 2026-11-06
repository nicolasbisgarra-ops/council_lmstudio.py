package io.trazo.core

/**
 * Halos: zonas translúcidas que aparecen junto a una integral o una raíz recién puesta para escribir
 * sus extremos o su radicando. Lo que se escribe en el halo de un extremo se achica y se acomoda junto a
 * la integral; lo que se escribe en el de la raíz alarga la barra hasta cubrirlo.
 */
data class Halo(val kind: Kind, val groupId: String, val zone: Bounds) {
    enum class Kind { UPPER_LIMIT, LOWER_LIMIT, RADICAND }
}

object Halos {

    /** Halos de la integral o raíz con [groupId] en [strokes]; vacío si ya no está en la hoja. */
    fun of(strokes: List<InkStroke>, groupId: String): List<Halo> {
        val group = strokes.filter { it.groupId == groupId }
        if (group.isEmpty()) return emptyList()
        val b = group.bounds()
        val w = b.width
        val h = b.height.coerceAtLeast(1f)
        return when (group.first().patternId) {
            DefaultPatterns.INTEGRAL -> listOf(
                Halo(Halo.Kind.UPPER_LIMIT, groupId, Bounds(b.right - 0.15f * w, b.top - 0.55f * h, b.right + 0.9f * h, b.top + 0.2f * h)),
                Halo(Halo.Kind.LOWER_LIMIT, groupId, Bounds(b.left + 0.3f * w, b.bottom - 0.2f * h, b.right + 0.9f * h, b.bottom + 0.55f * h)),
            )
            DefaultPatterns.ROOT -> {
                val bar = RootFit.barOf(group)?.bounds ?: return emptyList()
                listOf(Halo(Halo.Kind.RADICAND, groupId, Bounds(bar.left, bar.bottom, bar.right + 0.6f * h, b.bottom)))
            }
            else -> emptyList()
        }
    }

    /**
     * Mueve y achica [written] (escrito en el halo [kind]) para ponerlo como extremo de la integral [integral].
     * Varios bloques del mismo extremo se ponen uno a la derecha del otro a partir de [after] (borde derecho
     * del bloque anterior, o null).
     */
    fun placeLimit(kind: Halo.Kind, integral: Bounds, written: List<InkStroke>, after: Float? = null): List<InkStroke> {
        if (written.isEmpty()) return written
        val p = written.bounds()
        val h = integral.height.coerceAtLeast(1f)
        val k = minOf(1f, 0.3f * h / p.height.coerceAtLeast(1f), 1.2f * h / p.width.coerceAtLeast(1f))
        val ph = p.height * k
        val left = after?.let { it + 0.04f * h } ?: when (kind) {
            Halo.Kind.UPPER_LIMIT -> integral.right + 0.02f * h
            else -> integral.left + 0.5f * integral.width
        }
        val top = when (kind) {
            Halo.Kind.UPPER_LIMIT -> integral.top + 0.28f * h - ph
            else -> integral.bottom - 0.22f * h
        }
        val t = Affine.translate(-p.left, -p.top)
            .then(Affine(a = k, d = k))
            .then(Affine.translate(left, top))
        return written.map { it.transformed(t, scaleWidth = false) }
    }

    /** La barra de la raíz alargada hasta [right] (escala sus x desde su borde izquierdo), o null si ya llega. */
    fun extendBar(bar: InkStroke, right: Float): InkStroke? {
        val b = bar.bounds
        if (right <= b.right || b.width < 0.5f) return null
        val k = (right - b.left) / b.width
        return bar.copy(points = bar.points.map { it.copy(x = b.left + (it.x - b.left) * k) })
    }
}
