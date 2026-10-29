package io.trazo.core

import io.trazo.core.math.ApproxValue
import io.trazo.core.math.ExactValue
import io.trazo.core.math.LineResult
import io.trazo.core.math.MathException
import io.trazo.core.math.MathParser
import io.trazo.core.math.MathValue
import io.trazo.core.math.Statement
import io.trazo.core.math.VariableGraph
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
        null -> label(p.name)
        "" -> null
        else -> u
    }

    /** Trazos de patrones que no cuentan en las cuentas: estorban como la escritura a mano. */
    private fun ignored(s: InkStroke, patterns: Map<String, InkPattern>) =
        s.patternId != null && patterns[s.patternId]?.let { symbol(it) == null } == true

    /** Nombres de signos sin tilde ni mayúsculas («mas», «IGUAL») como los lee el reconocedor. */
    private val SIGN_NAMES = listOf(
        "Más", "Menos", "Por", "Multiplicación", "Dividido", "División", "Igual", "Barra", "Fracción",
        "Barra de fracción", "Raíz", "Pi", "Abre paréntesis", "Cierra paréntesis", "Paréntesis abre",
        "Paréntesis cierra", "Coma", "Punto",
    ).associateBy { fold(it) } + mapOf("equis" to "x", "ye" to "y", "i griega" to "y", "incognita" to "x")

    private fun fold(name: String) = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    internal fun label(name: String): String = SIGN_NAMES[fold(name)] ?: name.trim()

    /** Renglones: barrido de arriba hacia abajo; un signo entra al renglón si empieza antes de que termine. */
    fun rows(glyphs: List<Glyph>): List<List<Glyph>> {
        if (glyphs.isEmpty()) return emptyList()
        val unit = median(glyphs.filter { !isFlat(it) }.map { it.bounds.height }) ?: median(glyphs.map { it.bounds.height }) ?: 1f
        val out = mutableListOf<MutableList<Glyph>>()
        var bottom = Float.NEGATIVE_INFINITY
        for (g in glyphs.sortedBy { it.bounds.top }) {
            if (out.isEmpty() || g.bounds.top > bottom + ROW_GAP * unit) {
                out += mutableListOf(g)
                bottom = g.bounds.bottom
            } else {
                out.last() += g
                bottom = maxOf(bottom, g.bounds.bottom)
            }
        }
        return out.flatMap { r -> split(r.sortedBy { it.bounds.left }) }
    }

    /**
     * Separa un renglón donde hay un hueco de más de [SPLIT_GAP] altos de signo: son cuentas distintas
     * escritas a la misma altura (por ejemplo, una a cada lado de la hoja).
     */
    private fun split(row: List<Glyph>): List<List<Glyph>> {
        val unit = median(row.filter { !isFlat(it) }.map { it.bounds.height }) ?: return listOf(row)
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

    private fun lines(strokes: List<InkStroke>, patterns: Map<String, InkPattern>): List<Line> {
        val handwriting = strokes.filter { (it.patternId == null || ignored(it, patterns)) && it.brush != Brush.HIGHLIGHTER }
        return rows(glyphs(strokes, patterns)).map { row ->
            val box = row.map { it.bounds }.reduce(Bounds::union)
            val last = row.maxByOrNull { it.bounds.right }!!
            val trailing = last.takeIf { it.label == EQUALS && row.none { g -> g !== it && g.bounds.centerX > it.bounds.right } }
            val unit = median(row.filter { !isFlat(it) }.map { it.bounds.height }) ?: box.height
            // Tinta a mano dentro de la cuenta o en el lugar del resultado: no se calcula este renglón.
            val reach = Bounds(box.left, box.top, box.right + if (trailing != null) unit * 3f else 0f, box.bottom)
            val blocked = handwriting.any { s -> val b = s.bounds; reach.contains(b.centerX, b.centerY) }
            val text = if (blocked) "" else convert(row)
            Line(row, text, trailing.takeIf { !blocked }, blocked)
        }
    }

    private fun convert(row: List<Glyph>): String = try {
        MathLayoutConverter.convert(row.map { LayoutSymbol(it.label, it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) })
            .lines().singleOrNull().orEmpty()
    } catch (e: MathException) {
        ""
    }

    /** Valores de cada renglón, en el orden de [lines]; null donde no hay un resultado para mostrar. */
    private fun values(lines: List<Line>): List<MathValue?> =
        evaluate(lines.map { it.text }).map { (it as? LineResult.Value)?.value }

    /**
     * Como [VariableGraph.evaluate], pero antes despeja las incógnitas: una igualdad con una sola incógnita
     * sin definir (`2x = 5`) se resuelve si es lineal, y su valor vale para la hoja como si estuviera
     * escrito `x = 5/2`. Se repite para encadenar (`2x = 5`, `y = x + 1`, `3z = y`).
     */
    internal fun evaluate(texts: List<String>): List<LineResult> =
        VariableGraph.evaluate(texts + solve(texts).map { it.text }).take(texts.size)

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
        val byId = patterns.associateBy { it.id }
        val lines = lines(strokes, byId)
        if (lines.none { l -> l.glyphs.any { it.label == EQUALS } }) return null
        val texts = lines.map { it.text }
        val solved = solve(texts)
        val all = texts + solved.map { it.text }
        val results = VariableGraph.evaluate(all)
        val writer = Writer(patterns, IdGenerator.sequential("explain"))
        for ((i, line) in lines.withIndex()) {
            if (line.text.isEmpty() || line.glyphs.none { it.label == EQUALS }) continue
            val result = results[i]
            val box = line.glyphs.map { it.bounds }.reduce(Bounds::union)
            val shownInk = if (line.trailingEquals != null && result is LineResult.Value) writer.value(result.value, line.trailingEquals, line.glyphs, 0) else emptyList()
            val target = when {
                shownInk.isNotEmpty() && wholeLine -> box.union(shownInk.bounds())
                shownInk.isNotEmpty() -> shownInk.bounds().union(line.trailingEquals!!.bounds)
                wholeLine -> box
                else -> continue
            }
            if (x < target.left - slop || x > target.right + slop || y < target.top - slop || y > target.bottom + slop) continue
            val main = when (result) {
                is LineResult.Value -> "${pretty(line.text)} ${result.value.display()}"
                is LineResult.Assignment -> pretty(line.text)
                is LineResult.Check -> pretty(line.text) + if (result.holds) "  ✓" else "  ✗ (${result.left.display()} ≠ ${result.right.display()})"
                is LineResult.Failure -> "${pretty(line.text)}  → ${result.error.message}"
                LineResult.Empty -> continue
            }
            val steps = mutableListOf(main)
            val value = (result as? LineResult.Value)?.value
            if (value != null && value.display().let { it.contains('/') || it.contains('π') }) {
                decimal(value)?.let { steps += "≈ " + (if (it.negative) "−" else "") + it.numerator }
            }
            steps += sources(i, texts, solved, results)
            return Explanation(target, steps)
        }
        return null
    }

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

    /** La cuenta como texto legible: «2·x + 1 =». */
    internal fun pretty(text: String): String {
        val out = StringBuilder()
        for ((i, c) in text.withIndex()) {
            val prev = text.getOrNull(i - 1)
            val binary = c in "+=" || (c == '-' && prev != null && (prev.isLetterOrDigit() || prev == ')' || prev == 'π'))
            when {
                c == '*' -> out.append('·')
                c == ' ' -> Unit
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
        val lines = lines(strokes, byId).filter { l -> l.glyphs.any { g -> g.ids.any { it in selected } } }
        if (lines.isEmpty()) return "No hay una cuenta en la selección."
        val values = evaluate(lines.map { it.text })
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
                is LineResult.Value -> if (shown(result.value) == null) return "El resultado de «${line.text}» es muy largo para escribirlo."
                else -> return "Leí «${line.text}»: no es una cuenta para resolver."
            }
        }
        return "No se pudo escribir el resultado con los patrones disponibles."
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

    private fun median(values: List<Float>): Float? = values.sorted().let { it.getOrNull(it.size / 2) }

    /** Dibuja con los patrones: los del usuario si tiene uno con ese nombre, si no los de la biblioteca. */
    private class Writer(patterns: List<InkPattern>, private val ids: IdGenerator) {
        private val user = patterns.filter { it.enabled && !PresetPatterns.isSign(it) }.associateBy { it.name.trim() }
        private val presets = (PresetPatterns.signs() + patterns.filter { it.id.startsWith(PresetPatterns.PREFIX) }).associateBy { it.name.trim() }

        private fun pattern(name: String): InkPattern? = user[name]?.takeIf { it.outputInk.isNotEmpty() } ?: presets[name]

        /** «=» después de la cuenta, con su estilo; null si no hay patrón de igual. */
        fun equals(row: List<Glyph>): List<InkStroke>? {
            val p = pattern(EQUALS) ?: return null
            val h = height(row)
            val last = row.maxBy { it.bounds.right }
            val style = last.strokes.first().style
            return glyph(p, last.bounds.right + h * 0.35f, centerY(row), h, style, ids.next())?.strokes
        }

        fun value(value: MathValue, eq: Glyph, row: List<Glyph>, color: Int): List<InkStroke> {
            val shown = shown(value) ?: return emptyList()
            val h = height(row)
            val style = eq.strokes.first().style.copy(color = color)
            val cy = eq.bounds.centerY
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

        /** Una cadena de cifras (y π o coma) a partir de [left], centrada en [cy]. */
        private fun text(s: String, left: Float, cy: Float, h: Float, style: InkStyle): List<InkStroke>? {
            val out = mutableListOf<InkStroke>()
            var x = left
            for (c in s) {
                val strokes = if (c == ',') {
                    comma(x, cy, h, style)
                } else {
                    val p = pattern(if (c == 'π') "pi" else c.toString()) ?: return null
                    glyph(p, x, cy, h, style, ids.next())?.strokes ?: return null
                }
                out += strokes
                x = strokes.bounds().right + h * 0.12f
            }
            return out
        }

        private fun comma(x: Float, cy: Float, h: Float, style: InkStyle): List<InkStroke> {
            val top = cy + h * 0.38f
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

        private fun height(row: List<Glyph>): Float =
            median(row.filter { !isFlat(it) && it.label != EQUALS }.map { it.bounds.height })
                ?: row.maxOf { it.bounds.width } * 1.4f

        private fun centerY(row: List<Glyph>): Float =
            median(row.filter { !isFlat(it) }.map { it.bounds.centerY }) ?: row.first().bounds.centerY
    }
}
