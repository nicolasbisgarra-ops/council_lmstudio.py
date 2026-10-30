package io.trazo.core

import io.trazo.core.math.ApproxValue
import io.trazo.core.math.ExactValue
import io.trazo.core.math.LineResult
import io.trazo.core.math.MathException
import io.trazo.core.math.MathParser
import io.trazo.core.math.MathValue
import io.trazo.core.math.Poly
import io.trazo.core.math.Polynomials
import io.trazo.core.math.QuotientValue
import io.trazo.core.math.Rational
import io.trazo.core.math.Root
import io.trazo.core.math.sameAs
import io.trazo.core.math.Statement
import io.trazo.core.math.SymbolicValue
import io.trazo.core.math.TupleValue
import io.trazo.core.math.VariableGraph
import io.trazo.core.math.hasLetters
import io.trazo.core.math.variables
import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * Cuentas en la hoja: lee la tinta reconocida (salidas de patrones), arma los renglones, los convierte
 * a texto con [MathLayoutConverter] y los calcula con [VariableGraph] (las variables definidas en otros
 * renglones, como `x = 3`, valen para toda la hoja).
 *
 * - [results]: tinta del resultado que va después de cada «=» final de renglón. Es derivada: no se guarda
 *   en la página, se recalcula cuando la página cambia.
 * - [materialize]: la misma tinta (más el «=» si falta) para los renglones elegidos con el lazo, para
 *   insertarla en la página.
 *
 * - [calculate]: «Calcular» en el lazo. Calcula solo lo seleccionado y lo deja guardado como una cuenta
 *   ([CalcTag]): moverla o cambiarle el tamaño no la recalcula ni la junta con lo que tenga cerca; [refresh]
 *   la vuelve a calcular cuando se borran o se agregan signos dentro de ella.
 *
 * Las letras sin valor quedan escritas en el resultado («resolución genérica»): `2x + 3x = 5x`,
 * `(3, 2)·(5, k) = (15, 2k)`.
 *
 * Un renglón con escritura a mano sin reconocer, o con algo escrito después del «=», no se calcula:
 * el resultado sería el de una parte de la cuenta, o taparía la respuesta del usuario.
 * El resultado se dibuja con los patrones de los números (los del usuario, si tiene uno con ese nombre).
 */
object MathNotes {
    /** Color por defecto de lo autocompletado: verde. */
    const val DEFAULT_RESULT_COLOR: Int = 0xFF2E9D57.toInt()

    /** Margen vertical, en altos de signo, con que dos signos siguen en el mismo renglón (fracciones, exponentes). */
    private const val ROW_GAP = 0.25f
    private const val EQUALS = "Igual"
    /** Hueco horizontal, en altos de signo, que separa dos cuentas del mismo renglón. */
    private const val SPLIT_GAP = 1.5f
    /** Distancia máxima, en altos de signo, del numerador o el denominador a su barra. */
    private const val STACK_GAP = 0.8f
    /** Rayas que, con algo justo encima y justo debajo, son la barra de una fracción apilada. */
    private val BAR_LABELS = setOf("Menos", "Fracción", "Barra de fracción", "Barra", "Dividido")
    /** Las que, sin nada encima y debajo, son una división en línea («/»). */
    private val SLASH_LABELS = setOf("Fracción", "Barra de fracción", "Barra")

    /** Un signo reconocido: los trazos de una misma salida de patrón. */
    data class Glyph(val label: String, val bounds: Bounds, val strokes: List<InkStroke>) {
        val ids: List<String> get() = strokes.map { it.id }
    }

    /** Signos reconocidos de [strokes] con un patrón conocido. */
    fun glyphs(strokes: List<InkStroke>, patterns: Map<String, InkPattern>): List<Glyph> =
        strokes.filter { it.patternId != null && it.brush != Brush.HIGHLIGHTER }
            .groupBy { it.groupId ?: it.id }
            .mapNotNull { (_, g) -> patterns[g.first().patternId]?.let { p -> symbol(p)?.let { Glyph(it, g.bounds(), g) } } }

    /** Lo que vale un patrón en las cuentas; null si el usuario eligió no usarlo. */
    private fun symbol(p: InkPattern): String? = when (val u = p.unknown?.trim()) {
        null -> label(p.name).let { l ->
            if (MathLayoutConverter.spelling(l) != null || p.id.startsWith(PresetPatterns.PREFIX)) l else autoLetter(p)
        }
        "" -> null
        else -> u
    }

    /**
     * Un patrón del usuario con un nombre que no es número ni signo («Patrón», «mi k») es una letra sin
     * valor: se le asigna un carácter propio (fijo para ese patrón) y el resultado se dibuja con su tinta.
     */
    private fun autoLetter(p: InkPattern): String = (AUTO_FIRST + Math.floorMod(p.id.hashCode(), AUTO_COUNT)).toChar().toString()

    private const val AUTO_FIRST = 0x4E00
    private const val AUTO_COUNT = 0x5000

    /** Si [c] es la letra automática de un patrón (se muestra como «□» en los textos). */
    private fun isAuto(c: Char) = c.code in AUTO_FIRST until AUTO_FIRST + AUTO_COUNT

    /** Trazos de patrones que no cuentan en las cuentas: estorban como la escritura a mano. */
    private fun ignored(s: InkStroke, patterns: Map<String, InkPattern>) =
        s.patternId != null && patterns[s.patternId]?.let { symbol(it) == null } == true

    /** Nombres de signos sin tilde ni mayúsculas («mas», «IGUAL») como los lee el reconocedor. */
    private val SIGN_NAMES = listOf(
        "Más", "Menos", "Por", "Multiplicación", "Dividido", "División", "Igual", "Barra", "Fracción",
        "Barra de fracción", "Raíz", "Pi", "Abre paréntesis", "Cierra paréntesis", "Paréntesis abre",
        "Paréntesis cierra", "Coma", "Punto", "Porcentaje",
    ).associateBy { fold(it) } + mapOf(
        "equis" to "x", "ye" to "y", "i griega" to "y", "incognita" to "x",
        ":" to "Dividido", "÷" to "Dividido", "dos puntos" to "Dividido", "entre" to "Dividido", "%" to "Porcentaje",
    )

    private fun fold(name: String) = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    internal fun label(name: String): String = SIGN_NAMES[fold(name)] ?: name.trim()

    /**
     * Renglones: barrido de arriba hacia abajo; un signo entra al renglón si empieza antes de que termine.
     * Una fracción apilada (numerador, barra y denominador) entra entera, aunque ocupe dos alturas.
     */
    fun rows(glyphs: List<Glyph>): List<List<Glyph>> {
        if (glyphs.isEmpty()) return emptyList()
        val unit = unit(glyphs) ?: median(glyphs.map { it.bounds.height }) ?: 1f
        val group = glyphs.associateWithTo(java.util.IdentityHashMap()) { mutableListOf(it) }
        for ((bar, parts) in fractions(glyphs, unit)) for (part in parts) {
            val a = group.getValue(bar)
            val b = group.getValue(part)
            if (a !== b) { a += b; b.forEach { group[it] = a } }
        }
        val out = mutableListOf<MutableList<Glyph>>()
        var bottom = Float.NEGATIVE_INFINITY
        for (item in group.values.distinctBy { System.identityHashCode(it) }.sortedBy { i -> i.minOf { it.bounds.top } }) {
            if (out.isEmpty() || item.minOf { it.bounds.top } > bottom + ROW_GAP * unit) {
                out += mutableListOf<Glyph>()
                bottom = Float.NEGATIVE_INFINITY
            }
            out.last() += item
            bottom = maxOf(bottom, item.maxOf { it.bounds.bottom })
        }
        return out.flatMap { r -> split(r.sortedBy { it.bounds.left }) }
    }

    /** Alto de signo: el de las letras y números; si no hay, el de lo que no es plano. */
    private fun unit(glyphs: List<Glyph>): Float? = median(glyphs.filter { !isFlat(it) && !isSign(it) }.map { it.bounds.height })
        ?: median(glyphs.filter { !isFlat(it) }.map { it.bounds.height })

    /**
     * Barras de fracción apiladas: una raya («Fracción», «Menos», «Barra»…) con algo justo encima y justo
     * debajo, dentro de su ancho. Cada barra con lo que tiene encima y debajo (otra barra, si está anidada).
     */
    internal fun fractions(glyphs: List<Glyph>, unit: Float): Map<Glyph, List<Glyph>> = glyphs
        .filter { it.label in BAR_LABELS && isFlat(it) }
        .mapNotNull { bar ->
            val b = bar.bounds
            val slack = b.width * 0.1f
            val over = glyphs.filter { it !== bar && it.bounds.centerX in (b.left - slack)..(b.right + slack) }
            val above = over.filter { it.bounds.bottom <= b.centerY && b.top - it.bounds.bottom <= STACK_GAP * unit }
            val below = over.filter { it.bounds.top >= b.centerY && it.bounds.top - b.bottom <= STACK_GAP * unit }
            if (above.isEmpty() || below.isEmpty()) null else bar to above + below
        }.toMap()

    /**
     * Separa un renglón donde hay un hueco de más de [SPLIT_GAP] altos de signo: son cuentas distintas
     * escritas a la misma altura (por ejemplo, una a cada lado de la hoja).
     */
    private fun split(row: List<Glyph>): List<List<Glyph>> {
        val unit = unit(row) ?: return listOf(row)
        val out = mutableListOf(mutableListOf(row.first()))
        var right = row.first().bounds.right
        for (g in row.drop(1)) {
            if (g.bounds.left - right > SPLIT_GAP * unit) out += mutableListOf(g) else out.last() += g
            right = maxOf(right, g.bounds.right)
        }
        return out
    }

    /** Un renglón listo para calcular: su texto y, si termina en «=», ese signo. */
    private class Line(val glyphs: List<Glyph>, val text: String, val trailingEquals: Glyph?, val blocked: Boolean)

    /**
     * Renglones de la hoja sin las cuentas guardadas (esas ya tienen su resultado); con [only], solo los de
     * esos trazos (lo elegido con el lazo, o una cuenta guardada).
     */
    private fun lines(strokes: List<InkStroke>, patterns: Map<String, InkPattern>, only: Set<String>? = null): List<Line> {
        val pool = if (only == null) strokes.filter { it.calc == null } else strokes.filter { it.id in only }
        val handwriting = pool.filter { (it.patternId == null || ignored(it, patterns)) && it.brush != Brush.HIGHLIGHTER }
        return rows(glyphs(pool, patterns)).map { row ->
            val box = row.map { it.bounds }.reduce(Bounds::union)
            val last = row.maxByOrNull { it.bounds.right }!!
            val trailing = last.takeIf { it.label == EQUALS && row.none { g -> g !== it && g.bounds.centerX > it.bounds.right } }
            val unit = unit(row) ?: box.height
            // Tinta a mano dentro de la cuenta o en el lugar del resultado: no se calcula este renglón.
            val reach = Bounds(box.left, box.top, box.right + if (trailing != null) unit * 3f else 0f, box.bottom)
            val blocked = handwriting.any { s -> val b = s.bounds; reach.contains(b.centerX, b.centerY) }
            val text = if (blocked) "" else convert(row, fractions(row, unit).keys)
            Line(row, text, trailing.takeIf { !blocked }, blocked)
        }
    }

    /** Texto de un renglón. Las [bars] son fracciones apiladas; las demás barras dividen en línea. */
    private fun convert(row: List<Glyph>, bars: Set<Glyph>): String = try {
        MathLayoutConverter.convert(row.map { g ->
            val label = when {
                g in bars -> "Fracción"
                g.label in SLASH_LABELS -> "/"
                else -> g.label
            }
            LayoutSymbol(label, g.bounds.left, g.bounds.top, g.bounds.right, g.bounds.bottom)
        })
            .lines().singleOrNull().orEmpty()
    } catch (e: MathException) {
        ""
    }

    /** Marca de un signo sin reconocer en [reviewLines]. */
    private const val UNREAD = "\u0000?"

    /**
     * Los renglones numerados que se envían al Modo IA, de arriba hacia abajo: toda la tinta menos el
     * resaltador, también la escritura a mano sin reconocer (ese renglón va como «?» y el modelo lo lee en
     * la imagen). Con [only], solo esos trazos (lo elegido con el lazo).
     */
    fun reviewLines(strokes: List<InkStroke>, patterns: List<InkPattern>, only: Set<String>? = null): List<AiNotes.ReviewLine> {
        val pool = strokes.filter { it.brush != Brush.HIGHLIGHTER && (only == null || it.id in only) }
        val known = glyphs(pool, patterns.associateBy { it.id })
        val read = known.flatMap { it.ids }.toSet()
        val unread = pool.filter { it.id !in read }.groupBy { it.groupId ?: it.id }.map { (_, g) -> Glyph(UNREAD, g.bounds(), g) }
        return rows(known + unread).mapIndexed { i, row ->
            val box = row.map { it.bounds }.reduce(Bounds::union)
            val text = if (row.any { it.label == UNREAD }) "?" else convert(row, fractions(row, unit(row) ?: box.height).keys).ifBlank { "?" }
            val ids = row.flatMap { it.ids }
            AiNotes.ReviewLine(i + 1, text, box, ids, AiNotes.fingerprint(ids, pool))
        }
    }

    /** Valores de cada renglón, en el orden de [lines]; null donde no hay un resultado para mostrar. */
    private fun values(lines: List<Line>): List<MathValue?> =
        evaluate(lines.map { it.text }).mapIndexed { i, r -> shownValue(lines[i].text, r) }

    /** El valor para escribir después del «=»; null si no hay, o si con letras no se pudo simplificar nada. */
    private fun shownValue(text: String, r: LineResult): MathValue? =
        (r as? LineResult.Value)?.value?.takeIf { !trivial(text, it) }

    /**
     * Si el resultado con letras (o un par) es lo mismo que ya está escrito, en otro orden: `x + 1 =` daría
     * `x + 1`, y eso no se escribe. Se comparan los términos sueltos con sus caracteres ordenados.
     */
    internal fun trivial(text: String, value: MathValue): Boolean {
        if (!value.hasLetters() && value !is TupleValue) return false
        fun norm(t: String) = t.removeSuffix("=").replace("≈", "").replace(';', ',')
            .filter { it !in " *·^" }
            .map { c -> SUPERSCRIPTS.indexOf(c).let { if (it >= 0) '0' + it else c } }.joinToString("")
        fun terms(t: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            val cur = StringBuilder()
            for (c in t) {
                if (depth == 0 && c in "+-" && cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                if (c == '(') depth++
                if (c == ')') depth--
                cur.append(c)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out.map { it.removePrefix("+").toList().sorted().joinToString("") }.sorted()
        }
        return terms(norm(text)) == terms(norm(value.display()))
    }

    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    /**
     * Como [VariableGraph.evaluate], pero antes despeja las incógnitas: una igualdad con una sola incógnita
     * sin definir (`2x = 5`) se resuelve si es lineal, y su valor vale para la hoja como si estuviera
     * escrito `x = 5/2`. Se repite para encadenar (`2x = 5`, `y = x + 1`, `3z = y`).
     */
    internal fun evaluate(texts: List<String>): List<LineResult> =
        VariableGraph.evaluate(texts + solve(texts).map { it.text }, symbolic = true).take(texts.size)

    /** Una incógnita despejada: `x=5/2` ([text]) sale de la igualdad del renglón [from]. */
    private class Solved(val name: String, val text: String, val from: Int)

    private fun solve(texts: List<String>): List<Solved> {
        val statements = texts.map { runCatching { MathParser.parseStatement(it) }.getOrNull() }
        val assigned = statements.filterIsInstance<Statement.Assign>().map { it.name }.toMutableSet()
        val solved = mutableListOf<Solved>()
        for (round in 1..MAX_SOLVE_ROUNDS) {
            val before = solved.size
            statements.forEachIndexed { i, st ->
                if (st !is Statement.Check) return@forEachIndexed
                val unknown = (st.left.variables() + st.right.variables()).filter { it !in assigned }.singleOrNull()
                    ?: return@forEachIndexed
                val value = solveLinear(texts + solved.map { it.text }, i, unknown) ?: return@forEachIndexed
                solved += Solved(unknown, "$unknown=$value", i)
                assigned += unknown
            }
            if (solved.size == before) break
        }
        return solved
    }

    private const val MAX_SOLVE_ROUNDS = 4

    /** Valor (como texto) de [name] que cumple la igualdad del renglón [line], si es lineal en [name]. */
    private fun solveLinear(texts: List<String>, line: Int, name: String): String? {
        fun f(x: Int): MathValue? {
            val r = VariableGraph.evaluate(texts + "$name=$x")[line] as? LineResult.Check ?: return null
            val (a, b) = r.left to r.right
            if (a !is ExactValue && a !is ApproxValue || b !is ExactValue && b !is ApproxValue) return null
            return if (a is ExactValue && b is ExactValue) a - b else ApproxValue(a.toDouble() - b.toDouble())
        }
        val f0 = f(0) ?: return null
        val f1 = f(1) ?: return null
        val f2 = f(2) ?: return null
        if (f0 is ExactValue && f1 is ExactValue && f2 is ExactValue) {
            val slope = f1 - f0
            if (slope.isZero || f2 - f1 != slope) return null
            val c = slope.rational ?: return approx(f0.toDouble(), slope.toDouble())
            val k = f0.rational ?: return approx(f0.toDouble(), slope.toDouble())
            val x = -(k / c)
            return "(${x.num})/(${x.den})"
        }
        val (y0, y1, y2) = Triple(f0.toDouble(), f1.toDouble(), f2.toDouble())
        val slope = y1 - y0
        if (abs(slope) < 1e-12 || abs((y2 - y1) - slope) > 1e-9 * maxOf(1.0, abs(slope))) return null
        return approx(y0, slope)
    }

    private fun approx(f0: Double, slope: Double): String? {
        val x = -f0 / slope
        return if (x.isFinite()) BigDecimal(x).round(MathContext(15)).toPlainString().let { if (x < 0) "($it)" else it } else null
    }

    /**
     * Tinta de los resultados que faltan después de cada «=» final de renglón, con [color]. Vacía si no hay
     * ninguno. No se guarda en la página.
     */
    fun results(strokes: List<InkStroke>, patterns: List<InkPattern>, color: Int = DEFAULT_RESULT_COLOR, ids: IdGenerator = IdGenerator.sequential("r")): List<InkStroke> {
        val byId = patterns.associateBy { it.id }
        val lines = lines(strokes, byId)
        if (lines.none { it.trailingEquals != null }) return emptyList()
        val values = values(lines)
        val writer = Writer(patterns, ids)
        return lines.indices.flatMap { i ->
            val eq = lines[i].trailingEquals ?: return@flatMap emptyList()
            val value = values[i] ?: return@flatMap emptyList()
            writer.value(value, eq, lines[i].glyphs, color)
        }
    }

    /** Qué se calculó para un resultado: la cuenta completa y de dónde sale cada variable que usa. */
    data class Explanation(val bounds: Bounds, val steps: List<String>)

    /**
     * La explicación del resultado tocado en ([x], [y]) (como en Samsung Notes): el resultado que se muestra
     * después de un «=», o con [wholeLine] cualquier parte de un renglón con «=» (también un resultado ya
     * escrito en tinta). null si ahí no hay ninguno. [slop] agranda la zona del toque.
     */
    fun explain(strokes: List<InkStroke>, patterns: List<InkPattern>, x: Float, y: Float, slop: Float = 0f, wholeLine: Boolean = false): Explanation? {
        fun hit(b: Bounds) = x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop
        // Una cuenta guardada explica lo que se calculó cuando se resolvió.
        for (op in strokes.filter { it.calc != null }.groupBy { it.calc!!.id }.values) {
            val result = op.filter { it.calc!!.result }
            val steps = result.firstOrNull()?.calc?.steps?.takeIf { it.isNotEmpty() } ?: continue
            val target = if (wholeLine) op.bounds() else result.bounds()
            if (hit(target)) return Explanation(target, steps)
        }
        val byId = patterns.associateBy { it.id }
        val lines = lines(strokes, byId)
        if (lines.none { l -> l.glyphs.any { it.label == EQUALS } }) return null
        val texts = lines.map { it.text }
        val solved = solve(texts)
        val all = texts + solved.map { it.text }
        val results = VariableGraph.evaluate(all, symbolic = true)
        val writer = Writer(patterns, IdGenerator.sequential("explain"))
        for ((i, line) in lines.withIndex()) {
            if (line.text.isEmpty() || line.glyphs.none { it.label == EQUALS }) continue
            val result = results[i]
            val box = line.glyphs.map { it.bounds }.reduce(Bounds::union)
            val value = shownValue(line.text, result)
            val shownInk = if (line.trailingEquals != null && value != null) writer.value(value, line.trailingEquals, line.glyphs, 0) else emptyList()
            val target = when {
                shownInk.isNotEmpty() && wholeLine -> box.union(shownInk.bounds())
                shownInk.isNotEmpty() -> shownInk.bounds().union(line.trailingEquals!!.bounds)
                wholeLine -> box
                else -> continue
            }
            if (!hit(target)) continue
            return Explanation(target, steps(i, texts, solved, results) ?: continue)
        }
        return null
    }

    /** Explicación del renglón [i]: la cuenta con su resultado y de dónde sale cada variable. */
    private fun steps(i: Int, texts: List<String>, solved: List<Solved>, results: List<LineResult>): List<String>? {
        val text = texts[i]
        val result = results[i]
        val main = when (result) {
            is LineResult.Value -> "${pretty(text)} ${result.value.display()}"
            is LineResult.Assignment -> pretty(text)
            is LineResult.Check -> pretty(text) + if (result.holds) "  ✓" else "  ✗ (${result.left.display()} ≠ ${result.right.display()})"
            is LineResult.Failure -> "${pretty(text)}  → ${result.error.message}"
            LineResult.Empty -> return null
        }
        val steps = mutableListOf(main)
        val value = (result as? LineResult.Value)?.value
        if (value != null && value.display().let { it.contains('/') || it.contains('π') }) {
            decimal(value)?.let { steps += "≈ " + (if (it.negative) "−" else "") + it.numerator }
        }
        if (value != null && value.hasLetters()) steps += "Las letras sin valor quedan escritas: se operó con los números."
        steps += sources(i, texts, solved, results)
        return steps.map(::readable)
    }

    /** Un texto para el usuario: las letras automáticas se muestran como «□». */
    private fun readable(s: String) = s.map { if (isAuto(it)) '□' else it }.joinToString("")

    /** De dónde sale cada variable que usa el renglón [line], siguiendo las que dependen de otras. */
    private fun sources(line: Int, texts: List<String>, solved: List<Solved>, results: List<LineResult>): List<String> {
        val statements = texts.map { runCatching { MathParser.parseStatement(it) }.getOrNull() }
        fun uses(i: Int): Set<String> = when (val st = statements[i]) {
            is Statement.Assign -> st.expr.variables()
            is Statement.Query -> st.expr.variables()
            is Statement.Check -> st.left.variables() + st.right.variables()
            else -> emptySet()
        }
        val definedAt = statements.withIndex().mapNotNull { (i, st) -> (st as? Statement.Assign)?.let { it.name to i } }.toMap()
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(uses(line).toList())
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            val at = definedAt[name]
            val s = solved.withIndex().firstOrNull { it.value.name == name }
            when {
                at != null && at != line -> {
                    val v = (results[at] as? LineResult.Assignment)?.value?.display()
                    out += "$name = ${v ?: "?"}   (de «${pretty(texts[at])}»)"
                    queue += uses(at)
                }
                s != null -> {
                    val v = (results[texts.size + s.index] as? LineResult.Assignment)?.value?.display()
                    out += "$name = ${v ?: "?"}   (despejada de «${pretty(texts[s.value.from])}»)"
                    queue += uses(s.value.from) - name
                }
            }
        }
        return out
    }

    private val SIMPLE_PARENS = Regex("(?<![\\p{L}\\d)])\\(([\\p{L}\\d.,π]+)\\)")

    /** La cuenta como texto legible: «2·x + 1 =». */
    internal fun pretty(raw: String): String {
        // «(1)/(2)» de una fracción apilada se lee «1/2».
        val text = raw.replace(SIMPLE_PARENS, "$1").replace(';', ',').map { if (isAuto(it)) '□' else it }.joinToString("")
        val out = StringBuilder()
        for ((i, c) in text.withIndex()) {
            val prev = text.getOrNull(i - 1)
            val binary = c in "+=" || (c == '-' && prev != null && (prev.isLetterOrDigit() || prev == ')' || prev == 'π'))
            when {
                c == '*' -> out.append('·')
                c == ' ' -> Unit
                c == ',' -> out.append(", ")
                binary -> { if (out.isNotEmpty()) out.append(' '); out.append(if (c == '-') '−' else c); out.append(' ') }
                c == '-' -> out.append('−')
                else -> out.append(c)
            }
        }
        return out.toString().trim()
    }

    /**
     * Tinta para insertar en los renglones que tocan [selected]: el resultado después del «=» final, o un
     * «=» (con el estilo de la cuenta) y el resultado si la cuenta no tiene igual. Vacía si nada se puede calcular.
     */
    fun materialize(strokes: List<InkStroke>, selected: Set<String>, patterns: List<InkPattern>, color: Int = DEFAULT_RESULT_COLOR, ids: IdGenerator): List<InkStroke> {
        val byId = patterns.associateBy { it.id }
        val lines = lines(strokes, byId)
        val values = values(lines)
        val writer = Writer(patterns, ids)
        return lines.indices.flatMap { i ->
            val line = lines[i]
            val value = values[i]
            if (value == null || line.glyphs.none { g -> g.ids.any { it in selected } }) return@flatMap emptyList()
            val eq = line.trailingEquals
            if (eq != null) return@flatMap writer.value(value, eq, line.glyphs, color)
            if (line.glyphs.any { it.label == EQUALS }) return@flatMap emptyList()
            val added = writer.equals(line.glyphs) ?: return@flatMap emptyList()
            val glyph = Glyph(EQUALS, added.bounds(), added)
            added + writer.value(value, glyph, line.glyphs, color)
        }
    }

    /** Los trazos de la selección que forman la cuenta: los elegidos y el resto de sus signos, sin resultados guardados. */
    private fun pool(strokes: List<InkStroke>, selected: Set<String>): Set<String> {
        val groups = strokes.filter { it.id in selected }.mapNotNullTo(HashSet()) { it.groupId }
        return strokes.filter { (it.id in selected || it.groupId in groups) && it.calc?.result != true }.mapTo(HashSet()) { it.id }
    }

    /** Un renglón resuelto: sus trazos, el «=» que faltaba y el resultado, todavía sin marcar. */
    private class Resolved(val existing: List<InkStroke>, val newInk: List<InkStroke>, val result: List<InkStroke>, val steps: List<String>, val complex: Boolean = false)

    /** Lo que sale de resolver ecuaciones: lo que se escribe abajo, o por qué no. */
    private sealed interface Outcome {
        class Solved(val pieces: List<Piece>, val steps: List<String>) : Outcome
        /** [complexOnly]: no tiene soluciones reales pero sí con números imaginarios. */
        class Failed(val message: String, val complexOnly: Boolean = false) : Outcome
    }

    /** Un renglón con un «=» en el medio (no al final): una ecuación para resolver. */
    private fun isEquation(line: Line) = !line.blocked && line.trailingEquals == null && line.text.count { it == '=' } == 1 &&
        !line.text.startsWith("=") && !line.text.endsWith("=")

    /** `k = 2` (una letra igual a algo que ya tiene valor) es una definición, no una ecuación para resolver. */
    private fun isDefinition(text: String, context: List<String>): Boolean {
        val st = runCatching { MathParser.parseStatement(text) }.getOrNull() as? Statement.Assign ?: return false
        if (st.expr.variables().isEmpty()) return true
        val r = VariableGraph.evaluate(context + text.substringAfter('='), symbolic = true).last()
        return r is LineResult.Value && !r.value.hasLetters()
    }

    /** La letra que se despeja cuando hay varias: x, y, z o t; si no, ninguna. */
    private val PREFERRED = listOf("x", "y", "z", "t")

    /**
     * Resuelve las ecuaciones [equations] (textos `L=R`) con las variables de [context]: una sola ecuación
     * en una letra (polinomio hasta grado [Polynomials.MAX_DEGREE], o lineal con otras letras), o un sistema
     * lineal con tantas ecuaciones como letras. Con [complex], también las soluciones imaginarias.
     */
    private fun solveEquations(equations: List<String>, context: List<String>, complex: Boolean): Outcome {
        val diffs = equations.map { "(" + it.substringBefore('=') + ")-(" + it.substringAfter('=') + ")" }
        val base = context + solve(context).map { it.text }
        val results = VariableGraph.evaluate(base + diffs, symbolic = true).takeLast(diffs.size)
        val values = results.mapIndexed { i, r ->
            when (r) {
                is LineResult.Value -> r.value
                is LineResult.Failure -> return Outcome.Failed("En «${readable(pretty(equations[i]))}»: ${r.error.message}.")
                else -> return Outcome.Failed("No pude leer «${readable(pretty(equations[i]))}» como ecuación.")
            }
        }
        if (values.any { it is TupleValue || it is QuotientValue }) return Outcome.Failed("Solo resuelvo ecuaciones con números y letras (sin pares ni divisiones con resto).")
        return if (values.size == 1) single(equations[0], values[0], complex) else system(equations, values)
    }

    private fun lettersOf(v: MathValue): Set<String> =
        if (v is SymbolicValue) v.terms.keys.flatMapTo(sortedSetOf()) { m -> m.keys.filter { it != SymbolicValue.PI_LETTER } } else emptySet()

    private fun single(equation: String, raw: MathValue, complex: Boolean): Outcome {
        val shown = readable(pretty(equation))
        val letters = lettersOf(raw)
        if (letters.isEmpty()) {
            return Outcome.Failed(if (raw.sameAs(ExactValue.ZERO)) "«$shown» se cumple siempre: no hay nada que despejar." else "«$shown» no se cumple nunca: no tiene solución.")
        }
        val letter = letters.singleOrNull() ?: PREFERRED.firstOrNull { it in letters }
            ?: return Outcome.Failed("«$shown» tiene más de una letra sin valor (${letters.joinToString(", ") { readable(it) }}): escribí la x, o encerrá también otra ecuación para un sistema.")
        // Si la letra divide (1/x), se multiplica todo por la potencia que la saca; x = 0 no vale.
        val lowest = (raw as SymbolicValue).terms.keys.minOf { it[letter] ?: 0 }
        val v = if (lowest < 0) SymbolicValue.multiply(raw, SymbolicValue.power(SymbolicValue.variable(letter), ExactValue.of(Rational.of(-lowest.toLong())))) else raw
        val terms = SymbolicValue.termsOf(v)
        val degree = terms.keys.maxOf { it[letter] ?: 0 }
        if (degree == 0) return Outcome.Failed("En «$shown» la $letter se cancela: no hay nada que despejar.")
        if (terms.keys.any { m -> (m[letter] ?: 0) < 0 }) return Outcome.Failed("«$shown» no es un polinomio en ${readable(letter)}.")
        val poly = Poly.of(v, letter) ?: Poly.of(exactly(v), letter)
        if (poly == null) {
            if (degree != 1) return Outcome.Failed("Para resolver «$shown» de grado $degree, la ecuación tiene que tener solo números y la ${readable(letter)}.")
            // Lineal con otras letras: a·x + b = 0 → x = −b/a.
            val a = SymbolicValue.of(terms.filterKeys { (it[letter] ?: 0) == 1 }.mapKeys { (m, _) -> m - letter })
            val b = SymbolicValue.of(terms.filterKeys { (it[letter] ?: 0) == 0 })
            val x = try { SymbolicValue.divide(SymbolicValue.negate(b), a) } catch (e: MathException) {
                return Outcome.Failed("No pude despejar ${readable(letter)} de «$shown»: ${e.error.message}.")
            }
            val pieces = pieces(x) ?: return Outcome.Failed("La solución de «$shown» es muy larga para escribirla.")
            return Outcome.Solved(listOf(Piece.Text(letter), Piece.Eq) + pieces, listOf(shown, readable("$letter = ${x.display()}"), "Se despejó ${readable(letter)}: las otras letras quedan escritas."))
        }
        if (poly.degree > Polynomials.MAX_DEGREE) return Outcome.Failed("«$shown» es de grado ${poly.degree}: resuelvo hasta grado ${Polynomials.MAX_DEGREE}.")
        var roots = Polynomials.roots(poly, complex).orEmpty()
        if (lowest < 0) roots = roots.filterNot { it is Root.Exact && it.a.isZero && it.b.isZero }
        if (roots.isEmpty()) {
            val any = Polynomials.roots(poly, complex = true).orEmpty().isNotEmpty()
            return Outcome.Failed(if (any) "«$shown» no tiene solución real." else "«$shown» no tiene solución.", complexOnly = any && !complex)
        }
        val out = mutableListOf<Piece>()
        for ((i, r) in roots.withIndex()) {
            if (i > 0) out += Piece.Sep
            out += Piece.Text(letter)
            out += Piece.Eq
            out += rootPieces(r) ?: return Outcome.Failed("Una solución de «$shown» es muy larga para escribirla.")
        }
        val how = when {
            poly.degree == 1 -> "Ecuación de grado 1: se despejó ${readable(letter)}."
            poly.degree == 2 -> "Ecuación de grado 2: fórmula resolvente (−b ± √(b² − 4ac)) / 2a."
            roots.any { it is Root.Approx } -> "Grado ${poly.degree}: raíces racionales exactas (Ruffini) y el resto con decimales."
            else -> "Grado ${poly.degree}: se factorizó con las raíces racionales (Ruffini)."
        }
        return Outcome.Solved(out, listOf(shown, readable(plain(out)), how))
    }

    /** Coeficientes decimales pasados a fracción (15 cifras), para poder resolver. */
    private fun exactly(v: MathValue): MathValue = SymbolicValue.of(SymbolicValue.termsOf(v).mapValues { (_, c) ->
        if (c is ApproxValue) ExactValue.of(Rational.parseDecimal(BigDecimal(c.value).round(MathContext(15)).toPlainString())) else c
    })

    private fun system(equations: List<String>, values: List<MathValue>): Outcome {
        val shown = equations.joinToString(" ; ") { readable(pretty(it)) }
        val letters = values.flatMapTo(sortedSetOf()) { lettersOf(it) }.toList()
        if (letters.size != values.size) {
            return Outcome.Failed("El sistema tiene ${values.size} ecuaciones y ${letters.size} letras sin valor (${letters.joinToString(", ") { readable(it) }}): tienen que ser la misma cantidad.")
        }
        val rows = values.map { v ->
            val terms = SymbolicValue.termsOf(exactly(v))
            val row = MutableList(letters.size + 1) { Rational.ZERO }
            for ((m, c) in terms) {
                val r = (c as? ExactValue)?.rational ?: return Outcome.Failed("Resuelvo sistemas con números y letras (sin π).")
                when {
                    m.isEmpty() -> row[letters.size] = -r
                    m.size == 1 && m.values.single() == 1 -> row[letters.indexOf(m.keys.single())] = r
                    else -> return Outcome.Failed("Resuelvo sistemas lineales: cada letra sola y sin exponente.")
                }
            }
            row
        }
        val solution = Polynomials.solveLinear(rows, letters.size)
            ?: return Outcome.Failed("El sistema no tiene una única solución (no tiene ninguna o tiene infinitas).")
        val out = mutableListOf<Piece>()
        for ((i, l) in letters.withIndex()) {
            if (i > 0) out += Piece.Sep
            out += Piece.Text(l)
            out += Piece.Eq
            out += pieces(ExactValue.of(solution[i])) ?: return Outcome.Failed("Una solución es muy larga para escribirla.")
        }
        return Outcome.Solved(out, listOf(shown, readable(plain(out)), "Sistema de ${letters.size} ecuaciones lineales (eliminación de Gauss)."))
    }

    /** Una solución para dibujar: `2`, `(5 + √13)/2`, `1 − 2i`, `0,4142`. */
    private fun rootPieces(r: Root): List<Piece>? = when (r) {
        is Root.Approx -> {
            val re = if (r.re == 0.0 && r.im != 0.0) emptyList() else pieces(ApproxValue(r.re)) ?: return null
            if (r.im == 0.0) re else {
                val im = decimal(ApproxValue(abs(r.im)))?.numerator ?: return null
                re + (if (re.isEmpty()) (if (r.im < 0) listOf(Piece.Sign(false, unary = true)) else emptyList()) else listOf(Piece.Sign(r.im > 0))) +
                    (if (im == "1") emptyList() else listOf(Piece.Text(im))) + Piece.Text("i")
            }
        }
        is Root.Exact -> if (r.b.isZero) pieces(ExactValue.of(r.a)) else {
            val d = r.a.den / r.a.den.gcd(r.b.den) * r.b.den
            val a = (r.a * Rational.of(d)).num
            val b = (r.b * Rational.of(d)).num
            val surd = (if (b.abs() == java.math.BigInteger.ONE && (r.m != java.math.BigInteger.ONE || r.imaginary)) emptyList() else listOf(Piece.Text(b.abs().toString()))) +
                (if (r.m == java.math.BigInteger.ONE) emptyList() else listOf(Piece.Root(listOf(Piece.Text(r.m.toString()))))) +
                (if (r.imaginary) listOf(Piece.Text("i")) else emptyList())
            val num = (if (a.signum() == 0) emptyList() else listOf(Piece.Sign(false, unary = true)).takeIf { a.signum() < 0 }.orEmpty() + Piece.Text(a.abs().toString())) +
                (if (a.signum() == 0) listOf(Piece.Sign(false, unary = true)).takeIf { b.signum() < 0 }.orEmpty() else listOf(Piece.Sign(b.signum() > 0))) + surd
            if (d == java.math.BigInteger.ONE) num else listOf(Piece.Frac(num, listOf(Piece.Text(d.toString()))))
        }
    }

    /**
     * La factorización de un polinomio en una letra con raíces racionales: `x² − 5x + 6` → `(x − 2)(x − 3)`,
     * `2x + 4` → `2(x + 2)`. null si no se puede factorizar más.
     */
    private fun factorPieces(v: MathValue): List<Piece>? {
        val letter = lettersOf(v).singleOrNull() ?: return null
        val poly = Poly.of(v, letter) ?: return null
        val (k, factors) = Polynomials.factor(poly) ?: return null
        val nontrivial = factors.size > 1 || factors.any { it.power > 1 } || (k != Rational.ONE && k != -Rational.ONE && factors.isNotEmpty())
        if (!nontrivial) return null
        val out = mutableListOf<Piece>()
        when {
            k == -Rational.ONE -> out += Piece.Sign(false, unary = true)
            k != Rational.ONE -> out += pieces(ExactValue.of(k)) ?: return null
        }
        for (f in factors) {
            val inner = pieces(f.poly.toValue(letter)) ?: return null
            val single = (f.poly.toValue(letter) as? SymbolicValue)?.terms?.size == 1
            out += if (single) inner else listOf(Piece.Open) + inner + Piece.Close
            if (f.power > 1) out += Piece.Sup(f.power.toString())
        }
        return out
    }

    /** Para las pruebas: las soluciones de [equations] como texto, o el motivo del fallo precedido de «!». */
    internal fun solveText(equations: List<String>, context: List<String> = emptyList(), complex: Boolean = false): String =
        when (val o = solveEquations(equations, context, complex)) {
            is Outcome.Solved -> readable(plain(o.pieces))
            is Outcome.Failed -> "!" + (if (o.complexOnly) "*" else "") + o.message
        }

    /** Para las pruebas: [text] factorizado, o null si no se puede. */
    internal fun factorText(text: String): String? {
        val r = VariableGraph.evaluate(listOf(text), symbolic = true).last() as? LineResult.Value ?: return null
        return factorPieces(r.value)?.let(::plain)
    }

    /** Entre paréntesis solo si es más que un término suelto. */
    private fun wrap(t: String) = if (t.any { it == ' ' || it == '+' || it == '−' || it == '/' }) "($t)" else t

    /** Las piezas como texto, para la explicación. */
    private fun plain(pieces: List<Piece>): String = pieces.joinToString("") { p ->
        when (p) {
            is Piece.Text -> p.text
            is Piece.Sup -> p.text.map { SUPERSCRIPTS[it - '0'] }.joinToString("")
            is Piece.Frac -> wrap(plain(p.num)) + "/" + wrap(plain(p.den))
            is Piece.Sign -> if (p.unary) (if (p.plus) "" else "−") else if (p.plus) " + " else " − "
            Piece.Open -> "("
            Piece.Close -> ")"
            Piece.Sep -> " ; "
            Piece.Eq -> " = "
            is Piece.Root -> "√" + plain(p.inner)
        }
    }

    /**
     * Resuelve los renglones de los trazos [pool] (solo esos). Las variables salen del resto de la hoja (sin
     * las cuentas guardadas). Solo con [addEquals] se agrega el «=» a un renglón que no lo tiene.
     */
    private fun resolve(strokes: List<InkStroke>, pool: Set<String>, patterns: List<InkPattern>, color: Int, ids: IdGenerator, addEquals: Boolean, complex: Boolean = false): List<Resolved> {
        val byId = patterns.associateBy { it.id }
        val lines = lines(strokes, byId, only = pool)
        if (lines.isEmpty()) return emptyList()
        val writer = Writer(patterns, ids)
        val (equations, context) = equations(lines, lines(strokes.filter { it.id !in pool }, byId))
        val out = mutableListOf<Resolved>()
        if (equations.isNotEmpty()) {
            val outcome = solveEquations(equations.map { lines[it].text }, context, complex)
            if (outcome is Outcome.Solved) {
                val rows = equations.map { lines[it].glyphs }
                val ink = writer.under(outcome.pieces, rows, color)
                if (ink.isNotEmpty()) out += Resolved(rows.flatten().flatMap { it.strokes }, emptyList(), ink, outcome.steps, complex)
            }
        }
        val texts = (lines + lines(strokes.filter { it.id !in pool }, byId)).map { it.text }.toMutableList()
        val solved = solve(texts)
        val results = VariableGraph.evaluate(texts + solved.map { it.text }, symbolic = true)
        return out + lines.indices.mapNotNull { i ->
            val line = lines[i]
            if (i in equations) return@mapNotNull null
            val raw = (results[i] as? LineResult.Value)?.value
            val factored = if (raw != null && trivial(line.text, raw)) factorPieces(raw) else null
            val value = if (factored != null) raw else shownValue(line.text, results[i]) ?: return@mapNotNull null
            var eq = line.trailingEquals
            var added = emptyList<InkStroke>()
            if (eq == null) {
                if (!addEquals || line.glyphs.any { it.label == EQUALS }) return@mapNotNull null
                added = writer.equals(line.glyphs) ?: return@mapNotNull null
                eq = Glyph(EQUALS, added.bounds(), added)
                texts[i] = texts[i] + "="
            }
            val ink = if (factored != null) writer.after(factored, eq, line.glyphs, color) else writer.value(value!!, eq, line.glyphs, color)
            if (ink.isEmpty()) return@mapNotNull null
            val steps = if (factored != null) {
                listOf(readable(pretty(line.text.removeSuffix("=")) + " = " + plain(factored)), "Se factorizó con sus raíces racionales (Ruffini).")
            } else steps(i, texts, solved, results).orEmpty()
            Resolved(line.glyphs.flatMap { it.strokes }, added, ink, steps)
        }
    }

    /** Índices de los renglones de [lines] que son ecuaciones para resolver, y el resto de los textos (con [others]). */
    private fun equations(lines: List<Line>, others: List<Line>): Pair<List<Int>, List<String>> {
        val candidates = lines.indices.filter { isEquation(lines[it]) }
        val rest = (lines.indices - candidates.toSet()).map { lines[it].text } + others.map { it.text }
        val eqs = candidates.filter { !isDefinition(lines[it].text, rest + (candidates.map { c -> lines[c].text } - lines[it].text)) }
        return eqs to (lines.indices - eqs.toSet()).map { lines[it].text } + others.map { it.text }
    }

    private fun tagged(r: Resolved, id: String, inputs: (InkStroke) -> InkStroke = { it }): Pair<List<InkStroke>, List<InkStroke>> {
        val n = r.existing.size + r.newInk.size
        val tag = CalcTag(id, inputs = n)
        val result = r.result.map { it.copy(calc = CalcTag(id, result = true, inputs = r.result.size, steps = r.steps, complex = r.complex)) }
        return r.existing.map { inputs(it).copy(calc = tag) } to r.newInk.map { it.copy(calc = tag) } + result
    }

    /**
     * «Calcular» en el lazo: calcula solo los signos de [selected] (no lo que está al lado) y guarda cada
     * renglón como una cuenta ([CalcTag]): sus signos, el «=» si faltaba y el resultado quedan marcados.
     * Las variables de otros renglones valen con el valor que tienen ahora. Una cuenta guardada que toca la
     * selección se rehace. null si nada se puede calcular (ver [problem]).
     */
    fun calculate(strokes: List<InkStroke>, selected: Set<String>, patterns: List<InkPattern>, color: Int = DEFAULT_RESULT_COLOR, ids: IdGenerator, complex: Boolean = false): StrokeEdit? {
        val pool = pool(strokes, selected)
        val made = resolve(strokes, pool, patterns, color, ids, addEquals = true, complex = complex)
        if (made.isEmpty()) return null
        val touched = strokes.filter { it.id in selected || it.id in pool }.mapNotNullTo(HashSet()) { it.calc?.id }
        val original = strokes.associateBy { it.id }
        val replaced = LinkedHashMap<String, InkStroke>()
        // Las cuentas guardadas que se vuelven a calcular pierden su resultado; sus signos quedan sueltos.
        strokes.filter { it.calc?.id in touched && it.calc?.result == false }.forEach { replaced[it.id] = it.copy(calc = null) }
        val fresh = mutableListOf<InkStroke>()
        for (r in made) {
            val (inputs, ink) = tagged(r, ids.next())
            inputs.forEach { replaced[it.id] = it }
            fresh += ink
        }
        val oldResults = strokes.filter { it.calc?.id in touched && it.calc?.result == true }
        return StrokeEdit(oldResults + replaced.keys.map { original.getValue(it) }, replaced.values.toList() + fresh, StrokeEdit.Kind.INSERT)
    }

    /**
     * Pone al día las cuentas guardadas después de borrar o agregar algo ([added] = trazos nuevos de ese
     * cambio). Moverlas o cambiarles el tamaño no las cambia; en cambio:
     * - un signo nuevo escrito dentro de una cuenta se suma a ella, y la cuenta se vuelve a calcular;
     * - si se borró algo de la cuenta, se vuelve a calcular (si queda a medias, espera sin resultado);
     * - si se borró el resultado o el «=», deja de ser una cuenta guardada; si se borró la cuenta, se va su resultado.
     * null si no hay nada que cambiar.
     */
    fun refresh(strokes: List<InkStroke>, patterns: List<InkPattern>, added: Set<String>, ids: IdGenerator, color: Int = DEFAULT_RESULT_COLOR): StrokeEdit? {
        val ops = strokes.filter { it.calc != null }.groupBy { it.calc!!.id }
        if (ops.isEmpty()) return null
        val byId = patterns.associateBy { it.id }
        val newcomers = glyphs(strokes.filter { it.id in added && it.calc == null }, byId)
        val claimed = HashSet<String>()
        val removed = mutableListOf<InkStroke>()
        val replaced = LinkedHashMap<String, InkStroke>()
        val fresh = mutableListOf<InkStroke>()
        for ((id, op) in ops) {
            val (results, inputs) = op.partition { it.calc!!.result }
            val tag = inputs.firstOrNull()?.calc
            fun dissolve() {
                removed += results
                inputs.forEach { replaced[it.id] = it.copy(calc = null) }
            }
            when {
                tag == null -> { removed += results; continue }
                results.isEmpty() && !tag.failed -> { dissolve(); continue }
                results.isNotEmpty() && results.size != results.first().calc!!.inputs -> { dissolve(); continue }
            }
            val box = inputs.bounds().let { Bounds(it.left - it.height * 0.3f, it.top, it.right + it.height * 0.3f, it.bottom) }
            val joined = newcomers.filter { g -> g.ids.none { it in claimed } && box.contains(g.bounds.centerX, g.bounds.centerY) }
            if (joined.isEmpty() && inputs.size == tag!!.inputs) continue
            val joinedStrokes = joined.flatMap { it.strokes }
            claimed += joinedStrokes.map { it.id }
            val members = inputs + joinedStrokes
            if (glyphs(members, byId).none { it.label == EQUALS }) { dissolve(); continue }
            removed += results
            val made = resolve(strokes, members.mapTo(HashSet()) { it.id }, patterns, results.firstOrNull()?.color ?: color, ids, addEquals = false,
                complex = results.firstOrNull()?.calc?.complex == true)
                .singleOrNull()
            if (made == null) {
                val waiting = CalcTag(id, inputs = members.size, failed = true)
                members.forEach { replaced[it.id] = it.copy(calc = waiting) }
                continue
            }
            val (tags, ink) = tagged(made, id)
            tags.forEach { replaced[it.id] = it }
            fresh += ink
        }
        val original = strokes.associateBy { it.id }
        val edit = StrokeEdit(removed + replaced.keys.map { original.getValue(it) }, replaced.values.toList() + fresh, StrokeEdit.Kind.INSERT)
        return edit.takeIf { replaced.isNotEmpty() || removed.isNotEmpty() || fresh.isNotEmpty() }
    }

    /**
     * Por qué «Calcular» no escribe nada para [selected], en palabras para el usuario. Se usa solo cuando
     * [materialize] vuelve vacío.
     */
    fun problem(strokes: List<InkStroke>, selected: Set<String>, patterns: List<InkPattern>): String {
        val byId = patterns.associateBy { it.id }
        val chosen = strokes.filter { it.id in selected && it.brush != Brush.HIGHLIGHTER }
        if (chosen.none { it.patternId != null }) {
            return "Lo seleccionado es escritura a mano: para calcular, cada número y signo tiene que estar convertido " +
                "con un patrón (pausa del lápiz al terminarlo)."
        }
        val lost = chosen.filter { it.patternId != null && it.patternId !in byId }
        if (lost.isNotEmpty()) return "Hay signos convertidos con un patrón que ya no existe: volvé a escribirlos."
        val pool = pool(strokes, selected)
        val lines = lines(strokes, byId, only = pool)
        if (lines.isEmpty()) return "No hay una cuenta en la selección."
        val (equations, context) = equations(lines, lines(strokes.filter { it.id !in pool }, byId))
        if (equations.isNotEmpty()) {
            val outcome = solveEquations(equations.map { lines[it].text }, context, complex = false)
            if (outcome is Outcome.Failed) return outcome.message
        }
        val values = evaluate((lines + lines(strokes.filter { it.id !in pool }, byId)).map { it.text })
        for ((line, result) in lines.zip(values)) {
            val read = line.glyphs.joinToString(" ") { it.label }
            if (line.blocked) return "Hay trazos a mano sin convertir (o patrones marcados «No usar en cuentas») dentro de la cuenta o justo después del = (leí: $read)."
            val unknown = line.glyphs.map { it.label }.distinct().filter { name ->
                name !in SIGN_NAMES.values && name != "f( )" && runCatching { MathLayoutConverter.convert(listOf(LayoutSymbol(name, 0f, 0f, 1f, 1f))) }.isFailure
            }
            if (unknown.isNotEmpty()) {
                return "No sé leer ${unknown.joinToString(", ") { "«$it»" }} como número o signo. " +
                    "Renombrá ese patrón con el número, la letra o el signo (por ejemplo «5», «x», «Más», «Igual»), " +
                    "o en Patrones → Uso en las cuentas elegí «Incógnita» y su letra."
            }
            if (line.text.isEmpty()) return "Los signos no arman una cuenta (leí: $read)."
            val eqs = line.glyphs.count { it.label == EQUALS }
            if (eqs > 0 && line.trailingEquals == null) return "Ya hay algo escrito después del = (leí: ${line.text})."
            when (result) {
                is LineResult.Failure -> return "Leí «${line.text}», pero: ${result.error.message}."
                is LineResult.Value -> when {
                    trivial(line.text, result.value) ->
                        return "«${pretty(line.text)}» ya está lo más simplificado posible: las letras sin valor quedan como están."
                    !result.value.hasLetters() && result.value !is TupleValue && shown(result.value) == null ->
                        return "El resultado de «${line.text}» es muy largo para escribirlo."
                }
                else -> return "Leí «${line.text}»: no es una cuenta para resolver."
            }
        }
        return "No se pudo escribir el resultado con los patrones disponibles."
    }

    /**
     * Si las ecuaciones de [selected] no tienen solución real pero sí con números imaginarios: «Calcular»
     * ofrece resolverlas con imaginarios ([calculate] con complex = true).
     */
    fun onlyComplex(strokes: List<InkStroke>, selected: Set<String>, patterns: List<InkPattern>): Boolean {
        val byId = patterns.associateBy { it.id }
        val pool = pool(strokes, selected)
        val lines = lines(strokes, byId, only = pool)
        val (equations, context) = equations(lines, lines(strokes.filter { it.id !in pool }, byId))
        if (equations.isEmpty()) return false
        return (solveEquations(equations.map { lines[it].text }, context, complex = false) as? Outcome.Failed)?.complexOnly == true
    }

    /** Texto que se dibuja para un valor: entero, fracción (numerador / denominador), múltiplo de π o decimal. */
    internal data class Shown(val negative: Boolean, val numerator: String, val denominator: String? = null)

    internal fun shown(value: MathValue): Shown? {
        if (value is ExactValue) {
            if (value.isZero) return Shown(false, "0")
            val (k, c) = value.monomial ?: return decimal(value)
            if (k == 0 || k == 1) {
                val pi = if (k == 1) "π" else ""
                val n = c.num.abs().toString()
                val num = if (k == 1 && n == "1") pi else n + pi
                val shown = Shown(c.signum < 0, num, c.den.toString().takeIf { !c.isInteger })
                return shown.takeIf { it.numerator.length <= MAX_DIGITS && (it.denominator?.length ?: 0) <= MAX_DIGITS } ?: decimal(value)
            }
        }
        return decimal(value)
    }

    private fun decimal(value: MathValue): Shown? {
        val x = value.toDouble()
        if (!x.isFinite()) return null
        val text = BigDecimal(abs(x)).round(MathContext(8)).stripTrailingZeros().toPlainString().replace('.', ',')
        return Shown(x < 0, text).takeIf { text.length <= MAX_DIGITS }
    }

    private const val MAX_DIGITS = 14

    private fun isFlat(g: Glyph) = g.bounds.height < g.bounds.width * 0.4f

    /** Signos centrados (+, ×, =…): no marcan la altura del renglón ([AutoAlign.isSign]). */
    private fun isSign(g: Glyph) = AutoAlign.isSignName(g.label)

    private fun median(values: List<Float>): Float? = values.sorted().let { it.getOrNull(it.size / 2) }

    /** Partes de un resultado con letras o de un par, para dibujarlo. */
    private sealed interface Piece {
        /** Cifras, coma decimal, π y letras. */
        data class Text(val text: String) : Piece
        /** Exponente: chico y arriba. */
        data class Sup(val text: String) : Piece
        data class Frac(val num: List<Piece>, val den: List<Piece>) : Piece
        /** «+» o «−» entre términos; [unary] = el signo del primero, sin espacio antes. */
        data class Sign(val plus: Boolean, val unary: Boolean = false) : Piece
        data object Open : Piece
        data object Close : Piece
        /** La coma entre los elementos de un par (o entre soluciones). */
        data object Sep : Piece
        /** «=» entre una letra y su solución. */
        data object Eq : Piece
        /** Raíz cuadrada sobre [inner]. */
        data class Root(val inner: List<Piece>) : Piece
    }

    private fun pieces(v: MathValue): List<Piece>? = when (v) {
        is TupleValue -> {
            val out = mutableListOf<Piece>(Piece.Open)
            for ((i, item) in v.items.withIndex()) {
                if (i > 0) out += Piece.Sep
                val p = pieces(item)
                if (p == null) { out.clear(); break }
                out += p
            }
            if (out.isEmpty()) null else out + Piece.Close
        }
        is SymbolicValue -> {
            val out = mutableListOf<Piece>()
            var ok = true
            for ((i, t) in v.ordered.withIndex()) {
                val negative = SymbolicValue.sign(t.second) < 0
                if (i == 0 && negative) out += Piece.Sign(plus = false, unary = true)
                if (i > 0) out += Piece.Sign(plus = !negative)
                val p = term(t.first, SymbolicValue.magnitude(t.second))
                if (p == null) { ok = false; break }
                out += p
            }
            out.takeIf { ok }
        }
        is QuotientValue -> {
            val q = if (v.quotient.sameAs(ExactValue.ZERO)) emptyList() else pieces(v.quotient)
            val negative = (v.remainder as? SymbolicValue)?.ordered?.firstOrNull()?.let { SymbolicValue.sign(it.second) < 0 }
                ?: (v.remainder.toDouble() < 0)
            val r = pieces(if (negative) SymbolicValue.negate(v.remainder) else v.remainder)
            val d = pieces(v.divisor)
            if (q == null || r == null || d == null) null
            else q + (if (q.isEmpty()) listOfNotNull(Piece.Sign(false, unary = true).takeIf { negative }) else listOf(Piece.Sign(!negative))) + Piece.Frac(r, d)
        }
        else -> shown(v)?.let { n ->
            val sign = if (n.negative) listOf(Piece.Sign(plus = false, unary = true)) else emptyList()
            sign + if (n.denominator == null) Piece.Text(n.numerator) else Piece.Frac(listOf(Piece.Text(n.numerator)), listOf(Piece.Text(n.denominator)))
        }
    }

    /** Un término con coeficiente positivo: «3x²», ½x (fracción apilada), 2 sobre x. */
    private fun term(m: Map<String, Int>, c: MathValue): List<Piece>? {
        fun letters(up: Boolean) = SymbolicValue.letters(m).filter { (it.second > 0) == up }.flatMap { (l, e) ->
            listOf(Piece.Text(l)) + if (abs(e) > 1) listOf(Piece.Sup(abs(e).toString())) else emptyList()
        }
        val (num, den) = when (c) {
            is ExactValue -> c.rational?.let { it.num.toString() to it.den.toString() } ?: return null
            else -> (decimal(c)?.numerator ?: return null) to "1"
        }
        if (num.length > MAX_DIGITS || den.length > MAX_DIGITS) return null
        val up = letters(true)
        val top = (if (num != "1" || up.isEmpty()) listOf(Piece.Text(num)) else emptyList()) + up
        val bottom = (if (den != "1") listOf(Piece.Text(den)) else emptyList()) + letters(false)
        return if (bottom.isEmpty()) top else listOf(Piece.Frac(top, bottom))
    }

    /** Dibuja con los patrones: los del usuario si tiene uno con ese nombre, si no los de la biblioteca. */
    private class Writer(patterns: List<InkPattern>, private val ids: IdGenerator) {
        private val user = patterns.filter { it.enabled && !PresetPatterns.isSign(it) }.associateBy { it.name.trim() }
        private val presets = (PresetPatterns.signs() + patterns.filter { it.id.startsWith(PresetPatterns.PREFIX) }).associateBy { it.name.trim() }
        private val byId = patterns.associateBy { it.id }

        /** Patrones de letras por la letra que escriben («Equis» → x, «alfa» → α); los del usuario primero. */
        private val letters: Map<String, InkPattern> = (
            patterns.filter { it.enabled && it.outputInk.isNotEmpty() && !PresetPatterns.isSign(it) } +
                presets.values.filter { it.outputInk.isNotEmpty() }
            ).mapNotNull { p -> letterOf(p)?.let { it to p } }.distinctBy { it.first }.toMap()

        private fun letterOf(p: InkPattern): String? =
            symbol(p)?.let { MathLayoutConverter.spelling(it) }?.takeIf { it.length == 1 && it[0].isLetter() && it != "π" }

        private fun pattern(name: String): InkPattern? = user[name]?.takeIf { it.outputInk.isNotEmpty() } ?: presets[name]

        /** La letra como la escribió el usuario en este renglón; si no está, su patrón. */
        private fun letter(l: String, row: List<Glyph>): InkPattern? =
            row.firstNotNullOfOrNull { g ->
                byId[g.strokes.first().patternId]?.takeIf { it.outputInk.isNotEmpty() && letterOf(it) == l }
            } ?: letters[l]

        /** «=» después de la cuenta, con su estilo; null si no hay patrón de igual. */
        fun equals(row: List<Glyph>): List<InkStroke>? {
            val p = pattern(EQUALS) ?: return null
            val h = height(row)
            val last = row.maxBy { it.bounds.right }
            val style = last.strokes.first().style
            return glyph(p, last.bounds.right + h * 0.35f, centerY(row), h, style, ids.next())?.strokes
        }

        /** [pieces] después del «=» [eq] del renglón [row]. */
        fun after(pieces: List<Piece>, eq: Glyph, row: List<Glyph>, color: Int): List<InkStroke> {
            val h = height(row)
            val style = eq.strokes.first().style.copy(color = color)
            return draw(pieces, eq.bounds.right + h * 0.35f, eq.bounds.centerY, h, style, row) ?: emptyList()
        }

        /** [pieces] en el renglón de abajo de [rows] (las ecuaciones resueltas), alineado a su izquierda. */
        fun under(pieces: List<Piece>, rows: List<List<Glyph>>, color: Int): List<InkStroke> {
            val all = rows.flatten()
            val last = rows.maxBy { r -> r.maxOf { it.bounds.bottom } }
            val h = height(last)
            val box = all.map { it.bounds }.reduce(Bounds::union)
            val style = all.first().strokes.first().style.copy(color = color)
            return draw(pieces, box.left, box.bottom + h * 1.15f, h, style, all) ?: emptyList()
        }

        fun value(value: MathValue, eq: Glyph, row: List<Glyph>, color: Int): List<InkStroke> {
            val h = height(row)
            val style = eq.strokes.first().style.copy(color = color)
            val cy = eq.bounds.centerY
            if (value.hasLetters() || value is TupleValue) {
                val pieces = pieces(value) ?: return emptyList()
                return draw(pieces, eq.bounds.right + h * 0.35f, cy, h, style, row) ?: emptyList()
            }
            val shown = shown(value) ?: return emptyList()
            var x = eq.bounds.right + h * 0.35f
            val out = mutableListOf<InkStroke>()
            if (shown.negative) {
                val minus = pattern("Menos")
                out += minus?.let { glyph(it, x, cy, h * 0.55f, style, ids.next(), flatWidth = h * 0.5f) }?.strokes ?: return emptyList()
                x = out.bounds().right + h * 0.15f
            }
            if (shown.denominator == null) {
                out += text(shown.numerator, x, cy, h, style) ?: return emptyList()
                return out
            }
            // Fracción apilada: numerador y denominador más chicos, centrados sobre la barra.
            val small = h * 0.62f
            val gap = h * 0.12f
            val num = text(shown.numerator, x, cy - gap - small / 2f, small, style) ?: return emptyList()
            val den = text(shown.denominator, x, cy + gap + small / 2f, small, style) ?: return emptyList()
            val width = maxOf(num.bounds().width, den.bounds().width)
            val barLeft = x
            val barRight = x + width + h * 0.2f
            fun centered(part: List<InkStroke>): List<InkStroke> {
                val dx = (barLeft + barRight) / 2f - part.bounds().centerX
                return part.map { it.transformed(Affine.translate(dx, 0f), scaleWidth = false) }
            }
            out += centered(num) + centered(den)
            out += InkStroke(ids.next(), listOf(InkPoint(barLeft, cy), InkPoint(barRight, cy)), style.color, style.width,
                groupId = ids.next(), patternId = PresetPatterns.PREFIX + "barra", brush = style.brush)
            return out
        }

        /** [pieces] a partir de [left], con la línea media en [cy] y cifras de alto [h]; null si falta un patrón. */
        private fun draw(pieces: List<Piece>, left: Float, cy: Float, h: Float, style: InkStyle, row: List<Glyph>): List<InkStroke>? {
            val out = mutableListOf<InkStroke>()
            var x = left
            for (p in pieces) {
                if (p is Piece.Sign && !p.unary) x += h * 0.2f
                val part: List<InkStroke> = when (p) {
                    is Piece.Text -> text(p.text, x, cy, h, style, row)
                    is Piece.Sup -> text(p.text, x - h * 0.04f, cy - h * 0.45f, h * 0.55f, style, row)
                    is Piece.Frac -> fraction(p, x, cy, h, style, row)
                    is Piece.Sign -> if (p.plus) {
                        pattern("Más")?.let { glyph(it, x, cy, if (it.id.startsWith(PresetPatterns.PREFIX)) h else h * 0.55f, style, ids.next()) }?.strokes
                    } else {
                        pattern("Menos")?.let { glyph(it, x, cy, h * 0.55f, style, ids.next(), flatWidth = h * 0.5f) }?.strokes
                    }
                    Piece.Open, Piece.Close -> pattern(if (p == Piece.Open) "Abre paréntesis" else "Cierra paréntesis")
                        ?.let { glyph(it, x, cy, if (it.id.startsWith(PresetPatterns.PREFIX)) h * 1.15f else h * 1.2f, style, ids.next()) }?.strokes
                    Piece.Sep -> comma(x, cy, h, style)
                    Piece.Eq -> pattern(EQUALS)?.let { glyph(it, x + h * 0.2f, cy, h, style, ids.next()) }?.strokes
                    is Piece.Root -> root(p, x, cy, h, style, row)
                } ?: return null
                if (part.isEmpty()) return null
                out += part
                x = part.bounds().right + h * when (p) {
                    is Piece.Sign -> if (p.unary) 0.15f else 0.32f
                    // Entre soluciones (x = 2 , x = 3) más aire que en un par.
                    Piece.Sep -> if (pieces.any { it == Piece.Eq }) 0.9f else 0.3f
                    Piece.Eq -> 0.32f
                    else -> 0.12f
                }
            }
            return out
        }

        /** √ dibujada a mano alzada sobre lo de adentro. */
        private fun root(r: Piece.Root, x: Float, cy: Float, h: Float, style: InkStyle, row: List<Glyph>): List<InkStroke>? {
            val inner = draw(r.inner, x + h * 0.5f, cy, h * 0.9f, style, row) ?: return null
            val b = inner.bounds()
            val top = b.top - h * 0.12f
            val mark = InkStroke(ids.next(), listOf(
                InkPoint(x, cy + h * 0.05f), InkPoint(x + h * 0.12f, cy - h * 0.02f), InkPoint(x + h * 0.26f, b.bottom + h * 0.05f),
                InkPoint(x + h * 0.42f, top), InkPoint(b.right + h * 0.08f, top),
            ), style.color, style.width, groupId = ids.next(), brush = style.brush)
            return inner + mark
        }

        /** Una «i» de imaginario cuando el usuario no tiene patrón para la i: palito y punto. */
        private fun imaginaryI(x: Float, cy: Float, h: Float, style: InkStyle): List<InkStroke> {
            val g = ids.next()
            val bottom = cy + h * 0.5f
            return listOf(
                InkStroke(ids.next(), listOf(InkPoint(x + h * 0.1f, bottom - h * 0.55f), InkPoint(x + h * 0.1f, bottom)), style.color, style.width, groupId = g, brush = style.brush),
                InkStroke(ids.next(), listOf(InkPoint(x + h * 0.1f, bottom - h * 0.78f), InkPoint(x + h * 0.1f, bottom - h * 0.74f)), style.color, style.width * 1.4f, groupId = g, brush = style.brush),
            )
        }

        /** Fracción apilada: numerador y denominador más chicos, centrados sobre la barra. */
        private fun fraction(f: Piece.Frac, x: Float, cy: Float, h: Float, style: InkStyle, row: List<Glyph>): List<InkStroke>? {
            val small = h * 0.62f
            val gap = h * 0.12f
            val num = draw(f.num, x, cy - gap - small / 2f, small, style, row) ?: return null
            val den = draw(f.den, x, cy + gap + small / 2f, small, style, row) ?: return null
            val barRight = x + maxOf(num.bounds().width, den.bounds().width) + h * 0.2f
            fun centered(part: List<InkStroke>): List<InkStroke> {
                val dx = (x + barRight) / 2f - part.bounds().centerX
                return part.map { it.transformed(Affine.translate(dx, 0f), scaleWidth = false) }
            }
            return centered(num) + centered(den) + InkStroke(ids.next(), listOf(InkPoint(x, cy), InkPoint(barRight, cy)), style.color, style.width,
                groupId = ids.next(), patternId = PresetPatterns.PREFIX + "barra", brush = style.brush)
        }

        /** Una cadena de cifras (y π o coma) a partir de [left], centrada en [cy]. */
        private fun text(s: String, left: Float, cy: Float, h: Float, style: InkStyle, row: List<Glyph> = emptyList()): List<InkStroke>? {
            val out = mutableListOf<InkStroke>()
            var x = left
            for (c in s) {
                val strokes = when {
                    c == ',' -> comma(x, cy, h, style)
                    c.isLetter() && c != 'π' -> {
                        // Una letra apoyada en la línea de las cifras, un poco más baja que ellas.
                        val p = letter(c.toString(), row) ?: if (c == 'i') null else return null
                        if (p == null) {
                            imaginaryI(x, cy, h, style).also { out += it; x = it.bounds().right + h * 0.12f }
                            continue
                        }
                        val preset = p.id.startsWith(PresetPatterns.PREFIX)
                        val size = if (preset) h else h * 0.8f / AutoAlign.floor(p)
                        val center = if (preset) cy else cy + h / 2f - size * AutoAlign.floor(p) + size / 2f
                        glyph(p, x, center, size, style, ids.next())?.strokes ?: return null
                    }
                    else -> {
                        val p = pattern(if (c == 'π') "pi" else c.toString()) ?: return null
                        glyph(p, x, cy, h, style, ids.next())?.strokes ?: return null
                    }
                }
                out += strokes
                x = strokes.bounds().right + h * 0.12f
            }
            return out
        }

        /** La coma del usuario si tiene un patrón «Coma»; si no, un trazo corto. */
        private fun comma(x: Float, cy: Float, h: Float, style: InkStyle): List<InkStroke> {
            val top = cy + h * 0.38f
            user["Coma"]?.takeIf { it.outputInk.isNotEmpty() }?.let { p ->
                val src = p.outputInk.bounds()
                val ch = h * 0.3f
                val w = if (src.height > 0.5f) ch * src.width / src.height else ch * 0.4f
                return InkReplacement.place(p, Bounds(x, top - ch * 0.3f, x + w, top + ch * 0.7f), ids, style = style, elastic = false)
            }
            return listOf(InkStroke(ids.next(), listOf(InkPoint(x + h * 0.08f, top), InkPoint(x + h * 0.02f, top + h * 0.2f)),
                style.color, style.width, groupId = ids.next(), brush = style.brush))
        }

        /**
         * Salida de [p] con su borde izquierdo en [left]. Los signos de la biblioteca viven en una caja de 100
         * de alto con la misma línea de base: se escalan con esa caja, así el «−» queda a media altura. Los del
         * usuario se ajustan a [h] de alto (o a [flatWidth] de ancho si son planos).
         */
        private fun glyph(p: InkPattern, left: Float, cy: Float, h: Float, style: InkStyle, group: String, flatWidth: Float? = null): Glyph? {
            if (p.outputInk.isEmpty()) return null
            val src = p.outputInk.bounds()
            val preset = p.id.startsWith(PresetPatterns.PREFIX)
            val k = when {
                preset && flatWidth != null && src.width > 0.5f -> flatWidth / src.width
                preset -> h / 100f
                src.height > src.width * 0.4f && src.height > 0.5f -> h / src.height
                src.width > 0.5f -> (flatWidth ?: h * 0.5f) / src.width
                else -> 1f
            }
            val srcCy = if (preset) 50f else src.centerY
            val t = Affine.translate(-src.left, -srcCy).then(Affine(a = k, d = k)).then(Affine.translate(left, cy))
            val strokes = p.outputInk.map { s ->
                s.transformed(t, scaleWidth = false).copy(
                    id = ids.next(), groupId = group, patternId = p.id, color = style.color,
                    width = (s.width * style.width / DEFAULT_INK_WIDTH).coerceIn(0.5f, 60f),
                    brush = if (style.brush == Brush.HIGHLIGHTER) Brush.PEN else style.brush,
                )
            }
            return Glyph(p.name.trim(), strokes.bounds(), strokes)
        }

        /** Lo que no es parte de una fracción apilada, y las barras de las fracciones. */
        private fun inline(row: List<Glyph>): Pair<List<Glyph>, Set<Glyph>> {
            val bars = fractions(row, unit(row) ?: return row to emptySet())
            val stacked = (bars.keys + bars.values.flatten()).toSet()
            return row.filter { it !in stacked } to bars.keys
        }

        private fun height(row: List<Glyph>): Float =
            median(inline(row).first.filter { !isFlat(it) && !isSign(it) }.map { it.bounds.height })
                ?: median(row.filter { !isFlat(it) && !isSign(it) }.map { it.bounds.height })
                ?: median(row.filter { !isFlat(it) && it.label != EQUALS }.map { it.bounds.height })
                ?: row.maxOf { it.bounds.width } * 1.4f

        private fun centerY(row: List<Glyph>): Float {
            val (plain, bars) = inline(row)
            return median(plain.filter { !isFlat(it) && !isSign(it) }.map { it.bounds.centerY })
                ?: median(bars.map { it.bounds.centerY })
                ?: median(row.filter { !isFlat(it) && !isSign(it) }.map { it.bounds.centerY })
                ?: median(row.filter { !isFlat(it) }.map { it.bounds.centerY }) ?: row.first().bounds.centerY
        }
    }
}
