package io.trazo.core

import io.trazo.core.math.ExactValue
import io.trazo.core.math.LineResult
import io.trazo.core.math.MathException
import io.trazo.core.math.MathValue
import io.trazo.core.math.VariableGraph
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

    /** Un signo reconocido: los trazos de una misma salida de patrón. */
    data class Glyph(val label: String, val bounds: Bounds, val strokes: List<InkStroke>) {
        val ids: List<String> get() = strokes.map { it.id }
    }

    /** Signos reconocidos de [strokes] con un patrón conocido. */
    fun glyphs(strokes: List<InkStroke>, patterns: Map<String, InkPattern>): List<Glyph> =
        strokes.filter { it.patternId != null && it.brush != Brush.HIGHLIGHTER }
            .groupBy { it.groupId ?: it.id }
            .mapNotNull { (_, g) -> patterns[g.first().patternId]?.let { Glyph(it.name.trim(), g.bounds(), g) } }

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
        return out.map { r -> r.sortedBy { it.bounds.left } }
    }

    /** Un renglón listo para calcular: su texto y, si termina en «=», ese signo. */
    private class Line(val glyphs: List<Glyph>, val text: String, val trailingEquals: Glyph?)

    private fun lines(strokes: List<InkStroke>, patterns: Map<String, InkPattern>): List<Line> {
        val handwriting = strokes.filter { it.patternId == null && it.brush != Brush.HIGHLIGHTER }
        return rows(glyphs(strokes, patterns)).map { row ->
            val box = row.map { it.bounds }.reduce(Bounds::union)
            val last = row.maxByOrNull { it.bounds.right }!!
            val trailing = last.takeIf { it.label == EQUALS && row.none { g -> g !== it && g.bounds.centerX > it.bounds.right } }
            val unit = median(row.filter { !isFlat(it) }.map { it.bounds.height }) ?: box.height
            // Tinta a mano dentro de la cuenta o en el lugar del resultado: no se calcula este renglón.
            val reach = Bounds(box.left, box.top, box.right + if (trailing != null) unit * 3f else 0f, box.bottom)
            val blocked = handwriting.any { s -> val b = s.bounds; reach.contains(b.centerX, b.centerY) }
            val text = if (blocked) "" else convert(row)
            Line(row, text, trailing.takeIf { !blocked })
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
        VariableGraph.evaluate(lines.map { it.text }).map { (it as? LineResult.Value)?.value }

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
