package io.trazo.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-alineado: al convertir un gesto, ajusta el rectángulo donde va la salida para que quede en
 * el mismo renglón que las formas ya reconocidas.
 *
 * - Un cierre de paréntesis, corchete o llave toma la altura y la posición vertical de su apertura:
 *   la última sin cerrar de su misma expresión (formas encadenadas a menos de [MAX_GAP] altos; la
 *   escritura a mano del renglón también encadena), en su línea (a menos de [BRACKET_OFFSET] del alto)
 *   y no más de [MAX_RESIZE] veces más alta o más baja que él.
 * - Una apertura (o un cierre sin pareja) se centra en lo que tiene al lado (no encima ni debajo: a lo
 *   sumo [BESIDE_OVERLAP] de solape), en su línea y a menos de [MAX_GAP] altos; junto a una fracción,
 *   en su barra; junto a una integral, en su medio. Solo toma la altura de otro signo de agrupación
 *   parecido.
 * - Un número o una letra toma la escala (el alto sobre la línea) de la letra o el número más cercano
 *   del renglón y se apoya en su piso; solo si está a menos de [MAX_GAP] altos, así no toma el de otra
 *   cuenta del otro lado de la hoja. Un número prefiere otro número a su alcance. Las integrales,
 *   raíces y sumatorias solo se centran en su línea.
 * - Los "contenedores" (paréntesis, integrales, raíz, fracción, sumatoria) no sirven de referencia
 *   para las demás formas: un número dentro de una matriz no se centra en el corchete.
 * - Los signos de operación (+, −, ×, ÷, =, <…) tampoco: no marcan la altura del renglón. Ellos se
 *   centran en la línea de las letras y números más cercanos, sin cambiar de tamaño.
 * - Lo escrito claramente más arriba o más abajo que la referencia (más de [OFFSET] de su alto: un
 *   exponente, un numerador, un denominador) queda donde se escribió. Una raya con algo justo encima
 *   (la barra de una fracción) tampoco se mueve.
 * - Un exponente o subíndice (más chico que la forma de al lado y corrido hacia arriba o abajo de su
 *   renglón) queda donde se escribió, y uno ya escrito no sirve de referencia: lo que sigue a x² se
 *   alinea con la x, no con el 2. Un signo arriba del renglón (el − de x⁻¹) tampoco baja.
 * - Una forma lejos de todas (más de [MAX_GAP] altos de separación) no se alinea con nada.
 *
 * Solo mira la tinta que salió de un patrón; la escritura a mano nunca es referencia, solo une la
 * expresión de un paréntesis ([bridges]).
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

    /** Desvío vertical máximo (en altos del mayor de los dos) para que un paréntesis se centre en algo o copie su apertura. */
    const val BRACKET_OFFSET = 0.35f

    /** Un cierre no copia la altura de una apertura más de [MAX_RESIZE] veces más alta o más baja que él. */
    const val MAX_RESIZE = 3f

    /** Solape horizontal máximo (fracción del más angosto) con la referencia: lo que está encima o debajo no cuenta. */
    const val BESIDE_OVERLAP = 0.5f

    private val PAIRS = mapOf(
        "parentesis-abre" to "parentesis-cierra",
        "corchete-abre" to "corchete-cierra",
        "llave-abre" to "llave-cierra",
    )

    /**
     * Patrones propios del usuario que se llaman como el signo («(», «Abre paréntesis», «Corchete
     * cierra»…). Las claves van ya pasadas por [fold]: sin tildes ni mayúsculas.
     */
    private val NAMES: Map<String, String> = buildMap {
        put("(", "parentesis-abre"); put(")", "parentesis-cierra")
        put("[", "corchete-abre"); put("]", "corchete-cierra")
        put("{", "llave-abre"); put("}", "llave-cierra")
        for (k in listOf("parentesis", "corchete", "llave")) {
            for (v in listOf("abre", "abrir")) { put("$v $k", "$k-abre"); put("$k $v", "$k-abre") }
            for (v in listOf("cierra", "cerrar")) { put("$v $k", "$k-cierra"); put("$k $v", "$k-cierra") }
        }
    }

    /** Formas reconocidas de [strokes] (agrupadas por grupo), sin los trazos de [exclude]. */
    fun items(strokes: List<InkStroke>, exclude: Set<String> = emptySet()): List<Item> =
        strokes.filter { it.patternId != null && it.id !in exclude }
            .groupBy { it.groupId ?: it.id }
            .map { (_, g) -> Item(g.first().patternId!!, g.bounds()) }

    /**
     * Trazos escritos a mano ([InkStroke.isHandwriting]) de [strokes], sin [exclude]: nunca son
     * referencia, solo unen la expresión de un paréntesis con su apertura.
     */
    fun bridges(strokes: List<InkStroke>, exclude: Set<String> = emptySet()): List<Bounds> =
        strokes.filter { it.isHandwriting && it.id !in exclude }.map { it.bounds }

    /**
     * Rectángulo ajustado para la salida de [pattern] escrita en [gesture].
     * Devuelve [gesture] sin cambios si no hay con qué alinear.
     */
    fun anchor(pattern: InkPattern, gesture: Bounds, items: List<Item>, lookup: (String) -> InkPattern?): Bounds =
        anchor(pattern, gesture, items, emptyList(), lookup)

    /**
     * Como el otro [anchor], con los trazos escritos a mano de la página ([bridges]): un cierre los usa
     * para encadenar su expresión hasta la apertura, pero nunca como referencia.
     */
    fun anchor(
        pattern: InkPattern,
        gesture: Bounds,
        items: List<Item>,
        bridges: List<Bounds>,
        lookup: (String) -> InkPattern?,
    ): Bounds {
        if (pattern.stretch != null || gesture.height <= 0.5f || items.isEmpty()) return gesture
        val row = items.filter { sameRow(it.bounds, gesture) }
        if (row.isEmpty()) return gesture
        val scale = pattern.outputScale.coerceAtLeast(0.01f)

        if (gesture.height < gesture.width * 0.4f && items.any { stacked(it.bounds, gesture) }) return gesture
        kindOf(pattern)?.let { kind ->
            return bracketAnchor(kind, gesture, items, row, bridges.filter { sameRow(it, gesture) }, lookup, scale)
        }
        val sign = isSign(pattern)
        val variable = isVariable(pattern)
        // Los exponentes y subíndices ya escritos no marcan el renglón: lo que sigue a x² se alinea con la x.
        val bases = row.filter { r -> lookup(r.patternId)?.let { !isContainer(it) && !isSign(it) } ?: true }
        val plain = row.filter { r -> bases.none { b -> b !== r && isScript(r.bounds, r.bounds.height, b.bounds) } }
        val predicted = gesture.height * scale
        fun near(r: Item) = horizontalGap(r.bounds, gesture) <= MAX_GAP * max(r.bounds.height, predicted)
        // Un número prefiere otro número a su alcance; si no hay, toma la letra o el número más cercano.
        val refs = if (isContainer(pattern)) plain else plain.filter { r -> lookup(r.patternId)?.let { !isContainer(it) && !isSign(it) } ?: true }
            .let { all -> if (variable) all else all.filter { r -> near(r) && lookup(r.patternId)?.let(::isVariable) != true }.ifEmpty { all } }
        // Un signo sin letras ni números al lado se centra en otro signo; lo demás nunca toma su altura.
        val ref = (refs.takeIf { it.isNotEmpty() || !sign } ?: plain.filter { r -> lookup(r.patternId)?.let(::isSign) == true })
            .minByOrNull { horizontalGap(it.bounds, gesture) } ?: return gesture
        // Lejos de todo (otra cuenta en la misma altura de la hoja): queda donde se escribió.
        if (horizontalGap(ref.bounds, gesture) > MAX_GAP * max(ref.bounds.height, predicted)) return gesture
        if (ref in refs) {
            val out = Bounds(gesture.left, gesture.centerY - predicted / 2f, gesture.right, gesture.centerY + predicted / 2f)
            if (sign) {
                // Un signo claramente arriba (x⁻¹) o debajo del renglón se escribió así a propósito.
                val h = ref.bounds.height
                if (out.centerY < ref.bounds.top + SIGN_RAISED * h || out.centerY > ref.bounds.bottom + SIGN_LOWERED * h) return gesture
            } else if (isScript(out, predicted, ref.bounds)) {
                return gesture
            }
        }
        val refPattern = lookup(ref.patternId)
        // Números y letras se apoyan en el piso de la forma de al lado y toman su escala.
        val byFloor = ref in refs && (pattern.floor != null || (!sign && !isContainer(pattern)))
        // Entre formas de distinto alto, el desvío se mide con la más alta de las dos.
        val reach = if (sign) ref.bounds.height else max(ref.bounds.height, predicted)
        if (ref in refs && abs(ref.bounds.centerY - gesture.centerY) > OFFSET * reach) return gesture
        if (byFloor) {
            // La salida se dibuja centrada en el anclaje y mide su alto × scale: su piso queda a floor(pattern) de su arriba.
            val refFrac = refPattern?.let(::floor) ?: 1f
            val refFloor = ref.bounds.top + ref.bounds.height * refFrac
            val f = floor(pattern)
            // Misma escala que la de al lado: igual cuerpo sobre la línea (las colas de g, y… no cuentan).
            val h = if (refPattern != null && isGlyph(refPattern) && isGlyph(pattern)) ref.bounds.height * refFrac / f else predicted
            val ah = h / scale
            val aw = gesture.width * ah / gesture.height
            val cy = refFloor - h * f + h / 2f
            return Bounds(gesture.centerX - aw / 2f, cy - ah / 2f, gesture.centerX + aw / 2f, cy + ah / 2f)
        }
        val dy = ref.bounds.centerY - gesture.centerY
        return Bounds(gesture.left, gesture.top + dy, gesture.right, gesture.bottom + dy)
    }

    /** Letra o número (no signo, contenedor, coma, %, ! ni barra): lo que iguala su escala con el renglón. */
    private fun isGlyph(p: InkPattern): Boolean {
        if (p.stretch != null || isSign(p) || isContainer(p)) return false
        val key = p.id.removePrefix(PresetPatterns.PREFIX)
        return !(p.builtIn && (key in TALL_OPERATORS || key in BASELINE_MARKS))
    }

    /** Paréntesis, corchetes y llaves (ver el KDoc del objeto). */
    private fun bracketAnchor(
        kind: String, gesture: Bounds, items: List<Item>, row: List<Item>, bridges: List<Bounds>,
        lookup: (String) -> InkPattern?, scale: Float,
    ): Bounds {
        val predicted = gesture.height * scale
        // 1. Un cierre copia su apertura (misma expresión, en su línea, tamaño a lo sumo MAX_RESIZE veces distinto).
        partnerFor(kind, gesture, row, bridges, predicted, lookup)?.let { return resized(gesture, it.bounds, scale) }
        // 2. Si no, se centra en lo que tiene al lado; los exponentes y subíndices no cuentan.
        val bases = row.filter { r -> lookup(r.patternId)?.let { !isContainer(it) && !isSign(it) } ?: true }
        val plain = row.filter { r -> bases.none { b -> b !== r && isScript(r.bounds, r.bounds.height, b.bounds) } }
        // Junto a una fracción, la referencia es su barra (si está en la línea del paréntesis).
        val bars = fractionBars(items, lookup)
        val ref = plain
            .map { r -> barOf(r, bars)?.takeIf { onLine(it.bounds, gesture, predicted) } ?: r }
            .distinct()
            .filter {
                onLine(it.bounds, gesture, predicted) && beside(it.bounds, gesture) &&
                    horizontalGap(it.bounds, gesture) <= MAX_GAP * max(it.bounds.height, predicted)
            }
            .minWithOrNull(compareBy<Item>({ horizontalGap(it.bounds, gesture) }, { abs(it.bounds.centerY - gesture.centerY) }))
            ?: return gesture
        // 3. Solo copia la altura de otro signo de agrupación parecido.
        if (lookup(ref.patternId)?.let(::kindOf) != null) {
            val ratio = predicted / ref.bounds.height.coerceAtLeast(0.5f)
            if (ratio in (1f / SIMILAR_RATIO)..SIMILAR_RATIO) return resized(gesture, ref.bounds, scale)
        }
        // Junto a un número, una letra, un signo o una integral se centra en su medio, sin cambiar de tamaño.
        val dy = ref.bounds.centerY - gesture.centerY
        return Bounds(gesture.left, gesture.top + dy, gesture.right, gesture.bottom + dy)
    }

    /** Apertura de [kind] si es un cierre: la última sin pareja de su expresión, en su línea y de tamaño parecido. */
    private fun partnerFor(
        kind: String, gesture: Bounds, row: List<Item>, bridges: List<Bounds>, predicted: Float,
        lookup: (String) -> InkPattern?,
    ): Item? {
        // Las aperturas no buscan pareja a su derecha.
        val open = PAIRS.entries.firstOrNull { it.value == kind }?.key ?: return null
        val stack = ArrayDeque<Item>()
        val left = sameExpression(gesture, row, bridges, predicted)
            .filter { it.bounds.centerX < gesture.centerX && onLine(it.bounds, gesture, predicted) }
            .sortedBy { it.bounds.centerX }
        for (it in left) {
            when (lookup(it.patternId)?.let(::kindOf)) {
                open -> stack.addLast(it)
                kind -> stack.removeLastOrNull()
            }
        }
        val top = stack.lastOrNull() ?: return null
        val ratio = predicted / top.bounds.height.coerceAtLeast(0.5f)
        return top.takeIf { ratio in (1f / MAX_RESIZE)..MAX_RESIZE }
    }

    /**
     * Lo del renglón encadenado con el gesto: cada forma a menos de [MAX_GAP] altos de lo ya juntado.
     * Los trazos a mano del renglón ([bridges]) alargan la cadena (x + y escritos sin convertir entre
     * los dos paréntesis), pero nunca se devuelven: no son referencia de nada.
     */
    private fun sameExpression(gesture: Bounds, row: List<Item>, bridges: List<Bounds>, predicted: Float): List<Item> {
        val rest = row.toMutableList()
        val links = bridges.toMutableList()
        val inside = ArrayList<Item>()
        var span = gesture
        while (true) {
            val next = rest.firstOrNull { horizontalGap(it.bounds, span) <= MAX_GAP * max(it.bounds.height, predicted) }
            if (next != null) {
                rest.remove(next); inside += next; span = span.union(next.bounds); continue
            }
            val link = links.firstOrNull { horizontalGap(it, span) <= MAX_GAP * max(it.height, predicted) } ?: break
            links.remove(link); span = span.union(link)
        }
        return inside
    }

    /** [b] está en la línea del paréntesis: su centro a menos de [BRACKET_OFFSET] del mayor de los dos altos. */
    private fun onLine(b: Bounds, gesture: Bounds, predicted: Float) =
        abs(b.centerY - gesture.centerY) <= BRACKET_OFFSET * max(b.height, predicted)

    /** [b] está al costado de [g], no encima ni debajo: se solapan en horizontal a lo sumo [BESIDE_OVERLAP] del más angosto. */
    private fun beside(b: Bounds, g: Bounds): Boolean {
        val overlap = min(b.right, g.right) - max(b.left, g.left)
        return overlap <= BESIDE_OVERLAP * min(b.width, g.width)
    }

    private fun above(i: Item, bar: Item): Boolean {
        val b = bar.bounds
        val s = b.width * 0.1f
        return i !== bar && i.bounds.centerX in (b.left - s)..(b.right + s) &&
            i.bounds.bottom <= b.centerY && b.top - i.bounds.bottom <= 0.8f * i.bounds.height
    }

    private fun below(i: Item, bar: Item): Boolean {
        val b = bar.bounds
        val s = b.width * 0.1f
        return i !== bar && i.bounds.centerX in (b.left - s)..(b.right + s) &&
            i.bounds.top >= b.centerY && i.bounds.top - b.bottom <= 0.8f * i.bounds.height
    }

    /** Barras de fracción: rayas con algo justo encima y justo debajo (también la de un resultado, de alto 0). */
    private fun fractionBars(items: List<Item>, lookup: (String) -> InkPattern?): List<Item> =
        items.filter { isBar(it, lookup) }.filter { bar -> items.any { above(it, bar) } && items.any { below(it, bar) } }

    /** La barra (la más ancha) de la fracción de la que [item] es numerador o denominador. */
    private fun barOf(item: Item, bars: List<Item>): Item? =
        bars.filter { above(item, it) || below(item, it) }.maxByOrNull { it.bounds.width }

    private val BAR_IDS = setOf(DefaultPatterns.FRACTION, PresetPatterns.PREFIX + "menos", PresetPatterns.PREFIX + "barra")

    private fun isBar(i: Item, lookup: (String) -> InkPattern?): Boolean {
        if (i.bounds.height >= 0.4f * i.bounds.width) return false
        if (i.patternId in BAR_IDS) return true
        val p = lookup(i.patternId) ?: return false
        return MathNotes.label(p.name) in MathNotes.BAR_LABELS
    }

    /** "parentesis-abre", "corchete-cierra"… o null si no es un signo de agrupación con pareja. */
    private fun kindOf(p: InkPattern): String? {
        val key = p.id.removePrefix(PresetPatterns.PREFIX)
        if (key in PAIRS.keys || key in PAIRS.values) return key
        return NAMES[fold(p.name)]
    }

    /**
     * Signo de operación que va centrado en la línea (+, −, ×, ÷, =, <, ≈…): no marca la altura del
     * renglón. Los de la biblioteca por su sección; los propios del usuario por su nombre.
     */
    fun isSign(p: InkPattern): Boolean {
        if (p.stretch != null) return false
        val key = p.id.removePrefix(PresetPatterns.PREFIX)
        if (p.builtIn && p.id.startsWith(PresetPatterns.PREFIX)) return p.category == PatternCategory.OPERATORS && key !in TALL_OPERATORS && key !in BASELINE_MARKS
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
        if (p.builtIn && p.id.removePrefix(PresetPatterns.PREFIX) in BASELINE_MARKS) return true
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
        p.floor?.let { return it.coerceIn(MIN_USER_FLOOR, 1f) }
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
    /** Piso más alto que puede elegir el usuario (fracción desde arriba). */
    const val MIN_USER_FLOOR = 0.05f
    private const val DESCENDERS = "gjpqyβγζημξρφχψ"

    /** Un nombre de signo centrado («Más», «+», «igual»…), sin importar tildes ni mayúsculas. */
    fun isSignName(name: String): Boolean = fold(name) in SIGN_NAMES

    /** Operadores de la biblioteca con alto de letra: sí sirven de referencia. */
    private val TALL_OPERATORS = setOf("porcentaje", "factorial", "barra")

    /** Signos que se apoyan en la línea como una letra (la coma cuelga de ella), no centrados como un «+». */
    private val BASELINE_MARKS = setOf("coma")

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

    /** Alto máximo (en altos de la base) de un exponente o subíndice. */
    const val SCRIPT_SIZE = 0.8f

    /** Separación horizontal máxima (en altos) con la forma de referencia: más lejos ya no es el mismo renglón. */
    const val MAX_GAP = 3f

    /** Un signo cuyo centro queda en la franja de arriba de la referencia (esta fracción de su alto) no se baja. */
    const val SIGN_RAISED = 0.25f

    /** Un signo cuyo centro queda debajo del piso de la referencia (más de esta fracción de su alto) no se sube. */
    const val SIGN_LOWERED = 0.1f

    /**
     * [a] (de alto [height]) es un exponente o un subíndice de [base]: claramente más chico y corrido hacia
     * arriba (no llega al piso del renglón) o hacia abajo (empieza debajo de la mitad y cuelga del piso).
     */
    private fun isScript(a: Bounds, height: Float, base: Bounds): Boolean {
        val h = base.height
        if (h <= 0.5f || height > SCRIPT_SIZE * h) return false
        val raised = a.bottom < base.bottom - 0.3f * h && a.centerY < base.centerY - 0.15f * h
        val lowered = a.top > base.centerY && a.bottom > base.bottom + 0.1f * h
        return raised || lowered
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
