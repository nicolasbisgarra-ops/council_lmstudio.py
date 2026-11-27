package io.trazo.core.settings

import io.trazo.core.Match

/**
 * Cómo se muestran las dos opciones de un trazo ambiguo, sin tipos de Android.
 *
 * - Lugar fijo: la primera vez que dos patrones aparecen juntos se recuerda cuál quedó a la izquierda, y
 *   desde entonces esa pareja sale siempre en el mismo orden (por ejemplo división a la izquierda, menos a
 *   la derecha), aunque el reconocedor las puntúe al revés.
 * - Color propio: cada opción tiene un color distinto para diferenciarlas de un vistazo cuando el dibujo es
 *   igual (división y menos son la misma raya). Si el patrón tiene color fijo ([SymbolColors]) se usa ese.
 *
 * Formato de texto del orden: `a|b=a;c|d=d` (pareja ordenada alfabéticamente = id de la izquierda).
 */
object AmbiguityLayout {
    /** Parejas recordadas como máximo; al pasarse se olvidan las más viejas. */
    const val MAX_PAIRS = 200

    /** Colores para opciones sin color fijo: legibles en papel claro y oscuro, lejos del verde y el rojo. */
    val PALETTE: List<Int> = listOf(
        0xFF1E63C6.toInt(), // azul
        0xFFE07A00.toInt(), // naranja
        0xFF7B3FC4.toInt(), // violeta
        0xFF00897B.toInt(), // verde azulado
        0xFFC2185B.toInt(), // fucsia
        0xFF6D4C41.toInt(), // marrón
    )

    fun key(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"

    /**
     * Las opciones en el orden recordado y el orden actualizado (la misma instancia si no cambió).
     * Solo se ordenan las dos primeras, que son las que se muestran.
     */
    fun arrange(order: Map<String, String>, options: List<Match>): Pair<List<Match>, Map<String, String>> {
        if (options.size < 2) return options to order
        val a = options[0]
        val b = options[1]
        if (a.pattern.id == b.pattern.id || !valid(a.pattern.id) || !valid(b.pattern.id)) return options to order
        val k = key(a.pattern.id, b.pattern.id)
        val left = order[k]
        return when (left) {
            a.pattern.id -> options to order
            b.pattern.id -> (listOf(b, a) + options.drop(2)) to order
            else -> {
                val next = LinkedHashMap(order)
                next.remove(k)
                next[k] = a.pattern.id
                while (next.size > MAX_PAIRS) next.remove(next.keys.first())
                options to next
            }
        }
    }

    /**
     * Un color por opción, todos distintos entre sí: el fijo del patrón si tiene ([fixed]); si no, uno de
     * [PALETTE] elegido por el id (siempre el mismo para el mismo patrón) que no repita uno ya usado.
     */
    fun colors(ids: List<String>, fixed: (String) -> Int?): List<Int> {
        val used = mutableListOf<Int>()
        // Primero los fijos, para que la paleta los esquive.
        val chosen = arrayOfNulls<Int>(ids.size)
        ids.forEachIndexed { i, id ->
            val c = fixed(id)
            if (c != null && c !in used) { chosen[i] = c; used += c }
        }
        ids.forEachIndexed { i, id ->
            if (chosen[i] != null) return@forEachIndexed
            val start = Math.floorMod(stableHash(id), PALETTE.size)
            val c = (PALETTE.indices).map { PALETTE[(start + it) % PALETTE.size] }.firstOrNull { it !in used } ?: PALETTE[start]
            chosen[i] = c
            used += c
        }
        return chosen.map { it!! }
    }

    fun read(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq)
            val v = part.substring(eq + 1)
            val ids = k.split('|')
            if (ids.size != 2 || v !in ids || ids.any { !valid(it) } || key(ids[0], ids[1]) != k) continue
            out[k] = v
        }
        return out
    }

    fun serialize(order: Map<String, String>): String =
        order.entries.joinToString(";") { (k, v) -> "$k=$v" }

    private fun valid(id: String) = id.isNotEmpty() && id.none { it == '|' || it == '=' || it == ';' }

    /** Hash que no cambia entre versiones ni dispositivos (no depende de String.hashCode). */
    private fun stableHash(id: String): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in id) { h = h xor ch.code; h *= 0x01000193 }
        return h
    }
}
