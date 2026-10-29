package io.trazo.core

import kotlin.math.max
import kotlin.math.min

/**
 * Auto-alineado: al convertir un gesto, ajusta el rectángulo donde va la salida para que quede en
 * el mismo renglón que las formas ya reconocidas.
 *
 * - Un cierre de paréntesis, corchete o llave toma la altura y la posición vertical de su apertura
 *   (la última sin cerrar, a su izquierda, en el mismo renglón).
 * - Cualquier otra forma se iguala con la forma reconocida más cercana del renglón: si su tamaño es
 *   parecido (entre la mitad y el doble) toma su misma altura; si no (=, −, ∫…), solo se centra en
 *   su misma línea, sin cambiar de tamaño.
 * - Los "contenedores" (paréntesis, integrales, raíz, fracción, sumatoria) no sirven de referencia
 *   para las demás formas: un número dentro de una matriz no se centra en el corchete.
 *
 * Solo mira la tinta que salió de un patrón; la escritura a mano no cuenta.
 */
object AutoAlign {
    /** Una forma ya reconocida en la página: los trazos de un mismo grupo. */
    data class Item(val patternId: String, val bounds: Bounds)

    /** Parecido mínimo de tamaño (alto) para igualar la altura en vez de solo centrar. */
    const val SIMILAR_RATIO = 2f

    /** Fracción del menor alto que deben solaparse en vertical dos formas para estar en el mismo renglón. */
    const val ROW_OVERLAP = 0.3f

    private val PAIRS = mapOf(
        "parentesis-abre" to "parentesis-cierra",
        "corchete-abre" to "corchete-cierra",
        "llave-abre" to "llave-cierra",
    )
    /** Patrones propios del usuario que se llaman como el signo. */
    private val NAMES = mapOf(
        "(" to "parentesis-abre", ")" to "parentesis-cierra",
        "[" to "corchete-abre", "]" to "corchete-cierra",
        "{" to "llave-abre", "}" to "llave-cierra",
    )

    /** Formas reconocidas de [strokes] (agrupadas por grupo), sin los trazos de [exclude]. */
    fun items(strokes: List<InkStroke>, exclude: Set<String> = emptySet()): List<Item> =
        strokes.filter { it.patternId != null && it.id !in exclude }
            .groupBy { it.groupId ?: it.id }
            .map { (_, g) -> Item(g.first().patternId!!, g.bounds()) }

    /**
     * Rectángulo ajustado para la salida de [pattern] escrita en [gesture].
     * Devuelve [gesture] sin cambios si no hay con qué alinear.
     */
    fun anchor(
        pattern: InkPattern,
        gesture: Bounds,
        items: List<Item>,
        lookup: (String) -> InkPattern?,
    ): Bounds {
        if (pattern.stretch != null || gesture.height <= 0.5f || items.isEmpty()) return gesture
        val row = items.filter { sameRow(it.bounds, gesture) }
        if (row.isEmpty()) return gesture
        val scale = pattern.outputScale.coerceAtLeast(0.01f)

        openerFor(pattern, gesture, row, lookup)?.let { open ->
            return resized(gesture, open.bounds, scale)
        }

        val refs = if (isContainer(pattern)) row else row.filter { r -> lookup(r.patternId)?.let { !isContainer(it) } ?: true }
        val ref = refs.minByOrNull { horizontalGap(it.bounds, gesture) } ?: return gesture
        val predicted = gesture.height * scale
        val ratio = predicted / ref.bounds.height.coerceAtLeast(0.5f)
        return if (ratio in (1f / SIMILAR_RATIO)..SIMILAR_RATIO) {
            resized(gesture, ref.bounds, scale)
        } else {
            val dy = ref.bounds.centerY - gesture.centerY
            Bounds(gesture.left, gesture.top + dy, gesture.right, gesture.bottom + dy)
        }
    }

    /** Apertura que cierra [pattern] si es un cierre: la última sin pareja a la izquierda del gesto. */
    private fun openerFor(pattern: InkPattern, gesture: Bounds, row: List<Item>, lookup: (String) -> InkPattern?): Item? {
        val close = kindOf(pattern) ?: return null
        val open = PAIRS.entries.firstOrNull { it.value == close }?.key ?: return null
        val stack = ArrayDeque<Item>()
        for (it in row.filter { it.bounds.centerX < gesture.centerX }.sortedBy { it.bounds.centerX }) {
            when (lookup(it.patternId)?.let(::kindOf)) {
                open -> stack.addLast(it)
                close -> stack.removeLastOrNull()
            }
        }
        return stack.lastOrNull()
    }

    /** "parentesis-abre", "corchete-cierra"… o null si no es un signo de agrupación con pareja. */
    private fun kindOf(p: InkPattern): String? {
        val key = p.id.removePrefix(PresetPatterns.PREFIX)
        if (key in PAIRS.keys || key in PAIRS.values) return key
        return NAMES[p.name.trim()]
    }

    /** Formas que abarcan a otras: no sirven de referencia de tamaño para una forma suelta. */
    fun isContainer(p: InkPattern): Boolean {
        if (p.category == PatternCategory.GROUPING || p.stretch != null) return true
        val key = p.id.removePrefix(PresetPatterns.PREFIX)
        return key == DefaultPatterns.INTEGRAL || key == DefaultPatterns.ROOT || key == DefaultPatterns.FRACTION ||
            key.startsWith("integral") || key == "sumatoria" || key == "productoria" || kindOf(p) != null
    }

    private fun sameRow(a: Bounds, b: Bounds): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap >= ROW_OVERLAP * min(a.height, b.height).coerceAtLeast(1f)
    }

    private fun horizontalGap(a: Bounds, b: Bounds): Float = max(0f, max(a.left - b.right, b.left - a.right))

    /**
     * El gesto llevado a la altura y la línea de [ref]: como la salida mide su alto × [scale], el
     * anclaje mide ref/scale. Mismo centro horizontal; el ancho crece en proporción para los patrones
     * que miden por ancho.
     */
    private fun resized(gesture: Bounds, ref: Bounds, scale: Float): Bounds {
        val h = (ref.height / scale).coerceAtLeast(0.5f)
        val w = gesture.width * h / gesture.height
        return Bounds(gesture.centerX - w / 2f, ref.centerY - h / 2f, gesture.centerX + w / 2f, ref.centerY + h / 2f)
    }
}
