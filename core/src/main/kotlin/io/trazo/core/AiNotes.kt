package io.trazo.core

import io.trazo.core.math.LineResult
import io.trazo.core.math.VariableGraph
import io.trazo.core.math.hasLetters
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

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
    val reviewId: String = "",
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
        return last.takeIf { AiNotes.unchanged(last.strokeIds, last.area, strokes) }
    }

    fun without(noteId: String): PageAi = copy(notes = notes.filterNot { it.id == noteId })

    val isEmpty: Boolean get() = notes.isEmpty() && history.isEmpty()
}

object AiNotes {
    /** Se guardan a lo sumo estas revisiones por hoja (las más nuevas). */
    const val HISTORY_LIMIT = 30

    /** Un renglón numerado para el pedido: su texto («?» si el motor no lo leyó), su caja y sus trazos. */
    data class ReviewLine(val number: Int, val text: String, val bounds: Bounds, val strokeIds: List<String>)

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
        val kept = ai.notes.filter { unchanged(it.strokeIds, it.bounds, strokes) }
        return if (kept.size == ai.notes.size) ai else ai.copy(notes = kept)
    }

    internal fun unchanged(ids: List<String>, bounds: Bounds, strokes: List<InkStroke>): Boolean {
        if (ids.isEmpty()) return true
        val wanted = ids.toSet()
        val own = strokes.filter { it.id in wanted }
        if (own.size != wanted.size) return false
        val now = own.bounds()
        if (!close(now, bounds)) return false
        // Tinta nueva sobre el renglón (no la del resaltador).
        val area = bounds.inflate(2f)
        return strokes.none { s ->
            s.id !in wanted && s.brush != Brush.HIGHLIGHTER && s.calc == null && s.bounds.let { b ->
                b.centerX in area.left..area.right && b.centerY in area.top..area.bottom
            }
        }
    }

    private fun close(a: Bounds, b: Bounds, eps: Float = 0.5f) =
        kotlin.math.abs(a.left - b.left) < eps && kotlin.math.abs(a.top - b.top) < eps &&
            kotlin.math.abs(a.right - b.right) < eps && kotlin.math.abs(a.bottom - b.bottom) < eps

    /** Qué dice el motor de una igualdad del renglón. */
    enum class Verdict { HOLDS, FAILS, UNKNOWN }

    /** Resultado del motor por renglón y, si no se cumple, los dos lados como los calcula Trazo. */
    data class EngineCheck(val verdict: Verdict, val left: String = "", val right: String = "")

    /**
     * La doble verificación (15): Trazo evalúa cada renglón con un «=» en el medio, solo (sin despejar
     * con los demás, que podría dar por buena una cuenta con su propio error). Una identidad (`2x + 3x = 5x`)
     * es [Verdict.HOLDS]; una igualdad sin letras que no se cumple, [Verdict.FAILS].
     */
    fun check(lines: List<ReviewLine>): List<EngineCheck> = lines.map { l ->
        val text = l.text
        if (text == "?" || '=' !in text || text.trimEnd().endsWith("=")) return@map EngineCheck(Verdict.UNKNOWN)
        when (val r = runCatching { VariableGraph.evaluate(listOf(text), symbolic = true).first() }.getOrNull()) {
            is LineResult.Check -> when {
                r.holds -> EngineCheck(Verdict.HOLDS)
                text.none { it.isLetter() && it != 'π' } && !r.left.hasLetters() && !r.right.hasLetters() ->
                    EngineCheck(Verdict.FAILS, r.left.display(), r.right.display())
                else -> EngineCheck(Verdict.UNKNOWN)
            }
            else -> EngineCheck(Verdict.UNKNOWN)
        }
    }

    /**
     * Cruza las marcas de la IA con el motor: una marca de cuenta en un renglón que el motor da por bueno
     * queda «dudosa», y un renglón que el motor da por malo sin marca de la IA recibe una marca del motor.
     */
    fun crossCheck(notes: List<AiNote>, lines: List<ReviewLine>, ids: IdGenerator, reviewId: String): List<AiNote> {
        val checks = check(lines)
        val marked = notes.map { it.line }.toSet()
        val out = notes.map { n ->
            val c = checks.getOrNull(n.line - 1)
            if (n.kind == "CALCULATION" && c?.verdict == Verdict.HOLDS) n.copy(disputed = true) else n
        }
        val engine = lines.mapIndexedNotNull { i, l ->
            val c = checks[i]
            if (c.verdict != Verdict.FAILS || l.number in marked) return@mapIndexedNotNull null
            AiNote(
                id = ids.next(), line = l.number, kind = "CALCULATION",
                short = "Trazo calcula otro resultado",
                detail = "Según el motor de Trazo, el lado izquierdo vale ${c.left} y el derecho ${c.right}.",
                bounds = l.bounds, strokeIds = l.strokeIds, fromEngine = true, reviewId = reviewId,
            )
        }
        return out + engine
    }

    /** Una marca como la devuelve el modelo, antes de ubicarla en la hoja. */
    data class Mark(val line: Int, val kind: String, val short: String, val detail: String = "", val fix: String = "")

    /** Ubica cada marca en su renglón; las de renglones que no existen se descartan. */
    fun place(marks: List<Mark>, lines: List<ReviewLine>, ids: IdGenerator, reviewId: String): List<AiNote> =
        marks.mapNotNull { m ->
            val l = lines.getOrNull(m.line - 1) ?: return@mapNotNull null
            AiNote(ids.next(), m.line, m.kind, m.short, m.detail, m.fix, l.bounds, l.strokeIds, reviewId = reviewId)
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

    /** La nota que hay en ([x], [y]) (sobre su texto), o null. */
    fun noteAt(notes: List<AiNote>, x: Float, y: Float, slop: Float = 0f): AiNote? =
        layout(notes).lastOrNull { it.label.inflate(slop).contains(x, y) }?.note

    private fun prefix(n: AiNote) = when {
        n.fromEngine -> "Trazo: "
        n.disputed -> "¿? "
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
