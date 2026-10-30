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
        val marks = rawMarks.orEmpty().mapNotNull { (it as? JsonObject)?.let(::markOf) }
        // QA53: una marca ilegible no es «sin errores». Si no se pudo leer ninguna, se pide el arreglo.
        val dropped = rawMarks.orEmpty().size - marks.size
        if (dropped > 0 && marks.isEmpty()) return null
        val rawCorrect = root.field("correcto", "correct")
        val correct = when (rawCorrect) {
            null -> marks.isEmpty()
            else -> (rawCorrect as? JsonPrimitive)?.booleanOrNull ?: false
        }
        val score = root.field("puntaje", "score", "nota").number()?.coerceIn(0.0, 10.0)
        return AiReview(correct && marks.isEmpty() && dropped == 0, score, marks)
    }

    /** Una marca del JSON (acepta los nombres en español y en inglés); null si no dice el renglón. */
    fun markOf(o: JsonObject): AiMark? {
        val line = o.field("renglon", "renglón", "linea", "línea", "line").number()?.toInt() ?: return null
        return AiMark(
            line = line,
            kind = MarkKind.parse(o.field("tipo", "type", "kind").string()),
            short = o.field("breve", "short", "mensaje", "message").string().orEmpty().trim(),
            detail = o.field("detalle", "detail", "explicacion", "explicación").string().orEmpty().trim(),
            fix = o.field("correccion", "corrección", "fix", "correction").string().orEmpty().trim(),
        )
    }

    internal fun lenient(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

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
        val key = reply.searchKey()
        // QA53: «no es correcto» o «incorrecto» no cuentan como hoja bien.
        val sinErrores = POSITIVE.containsMatchIn(key) && !NEGATIVE.containsMatchIn(key)
        return AiReview(correct = marks.isEmpty() && sinErrores, score = null, marks = marks, fromFreeText = true)
    }

    private val POSITIVE = Regex("""\bsin errores\b|\bno hay errores\b|\b(?:es|esta) correct[oa]s?\b""")
    private val NEGATIVE = Regex(
        """incorrect|\bno (?:es|esta|son|estan|parece|resulta) (?:del todo |completamente |totalmente )?correct|(?<!no )\bhay (?:un |varios |algunos )?errores?\b|\bcon errores\b|\bpero\b""",
    )

    internal fun JsonObject.field(vararg names: String): JsonElement? = names.firstNotNullOfOrNull { this[it] }

    internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.number(): Double? =
        (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
}

/**
 * Lee las marcas mientras llega la respuesta (streaming): busca la lista `marcas` y entrega cada objeto
 * apenas se cierra, para que la hoja las vaya mostrando. La respuesta completa se vuelve a leer al final con
 * [AiReviewParser], que es la que vale.
 */
class StreamingMarks(private val onMark: (AiMark) -> Unit) {
    private val text = StringBuilder()
    private var pos = 0
    private var inArray = false
    private var depth = 0
    private var start = -1
    private var inString = false
    private var escaped = false
    private var done = false

    fun feed(chunk: String) {
        if (done) return
        text.append(chunk)
        if (!inArray) {
            val m = ARRAY_START.find(text) ?: return
            inArray = true
            pos = m.range.last + 1
        }
        while (pos < text.length && !done) {
            val c = text[pos]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = pos; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val o = AiReviewParser.lenient(text.substring(start, pos + 1)) as? JsonObject
                        o?.let(AiReviewParser::markOf)?.let(onMark)
                        start = -1
                    }
                }
                ']' -> if (depth == 0) done = true
            }
            pos++
        }
    }

    private companion object {
        val ARRAY_START = Regex(""""(?:marcas|marks|errores|errors)"\s*:\s*\[""")
    }
}

/** Respuesta a «Discutir» una marca: si el modelo la mantiene, qué contesta y, si la corrige, la marca nueva. */
data class AiDiscussion(val keep: Boolean, val answer: String, val mark: AiMark? = null)

/**
 * Revisión de una hoja: arma el pedido, lo manda en streaming (las marcas llegan de a una por [review]'s
 * `onMark`), lee la respuesta (con un reintento si el JSON vino roto) y aplica el rigor.
 */
class AiReviewer(private val client: AiClient = AiClient()) {
    fun review(profile: AiProfile, key: String?, lines: List<String>, png: ByteArray?, onMark: (AiMark) -> Unit = {}): AiReview {
        val keep = { m: AiMark -> m.kind != MarkKind.NOTATION || profile.rigor == Rigor.STRICT }
        val stream = StreamingMarks { if (keep(it)) onMark(it) }
        val reply = client.stream(profile, key, profile.instructions, AiReviewPrompt.userText(lines, profile.rigor), png) { stream.feed(it) }
        val r = AiReviewParser.parse(reply) { fix -> client.complete(profile, key, profile.instructions, fix, null) }
        val marks = r.marks.filter(keep)
        // Si solo había notación (y el rigor no la pide), la hoja queda bien.
        val correct = marks.isEmpty() && (r.correct || r.marks.isNotEmpty())
        return r.copy(correct = correct, marks = marks)
    }

    /**
     * El usuario no está de acuerdo con una marca: se le muestra al modelo la marca, el renglón y el
     * argumento, y decide si la mantiene, la retira o la corrige.
     */
    fun discuss(profile: AiProfile, key: String?, mark: AiMark, lineText: String, argument: String, png: ByteArray?): AiDiscussion {
        val text = buildString {
            appendLine("Usted marcó el renglón ${mark.line} (${mark.kind.label}): ${mark.short}")
            if (mark.detail.isNotBlank()) appendLine("Detalle: ${mark.detail}")
            if (mark.fix.isNotBlank()) appendLine("Corrección propuesta: ${mark.fix}")
            appendLine("Texto reconocido del renglón: ${lineText.ifBlank { "?" }}")
            appendLine()
            appendLine("El estudiante responde: $argument")
            appendLine()
            append(
                "Revise de nuevo con cuidado. Responda únicamente con JSON: " +
                    "{\"mantener\": true|false, \"respuesta\": \"...\", \"marca\": {\"renglon\": ${mark.line}, \"tipo\": \"...\", " +
                    "\"breve\": \"...\", \"detalle\": \"...\", \"correccion\": \"...\"} o null}. " +
                    "Si el estudiante tiene razón, «mantener» es false. Si la marca sigue pero cambia, envíe la marca nueva.",
            )
        }
        val reply = client.complete(profile, key, DISCUSS_SYSTEM, text, png)
        return parseDiscussion(reply)
    }

    internal fun parseDiscussion(reply: String): AiDiscussion {
        val clean = reply.replace(Regex("```[a-zA-Z]*"), "")
        val s = clean.indexOf('{')
        val e = clean.lastIndexOf('}')
        val o = if (s >= 0 && e > s) AiReviewParser.lenient(clean.substring(s, e + 1)) as? JsonObject else null
        if (o == null) return AiDiscussion(keep = true, answer = reply.trim())
        with(AiReviewParser) {
            val keep = (o.field("mantener", "keep") as? JsonPrimitive)?.booleanOrNull ?: true
            val answer = o.field("respuesta", "answer").string().orEmpty().trim()
            val mark = (o.field("marca", "mark") as? JsonObject)?.let(::markOf)
            return AiDiscussion(keep, answer.ifBlank { if (keep) "El modelo mantiene la marca." else "El modelo retira la marca." }, mark.takeIf { keep })
        }
    }

    companion object {
        const val DISCUSS_SYSTEM = "Usted es un corrector de matemática. Reconsidera una marca que hizo sobre una hoja manuscrita. " +
            "Sea honesto: si se equivocó, admítalo. Use español formal y neutro, como máximo tres oraciones en «respuesta»."
    }
}

private fun String.searchKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
