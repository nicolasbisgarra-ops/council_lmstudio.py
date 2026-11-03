package io.trazo.core

import io.trazo.core.math.ExactValue
import io.trazo.core.math.LineResult
import io.trazo.core.math.MathParser
import io.trazo.core.math.Poly
import io.trazo.core.math.Polynomials
import io.trazo.core.math.Root
import io.trazo.core.math.Statement
import io.trazo.core.math.VariableGraph
import io.trazo.core.math.hasLetters
import io.trazo.core.math.sameAs
import io.trazo.core.math.variables
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Una marca del Modo IA guardada en la hoja. El tipo va como texto (`CALCULATION`, `CONCEPT`,
 * `MISSING_STEP`, `NOTATION`, los nombres de `io.trazo.ai.MarkKind`): el motor no depende del módulo de red.
 */
@Serializable
data class AiNote(
    val id: String,
    /** Renglón numerado (desde 1) al que apunta, en el pedido que la generó. */
    val line: Int,
    val kind: String,
    val short: String,
    val detail: String = "",
    val fix: String = "",
    /** Caja de la tinta del renglón al marcarlo (si cambia, la marca se quita sola). */
    val bounds: Bounds,
    val strokeIds: List<String> = emptyList(),
    /** El motor de Trazo hizo la cuenta y no coincide con la marca: queda como «dudosa». */
    val disputed: Boolean = false,
    /** La agregó el motor (la IA no la vio): una igualdad sin letras que no se cumple. */
    val fromEngine: Boolean = false,
    /** La IA la marcó y el motor llegó a lo mismo por su cuenta: el error está comprobado. */
    val confirmed: Boolean = false,
    val reviewId: String = "",
    /** Huella del contenido de [strokeIds] al marcar (QA53); vacía en hojas viejas. */
    val fingerprint: String = "",
)

/** Una revisión hecha, para el historial de la hoja. */
@Serializable
data class AiReviewRecord(
    val id: String,
    val at: Long,
    val model: String,
    val correct: Boolean,
    val score: Double? = null,
    /** Solo lo elegido con el lazo. */
    val selection: Boolean = false,
    val lineCount: Int = 0,
    val notes: List<AiNote> = emptyList(),
    /** Caja de lo revisado: ahí va el tilde verde si no hubo errores. */
    val area: Bounds? = null,
    val strokeIds: List<String> = emptyList(),
    val fingerprint: String = "",
)

/** Lo del Modo IA en una hoja: las marcas visibles y el historial de revisiones. */
@Serializable
data class PageAi(
    val notes: List<AiNote> = emptyList(),
    val history: List<AiReviewRecord> = emptyList(),
) {
    /** Última revisión sin errores cuya tinta no cambió: se muestra el tilde verde con el puntaje. */
    fun verdict(strokes: List<InkStroke>): AiReviewRecord? {
        val last = history.lastOrNull() ?: return null
        if (!last.correct || notes.any { it.reviewId == last.id } || last.area == null) return null
        return last.takeIf { AiNotes.unchanged(last.strokeIds, last.area, strokes, last.fingerprint) }
    }

    fun without(noteId: String): PageAi = copy(notes = notes.filterNot { it.id == noteId })

    val isEmpty: Boolean get() = notes.isEmpty() && history.isEmpty()
}

object AiNotes {
    /** Se guardan a lo sumo estas revisiones por hoja (las más nuevas). */
    const val HISTORY_LIMIT = 30

    /** Un renglón numerado para el pedido: su texto («?» si el motor no lo leyó), su caja y sus trazos. */
    data class ReviewLine(val number: Int, val text: String, val bounds: Bounds, val strokeIds: List<String>, val fingerprint: String = "")

    /**
     * Huella del contenido de los trazos [ids] (puntos, grosor, pincel), independiente del orden: cambia si
     * se edita un trazo aunque conserve su id y la caja total no se mueva.
     */
    fun fingerprint(ids: Collection<String>, strokes: List<InkStroke>): String {
        val wanted = ids.toSet()
        var h = FNV_OFFSET
        fun mix(v: Long) { h = (h xor v) * FNV_PRIME }
        for (s in strokes.filter { it.id in wanted }.sortedBy { it.id }) {
            s.id.forEach { mix(it.code.toLong()) }
            mix(s.brush.ordinal.toLong())
            mix((s.width * 10f).roundToLong())
            mix(s.points.size.toLong())
            for (p in s.points) { mix((p.x * 10f).roundToLong()); mix((p.y * 10f).roundToLong()) }
        }
        return java.lang.Long.toHexString(h)
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    /**
     * Agrega una revisión: sus marcas reemplazan a las anteriores de los mismos renglones (por trazos en
     * común) y el historial guarda las últimas [HISTORY_LIMIT].
     */
    fun record(ai: PageAi?, review: AiReviewRecord): PageAi {
        val base = ai ?: PageAi()
        val touched = review.strokeIds.toSet()
        val kept = base.notes.filter { n -> n.strokeIds.none { it in touched } }
        return PageAi(kept + review.notes, (base.history + review).takeLast(HISTORY_LIMIT))
    }

    /**
     * Quita las marcas de los renglones que cambiaron (22): falta un trazo, se movió o hay tinta nueva
     * encima. El resaltador no cuenta.
     */
    fun prune(ai: PageAi?, strokes: List<InkStroke>): PageAi? {
        if (ai == null || ai.notes.isEmpty()) return ai
        val kept = ai.notes.filter { unchanged(it.strokeIds, it.bounds, strokes, it.fingerprint) }
        return if (kept.size == ai.notes.size) ai else ai.copy(notes = kept)
    }

    internal fun unchanged(ids: List<String>, bounds: Bounds, strokes: List<InkStroke>, fingerprint: String = ""): Boolean {
        if (ids.isEmpty()) return true
        val wanted = ids.toSet()
        val own = strokes.filter { it.id in wanted }
        if (own.size != wanted.size) return false
        val now = own.bounds()
        if (!close(now, bounds)) return false
        if (fingerprint.isNotEmpty() && fingerprint != fingerprint(wanted, own)) return false
        // Tinta nueva sobre el renglón (no la del resaltador): con el centro adentro o que lo cruce (tachar).
        val area = bounds.inflate(2f)
        return strokes.none { s -> s.id !in wanted && s.brush != Brush.HIGHLIGHTER && s.calc == null && covers(s, area) }
    }

    /** El trazo cae sobre [area]: su centro adentro o al menos la mitad de su largo la atraviesa. */
    private fun covers(s: InkStroke, area: Bounds): Boolean {
        val b = s.bounds
        if (area.contains(b.centerX, b.centerY)) return true
        if (b.right < area.left || b.left > area.right || b.bottom < area.top || b.top > area.bottom) return false
        var inside = 0f
        var total = 0f
        for (i in 1 until s.points.size) {
            val a = s.points[i - 1]
            val c = s.points[i]
            val d = kotlin.math.hypot(c.x - a.x, c.y - a.y)
            total += d
            if (area.contains((a.x + c.x) / 2f, (a.y + c.y) / 2f)) inside += d
        }
        return total > 0f && inside >= total / 2f
    }

    private fun close(a: Bounds, b: Bounds, eps: Float = 0.5f) =
        kotlin.math.abs(a.left - b.left) < eps && kotlin.math.abs(a.top - b.top) < eps &&
            kotlin.math.abs(a.right - b.right) < eps && kotlin.math.abs(a.bottom - b.bottom) < eps

    /** Qué dice el motor de una igualdad del renglón. */
    enum class Verdict { HOLDS, FAILS, UNKNOWN }

    /**
     * Resultado del motor por renglón. [verdict]: el renglón solo; si no se cumple, los dos lados como los
     * calcula Trazo. [step]: el renglón como paso de la resolución que viene del anterior; [stepDetail]
     * explica qué cambió si el paso está mal.
     */
    data class EngineCheck(
        val verdict: Verdict,
        val left: String = "",
        val right: String = "",
        val step: Verdict = Verdict.UNKNOWN,
        val stepDetail: String = "",
    ) {
        /** El motor da el renglón por bueno, solo o como paso. */
        val holds: Boolean get() = verdict == Verdict.HOLDS || step == Verdict.HOLDS
        /** El motor encontró un error en el renglón, solo o como paso. */
        val fails: Boolean get() = verdict == Verdict.FAILS || step == Verdict.FAILS
    }

    /**
     * La doble verificación (15), por renglón y por paso.
     *
     * - Solo: cada renglón con un «=» en el medio se evalúa aislado (sin despejar con los demás, que podría
     *   dar por buena una cuenta con su propio error). Una identidad (`2x + 3x = 5x`) es [Verdict.HOLDS]; una
     *   igualdad sin letras que no se cumple, [Verdict.FAILS].
     * - Paso: una ecuación en una sola letra se compara con la del renglón anterior (la misma letra): el paso
     *   está bien si las dos tienen las mismas soluciones (`2x + 3 = 7` → `2x = 4`) y mal si cambian
     *   (`2x = 4` → `x = 3`). Un renglón que empieza con «=» sigue la cuenta del anterior: su lado derecho
     *   tiene que valer lo mismo que el último lado de aquel (`2x + 3x` → `= 5x`).
     */
    fun check(lines: List<ReviewLine>): List<EngineCheck> {
        val alone = lines.map { checkAlone(it.text) }
        val height = lines.map { it.bounds.height }.sorted().let { it.getOrNull(it.size / 2) } ?: 0f
        return alone.mapIndexed { i, c ->
            val before = lines.getOrNull(i - 1) ?: return@mapIndexed c
            // Un renglón muy separado del anterior es otro ejercicio.
            if (height > 0f && lines[i].bounds.top - before.bounds.bottom > STEP_GAP * height) return@mapIndexed c
            val prev = before.text
            val (step, detail) = runCatching { checkStep(prev, lines[i].text) }.getOrNull() ?: return@mapIndexed c
            c.copy(step = step, stepDetail = detail)
        }
    }

    /** Hueco máximo, en altos de renglón, entre dos pasos de una misma resolución. */
    private const val STEP_GAP = 2.5f

    private fun checkAlone(text: String): EngineCheck {
        if (text == "?" || '=' !in text || text.trimEnd().endsWith("=") || text.trimStart().startsWith("=")) return EngineCheck(Verdict.UNKNOWN)
        return when (val r = runCatching { VariableGraph.evaluate(listOf(text), symbolic = true).first() }.getOrNull()) {
            is LineResult.Check -> when {
                r.holds -> EngineCheck(Verdict.HOLDS)
                text.none { it.isLetter() && it != 'π' } && !r.left.hasLetters() && !r.right.hasLetters() ->
                    EngineCheck(Verdict.FAILS, r.left.display(), r.right.display())
                else -> EngineCheck(Verdict.UNKNOWN)
            }
            else -> EngineCheck(Verdict.UNKNOWN)
        }
    }

    /** El paso de [prev] a [cur]; null si el motor no puede decir nada. */
    private fun checkStep(prev: String, cur: String): Pair<Verdict, String>? {
        if (prev == "?" || cur == "?" || prev.trimEnd().endsWith("=")) return null
        val c = cur.trim()
        if (c.startsWith("=")) {
            // Sigue la cuenta: «2x + 3x» / «= 5x», o «a = b» / «= c».
            val last = prev.split('=').last().trim().ifEmpty { return null }
            val rest = c.removePrefix("=").trim().split('=').first().trim().ifEmpty { return null }
            val d = VariableGraph.evaluate(listOf("($last)-($rest)"), symbolic = true).first() as? LineResult.Value ?: return null
            return if (d.value.sameAs(ExactValue.ZERO)) Verdict.HOLDS to ""
            else Verdict.FAILS to "«$rest» no es igual a «$last»: la diferencia es ${d.value.display()}."
        }
        // Después de una respuesta («x = 3») empieza otro ejercicio: no se compara con ella.
        if (answer(prev)) return null
        val a = equation(prev) ?: return null
        val b = equation(cur) ?: return null
        if (a.first != b.first) return null
        val ra = solutions(a.second) ?: return null
        val rb = solutions(b.second) ?: return null
        if (ra == rb) return Verdict.HOLDS to ""
        val x = a.first
        return Verdict.FAILS to "Del renglón anterior sale ${shown(x, ra)}; de este, ${shown(x, rb)}."
    }

    /** «x = 3»: una letra igualada a un número, la respuesta de un ejercicio. */
    private fun answer(text: String): Boolean =
        (runCatching { MathParser.parseStatement(text) }.getOrNull() as? Statement.Assign)?.expr?.variables()?.isEmpty() == true

    /** La letra y el polinomio (izquierda − derecha) de una ecuación en una sola letra; null si no lo es. */
    private fun equation(text: String): Pair<String, Poly>? {
        val vars = when (val st = runCatching { MathParser.parseStatement(text) }.getOrNull()) {
            is Statement.Check -> st.left.variables() + st.right.variables()
            // «x = 3» se lee como asignación: también es la ecuación final de una resolución.
            is Statement.Assign -> st.expr.variables() + st.name
            else -> return null
        }
        val letter = vars.filter { it != "π" }.toSet().singleOrNull() ?: return null
        val parts = text.split('=')
        if (parts.size != 2) return null
        val diff = VariableGraph.evaluate(listOf("(${parts[0]})-(${parts[1]})"), symbolic = true).first() as? LineResult.Value ?: return null
        val poly = Poly.of(diff.value, letter) ?: return null
        return letter to poly
    }

    /** Soluciones (reales y complejas) redondeadas para compararlas; null si el motor no puede resolverla. */
    private fun solutions(p: Poly): Set<Pair<Long, Long>>? {
        if (p.degree < 1) return null
        val roots = Polynomials.roots(p, complex = true) ?: return null
        return roots.map { r ->
            val (re, im) = when (r) {
                is Root.Exact -> {
                    val s = r.b.toDouble() * kotlin.math.sqrt(r.m.toDouble())
                    if (r.imaginary) r.a.toDouble() to s else r.a.toDouble() + s to 0.0
                }
                is Root.Approx -> r.re to r.im
            }
            Math.round(re * 1e6) to Math.round(im * 1e6)
        }.toSet()
    }

    private fun shown(x: String, roots: Set<Pair<Long, Long>>): String = when {
        roots.isEmpty() -> "que no tiene solución"
        else -> roots.sortedWith(compareBy({ it.first }, { it.second })).joinToString(" o ") { (re, im) ->
            fun num(v: Long) = java.math.BigDecimal(v).movePointLeft(6).stripTrailingZeros().toPlainString().replace('.', ',')
            "$x = " + if (im == 0L) num(re) else "${num(re)} ${if (im < 0) "−" else "+"} ${num(kotlin.math.abs(im))}i"
        }
    }

    /**
     * Cruza las marcas de la IA con el motor:
     * - una marca de cuenta en un renglón que el motor da por bueno (solo o como paso) queda «dudosa»;
     * - una marca en un renglón donde el motor también encontró el error queda «comprobada»;
     * - un renglón que el motor da por malo sin marca de la IA recibe una marca del motor.
     */
    fun crossCheck(notes: List<AiNote>, lines: List<ReviewLine>, ids: IdGenerator, reviewId: String): List<AiNote> {
        val checks = check(lines)
        val marked = notes.map { it.line }.toSet()
        val out = notes.map { n ->
            val c = checks.getOrNull(n.line - 1) ?: return@map n
            when {
                c.fails -> n.copy(confirmed = true, disputed = false)
                n.kind == "CALCULATION" && c.holds -> n.copy(disputed = true)
                else -> n
            }
        }
        val engine = lines.mapIndexedNotNull { i, l ->
            val c = checks[i]
            if (!c.fails || l.number in marked) return@mapIndexedNotNull null
            val (short, detail) = if (c.verdict == Verdict.FAILS) {
                "Trazo calcula otro resultado" to "Según el motor de Trazo, el lado izquierdo vale ${c.left} y el derecho ${c.right}."
            } else {
                "Este paso cambia la cuenta" to c.stepDetail
            }
            AiNote(
                id = ids.next(), line = l.number, kind = "CALCULATION", short = short, detail = detail,
                bounds = l.bounds, strokeIds = l.strokeIds, fromEngine = true, reviewId = reviewId, fingerprint = l.fingerprint,
            )
        }
        return out + engine
    }

    /** La revisión sale bien solo si el modelo la dio por buena y no queda ninguna marca firme (las dudosas no cuentan). */
    fun correct(modelSaysCorrect: Boolean, notes: List<AiNote>): Boolean = notes.none { !it.disputed } && (modelSaysCorrect || notes.isNotEmpty())

    /** Pasa la lectura del modelo («2·x − 3 = 7») a la forma que entiende el motor («2*x-3=7»). */
    fun normalizeReading(text: String): String? = text
        .replace('·', '*').replace('×', '*').replace('−', '-').replace('÷', '/').replace(':', '/')
        .replace("²", "^2").replace("³", "^3")
        .filterNot { it.isWhitespace() }
        .takeIf { it.isNotEmpty() && it.length <= 200 }

    /** Una marca como la devuelve el modelo, antes de ubicarla en la hoja. */
    data class Mark(val line: Int, val kind: String, val short: String, val detail: String = "", val fix: String = "")

    /** Ubica cada marca en su renglón; las de renglones que no existen se descartan. */
    fun place(marks: List<Mark>, lines: List<ReviewLine>, ids: IdGenerator, reviewId: String): List<AiNote> =
        marks.mapNotNull { m ->
            val l = lines.getOrNull(m.line - 1) ?: return@mapNotNull null
            AiNote(ids.next(), m.line, m.kind, m.short, m.detail, m.fix, l.bounds, l.strokeIds, reviewId = reviewId, fingerprint = l.fingerprint)
        }

    // ------------------------------------------------------------------ disposición en la hoja

    /** Alto de la letra de las marcas, en unidades de hoja (la hoja mide [Notebook.PAGE_WIDTH] de ancho). */
    const val TEXT_SIZE = 17f
    /** Ancho medio de un carácter de la letra manuscrita, en altos de letra. */
    const val CHAR_WIDTH = 0.5f
    private const val GAP = 14f
    private const val MIN_SIDE_WIDTH = 230f

    /** Dónde va cada marca: el óvalo alrededor del renglón, la nota (renglones de texto) y su caja. */
    data class Placed(val note: AiNote, val ring: Bounds, val label: Bounds, val textLines: List<String>)

    /**
     * Dispone las notas: al margen derecho del renglón si hay lugar, si no debajo; una nota no se pisa
     * con la anterior (se corre hacia abajo). Determinista: la vista, la goma y la exportación coinciden.
     */
    fun layout(notes: List<AiNote>, pageWidth: Float = Notebook.PAGE_WIDTH): List<Placed> {
        val placedLabels = mutableListOf<Bounds>()
        return notes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).map { n ->
            val ring = n.bounds.inflate(6f)
            val text = prefix(n) + n.short.ifBlank { "Revisar este renglón" }
            val side = pageWidth - ring.right - GAP - 12f
            val beside = side >= MIN_SIDE_WIDTH
            val left = if (beside) ring.right + GAP else max(12f, ring.left)
            val width = if (beside) side else pageWidth - left - 12f
            val wrapped = wrap(text, width)
            val w = min(width, wrapped.maxOf { it.length } * CHAR_WIDTH * TEXT_SIZE + 4f)
            val h = wrapped.size * TEXT_SIZE * 1.25f
            var top = if (beside) ring.centerY - h / 2f else ring.bottom + 6f
            var box = Bounds(left, top, left + w, top + h)
            while (true) {
                val hit = placedLabels.firstOrNull { it.overlaps(box) } ?: break
                top = hit.bottom + 4f
                box = Bounds(left, top, left + w, top + h)
            }
            placedLabels += box
            Placed(n, ring, box, wrapped)
        }
    }

    /** Dónde quedó [note] entre todas las de la hoja ([notes]); sola si ya no está. */
    fun placed(notes: List<AiNote>, note: AiNote, pageWidth: Float = Notebook.PAGE_WIDTH): Placed {
        val all = if (notes.any { it.id == note.id }) notes.map { if (it.id == note.id) note else it } else notes + note
        return layout(all, pageWidth).first { it.note.id == note.id }
    }

    /** La nota que hay en ([x], [y]) (sobre su texto), o null. */
    fun noteAt(notes: List<AiNote>, x: Float, y: Float, slop: Float = 0f): AiNote? =
        layout(notes).lastOrNull { it.label.inflate(slop).contains(x, y) }?.note

    private fun prefix(n: AiNote) = when {
        n.fromEngine -> "Trazo: "
        n.disputed -> "¿? "
        n.confirmed -> "✓ "
        else -> ""
    }

    /** Corta en palabras para que cada renglón entre en [width]. */
    fun wrap(text: String, width: Float): List<String> {
        val perLine = max(8, (width / (CHAR_WIDTH * TEXT_SIZE)).toInt())
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            if (cur.isNotEmpty() && cur.length + 1 + word.length > perLine) { out += cur.toString(); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word.take(perLine))
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.ifEmpty { listOf("") }
    }

    private fun Bounds.overlaps(o: Bounds) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}
