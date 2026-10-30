package io.trazo.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.text.Normalizer

/** Tipo de error; cada uno tiene su color en la hoja (rojo, naranja, azul y gris). */
enum class MarkKind(val label: String, val color: Int) {
    CALCULATION("Cuenta o signo", 0xFFD32F2F.toInt()),
    CONCEPT("Concepto o regla", 0xFFEF6C00.toInt()),
    MISSING_STEP("Paso faltante", 0xFF1E63D6.toInt()),
    NOTATION("Notación", 0xFF757575.toInt()),
    ;

    companion object {
        fun parse(text: String?): MarkKind {
            val t = text.orEmpty().searchKey()
            return when {
                t.startsWith("conc") || "regla" in t -> CONCEPT
                t.startsWith("paso") || "falta" in t || "missing" in t || "step" in t -> MISSING_STEP
                t.startsWith("nota") -> NOTATION
                else -> CALCULATION
            }
        }
    }
}

/** Una marca sobre la hoja: a qué renglón numerado apunta y qué dice. */
data class AiMark(
    val line: Int,
    val kind: MarkKind,
    val short: String,
    val detail: String = "",
    /** El paso corregido: se muestra solo si el usuario lo pide. */
    val fix: String = "",
)

data class AiReview(val correct: Boolean, val score: Double?, val marks: List<AiMark>, val fromFreeText: Boolean = false)

/** Arma el pedido de revisión: el texto reconocido con los renglones numerados y el rigor elegido. */
object AiReviewPrompt {
    fun userText(lines: List<String>, rigor: Rigor): String = buildString {
        appendLine("Rigor: ${rigor.instruction}")
        appendLine()
        appendLine("Texto reconocido por Trazo (renglón: contenido; «?» = no reconocido, léalo en la imagen):")
        lines.forEachIndexed { i, l -> appendLine("${i + 1}: ${l.ifBlank { "?" }}") }
    }

    fun repairText(broken: String): String =
        "Su respuesta anterior no es JSON válido. Devuelva la misma corrección únicamente como JSON con la forma pedida, " +
            "sin texto adicional ni bloques de código.\n\nRespuesta anterior:\n$broken"
}

/**
 * Lee la corrección del modelo: primero el JSON pedido; si no es válido se le pide una vez que lo arregle,
 * y si tampoco sirve se rescatan las marcas del texto libre ("Renglón 3: ...").
 */
object AiReviewParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @param repair vuelve a preguntarle al modelo con [AiReviewPrompt.repairText]; null = no reintentar. */
    fun parse(reply: String, repair: ((String) -> String)? = null): AiReview {
        parseJson(reply)?.let { return it }
        if (repair != null) {
            val second = runCatching { repair(AiReviewPrompt.repairText(reply)) }.getOrNull()
            second?.let(::parseJson)?.let { return it }
        }
        return parseFreeText(reply)
    }

    fun parseJson(reply: String): AiReview? {
        val text = reply.replace(Regex("```[a-zA-Z]*"), "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val rawMarks = root.field("marcas", "marks", "errores", "errors") as? JsonArray
        if (rawMarks == null && root.field("correcto", "correct") == null) return null
        val marks = rawMarks.orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val line = o.field("renglon", "renglón", "linea", "línea", "line").number()?.toInt() ?: return@mapNotNull null
            AiMark(
                line = line,
                kind = MarkKind.parse(o.field("tipo", "type", "kind").string()),
                short = o.field("breve", "short", "mensaje", "message").string().orEmpty().trim(),
                detail = o.field("detalle", "detail", "explicacion", "explicación").string().orEmpty().trim(),
                fix = o.field("correccion", "corrección", "fix", "correction").string().orEmpty().trim(),
            )
        }
        val correct = (root.field("correcto", "correct") as? JsonPrimitive)?.booleanOrNull ?: marks.isEmpty()
        val score = root.field("puntaje", "score", "nota").number()?.coerceIn(0.0, 10.0)
        return AiReview(correct && marks.isEmpty(), score, marks)
    }

    private val lineRegex = Regex(
        """(?im)^\s*[-*•]?\s*(?:\*\*)?(?:rengl[oó]n|l[ií]nea|line|L)\s*(\d+)(?:\*\*)?\s*(?:\(([^)]*)\))?\s*[:\-–—]\s*(.+)$""",
    )

    fun parseFreeText(reply: String): AiReview {
        val marks = lineRegex.findAll(reply).map { m ->
            val (num, kind, rest) = m.destructured
            val body = rest.trim().removePrefix("**").trim()
            AiMark(
                line = num.toInt(),
                kind = MarkKind.parse(kind.ifBlank { body }),
                short = body.substringBefore(". ").take(120),
                detail = body,
            )
        }.toList()
        val sinErrores = reply.searchKey().let { "sin errores" in it || "es correct" in it || "no hay errores" in it }
        return AiReview(correct = marks.isEmpty() && sinErrores, score = null, marks = marks, fromFreeText = true)
    }

    private fun JsonObject.field(vararg names: String): JsonElement? = names.firstNotNullOfOrNull { this[it] }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.number(): Double? =
        (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
}

private fun String.searchKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
