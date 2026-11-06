package io.trazo.core

/**
 * Halos: zonas translúcidas que aparecen junto a una integral, una raíz o una fracción recién puesta para
 * escribir sus extremos, su radicando o su numerador y denominador. Lo que se escribe en el halo de un extremo
 * se achica y se acomoda junto a la integral; lo de la fracción se centra arriba o abajo de la barra (y alarga
 * la barra si es más ancho); lo de la raíz alarga la barra hasta cubrirlo.
 */
data class Halo(val kind: Kind, val groupId: String, val zone: Bounds) {
    enum class Kind { UPPER_LIMIT, LOWER_LIMIT, RADICAND, NUMERATOR, DENOMINATOR }
}

object Halos {

    /** Halos de la integral, raíz o fracción con [groupId] en [strokes]; vacío si ya no está en la hoja. */
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
            DefaultPatterns.FRACTION -> {
                val zh = (0.6f * w).coerceAtLeast(36f)
                val pad = (0.1f * w).coerceAtLeast(6f)
                listOf(
                    Halo(Halo.Kind.NUMERATOR, groupId, Bounds(b.left - pad, b.top - zh, b.right + pad, b.top)),
                    Halo(Halo.Kind.DENOMINATOR, groupId, Bounds(b.left - pad, b.bottom, b.right + pad, b.bottom + zh)),
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Mueve y achica [written] (escrito en el halo [kind]) para ponerlo como extremo de la integral [integral],
     * o lo centra sin achicar sobre o bajo la barra de una fracción.
     * Varios bloques del mismo extremo se ponen uno a la derecha del otro a partir de [after] (borde derecho
     * del bloque anterior, o null).
     */
    fun placeLimit(kind: Halo.Kind, integral: Bounds, written: List<InkStroke>, after: Float? = null): List<InkStroke> {
        if (written.isEmpty()) return written
        val p = written.bounds()
        if (kind == Halo.Kind.NUMERATOR || kind == Halo.Kind.DENOMINATOR) {
            val h = p.height.coerceAtLeast(1f)
            val gap = (0.15f * h).coerceAtLeast(2f)
            val left = after?.let { it + (0.1f * h).coerceAtLeast(4f) } ?: (integral.centerX - p.width / 2f)
            val top = when (kind) {
                Halo.Kind.NUMERATOR -> integral.top - gap - p.height
                else -> integral.bottom + gap
            }
            val t = Affine.translate(left - p.left, top - p.top)
            return written.map { it.transformed(t, scaleWidth = false) }
        }
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

    /** La barra alargada para cubrir de [left] a [right] (por defecto desde su borde izquierdo), o null si ya llega. */
    fun extendBar(bar: InkStroke, right: Float, left: Float = bar.bounds.left): InkStroke? {
        val b = bar.bounds
        val newLeft = minOf(left, b.left)
        val newRight = maxOf(right, b.right)
        if ((newLeft >= b.left && newRight <= b.right) || b.width < 0.5f) return null
        val k = (newRight - newLeft) / b.width
        return bar.copy(points = bar.points.map { it.copy(x = newLeft + (it.x - b.left) * k) })
    }
}
