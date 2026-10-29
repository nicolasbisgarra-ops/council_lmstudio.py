package io.trazo.core

import kotlin.math.abs
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
 * - Los signos de operación (+, −, ×, ÷, =, <…) tampoco: no marcan la altura del renglón. Ellos se
 *   centran en la línea de las letras y números más cercanos, sin cambiar de tamaño.
 * - Las variables (x, y, letras griegas…) nunca cambian de tamaño: solo bajan o suben hasta el piso
 *   de la forma de al lado. Un número toma la altura de otro número, no la de una letra.
 * - Lo escrito claramente más arriba o más abajo que la referencia (más de [OFFSET] de su alto: un
 *   exponente, un numerador, un denominador) queda donde se escribió. Una raya con algo justo encima
 *   (la barra de una fracción) tampoco se mueve.
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

    /** Desplazamiento vertical (en altos de la referencia) a partir del cual la forma se escribió a propósito más arriba o abajo. */
    const val OFFSET = 0.5f

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

        if (gesture.height < gesture.width * 0.4f && items.any { stacked(it.bounds, gesture) }) return gesture
        val sign = isSign(pattern)
        val variable = isVariable(pattern)
        val refs = if (isContainer(pattern)) row else row.filter { r -> lookup(r.patternId)?.let { !isContainer(it) && !isSign(it) } ?: true }
            .let { all -> if (variable) all else all.filter { r -> lookup(r.patternId)?.let(::isVariable) != true }.ifEmpty { all } }
        // Un signo sin letras ni números al lado se centra en otro signo; lo demás nunca toma su altura.
        val ref = (refs.takeIf { it.isNotEmpty() || !sign } ?: row.filter { r -> lookup(r.patternId)?.let(::isSign) == true })
            .minByOrNull { horizontalGap(it.bounds, gesture) } ?: return gesture
        val predicted = gesture.height * scale
        val refPattern = lookup(ref.patternId)
        val byFloor = variable || refPattern?.let(::isVariable) == true
        // Entre una letra y un número de distinto alto, el desvío se mide con el más alto de los dos.
        val reach = if (byFloor) max(ref.bounds.height, predicted) else ref.bounds.height
        if (ref in refs && abs(ref.bounds.centerY - gesture.centerY) > OFFSET * reach) return gesture
        if (ref in refs && byFloor) {
            // La salida se dibuja centrada en el gesto y mide predicted de alto: su piso queda a floor(pattern) de su arriba.
            val refFloor = ref.bounds.top + ref.bounds.height * (refPattern?.let(::floor) ?: 1f)
            val dy = refFloor - (gesture.centerY - predicted / 2f + predicted * floor(pattern))
            return Bounds(gesture.left, gesture.top + dy, gesture.right, gesture.bottom + dy)
        }
        val ratio = predicted / ref.bounds.height.coerceAtLeast(0.5f)
        return if (!sign && ratio in (1f / SIMILAR_RATIO)..SIMILAR_RATIO) {
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

    /**
     * Signo de operación que va centrado en la línea (+, −, ×, ÷, =, <, ≈…): no marca la altura del
     * renglón. Los de la biblioteca por su sección; los propios del usuario por su nombre.
     */
    fun isSign(p: InkPattern): Boolean {
        if (p.stretch != null) return false
        val key = p.id.removePrefix(PresetPatterns.PREFIX)
        if (p.builtIn && p.id.startsWith(PresetPatterns.PREFIX)) return p.category == PatternCategory.OPERATORS && key !in TALL_OPERATORS
        return isSignName(p.name)
    }

    /**
     * Variable: una letra que se usa como incógnita (x, y, a…) o una letra de la biblioteca (griegas,
     * letras y funciones). No cambia de tamaño al alinearse.
     */
    fun isVariable(p: InkPattern): Boolean {
        if (p.stretch != null) return false
        val u = p.unknown?.trim()
        if (u != null) return u.isNotEmpty()
        if (p.category == PatternCategory.GREEK || p.category == PatternCategory.LETTERS) return true
        return letterOf(p) != null
    }

    private fun letterOf(p: InkPattern): Char? =
        (p.unknown?.trim()?.takeIf { it.isNotEmpty() } ?: MathNotes.label(p.name)).singleOrNull()?.takeIf { it.isLetter() && it != 'π' }

    /**
     * Dónde está el piso (la línea de escritura) de la salida de [p], en fracción de su alto desde arriba.
     * Los patrones de la biblioteca se dibujan con la línea en y = 100; los del usuario apoyan su borde de
     * abajo, salvo las minúsculas con cola (g, j, p, q, y…), que bajan [DESCENDER] de su alto.
     */
    fun floor(p: InkPattern): Float {
        if (p.builtIn && p.id.startsWith(PresetPatterns.PREFIX)) {
            val src = p.outputInk.bounds()
            // Si el usuario la redibujó, ya no está en el marco de la biblioteca.
            if (src.height > 0.5f && src.top >= -10f && src.bottom in 95f..150f) {
                return ((PresetPatterns.BASELINE - src.top) / src.height).coerceIn(0.3f, 1f)
            }
        }
        return if (letterOf(p)?.let { it in DESCENDERS } == true) 1f - DESCENDER else 1f
    }

    /** Parte del alto de una minúscula con cola que queda debajo de la línea. */
    const val DESCENDER = 0.35f
    private const val DESCENDERS = "gjpqyβγζημξρφχψ"

    /** Un nombre de signo centrado («Más», «+», «igual»…), sin importar tildes ni mayúsculas. */
    fun isSignName(name: String): Boolean = fold(name) in SIGN_NAMES

    /** Operadores de la biblioteca con alto de letra: sí sirven de referencia. */
    private val TALL_OPERATORS = setOf("porcentaje", "factorial", "barra")

    private val SIGN_NAMES = listOf(
        "Más", "Menos", "Por", "Multiplicación", "Dividido", "División", "Igual", "Más o menos", "Distinto",
        "Aproximado", "Equivalente", "Menor", "Mayor", "Menor o igual", "Mayor o igual",
        "+", "-", "−", "×", "·", "*", "÷", "=", "±", "≠", "≈", "≡", "<", ">", "≤", "≥",
    ).map(::fold).toSet()

    private fun fold(name: String) = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

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

    /** [item] está justo encima de la raya [bar], dentro de su ancho: es el numerador de una fracción. */
    private fun stacked(item: Bounds, bar: Bounds): Boolean =
        item.centerX in bar.left..bar.right && item.bottom <= bar.centerY && bar.top - item.bottom <= item.height * 0.8f

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
