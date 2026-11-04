package io.trazo.ai

/** Quién habla en el chat de revisión. */
enum class TutorRole { STUDENT, TUTOR }

data class TutorTurn(val role: TutorRole, val text: String)

/** El primer error que marcó el tutor ([line] numera los renglones de la hoja desde 1), o null si está bien. */
data class TutorVerdict(val correct: Boolean, val line: Int?, val summary: String)

/**
 * Chat de revisión: el modelo rehace la resolución del estudiante paso a paso, por el mismo camino, marca
 * el primer error y después contesta preguntas. Cada llamada es de un solo turno: la conversación previa
 * viaja como transcripción, y la imagen de la hoja solo en el primer pedido (después ya la conoce por la
 * transcripción y los renglones, que se mandan siempre).
 */
class AiTutor(private val client: AiClient = AiClient()) {
    fun ask(
        profile: AiProfile, key: String?, lines: List<String>, png: ByteArray?, history: List<TutorTurn>, question: String?,
        onUsage: ((AiTokenUsage) -> Unit)? = null, onText: (String) -> Unit = {},
    ): String = client.stream(
        profile, key, SYSTEM, AiTutorPrompt.userText(lines, history, question), png.takeIf { history.isEmpty() },
        maxTokens = profile.maxTokens.coerceAtLeast(MIN_TOKENS), onUsage = onUsage, onText = onText,
    )

    companion object {
        const val MIN_TOKENS = 2048
        val SYSTEM: String = AiTutorPrompt.SYSTEM

        /** Botones rápidos debajo del chat. */
        val QUICK = listOf("Explicá más", "Otro método", "Dame una pista")
    }
}

object AiTutorPrompt {
    const val SYSTEM: String = """Usted es un tutor de matemática que revisa la resolución manuscrita de un estudiante y conversa con él en un chat.
Datos: recibe la imagen de la hoja y, por renglón, etiquetas crudas (símbolos reconocidos y sus posiciones). Las etiquetas no son una expresión reconstruida ni un resultado del motor de Trazo: lea la escritura en la imagen y úselas solo para ubicarse. Si algo no se entiende, dígalo en vez de inventarlo.
En la primera respuesta:
1. Rehaga la resolución paso a paso por el MISMO camino que eligió el estudiante (mismo método, mismo orden), corrigiendo lo que haga falta. Una línea breve de explicación por paso y la cuenta de cada paso en su propio renglón entre $$ y $$.
2. Al final, exactamente una línea con el veredicto: «ERROR (renglón N): qué estuvo mal y por qué» para el PRIMER renglón con un error matemático, o «CORRECTO: comentario breve» si no hay errores. N es el número de renglón de la lista.
En las respuestas siguientes conteste lo que pida el estudiante, breve, sin repetir toda la resolución salvo que la pida; si el pedido revela otro error, puede volver a dar la línea de veredicto.
Notación: fórmulas en LaTeX, entre $$ … $$ en su propio renglón o entre \( … \) dentro de una oración. Use solo \frac, \sqrt, ^, _, \cdot, \times, \pm, \le, \ge, \ne, \left( \right), \begin{pmatrix}, \begin{cases}, \sum, \int, \lim, \text y letras griegas. Nada de tablas, imágenes ni Markdown complejo (sin títulos con #).
Escriba en español claro y cercano, sin rodeos."""

    /** Hasta cuántos turnos previos y cuánto de cada uno viajan en la transcripción. */
    const val MAX_TURNS = 16
    const val MAX_TURN_CHARS = 6000

    fun userText(lines: List<String>, history: List<TutorTurn>, question: String?): String = buildString {
        appendLine("Renglones de la hoja (etiquetas crudas con posiciones, numerados desde 1):")
        lines.forEachIndexed { i, l -> appendLine("${i + 1}: ${l.ifBlank { "?" }}") }
        appendLine()
        val recent = history.takeLast(MAX_TURNS)
        if (recent.isEmpty()) {
            appendLine("Revise toda la resolución como se indicó: rehágala paso a paso por el mismo camino y marque el primer error.")
        } else {
            if (recent.size < history.size) appendLine("(Se omitieron ${history.size - recent.size} mensajes anteriores.)")
            appendLine("Conversación hasta ahora:")
            for (t in recent) {
                val who = if (t.role == TutorRole.STUDENT) "Estudiante" else "Tutor"
                appendLine("[$who]")
                appendLine(t.text.take(MAX_TURN_CHARS).trim())
                appendLine()
            }
        }
        if (!question.isNullOrBlank()) {
            appendLine("Nuevo mensaje del estudiante:")
            append(question.trim())
        } else if (recent.isNotEmpty()) {
            append("Continúe.")
        }
    }

    private val ERROR = Regex("""(?im)^\W*error\W*(?:en\s+el\s+)?rengl[oó]n\s*(\d+)[\s)*]*[:.\-–—]\s*(.+)$""")
    private val CORRECT = Regex("""(?im)^\W*correcto\W*[:.\-–—]?\s*(.*)$""")

    /** El veredicto de una respuesta (el último que aparezca); null si la respuesta no trae ninguno. */
    fun verdict(reply: String, lineCount: Int = Int.MAX_VALUE): TutorVerdict? {
        val error = ERROR.findAll(reply).lastOrNull()
        val correct = CORRECT.findAll(reply).lastOrNull()
        if (error != null && (correct == null || error.range.first > correct.range.first)) {
            val line = error.groupValues[1].toIntOrNull()?.takeIf { it in 1..lineCount }
            return TutorVerdict(false, line, error.groupValues[2].trim().trim('*').trim())
        }
        if (correct != null) return TutorVerdict(true, null, correct.groupValues[1].trim().trim('*').trim())
        return null
    }

    /** La respuesta sin la línea del veredicto, que la app muestra aparte en una tarjeta. */
    fun withoutVerdict(reply: String): String {
        val v = (ERROR.findAll(reply) + CORRECT.findAll(reply)).maxByOrNull { it.range.first } ?: return reply.trim()
        return reply.removeRange(v.range).trim()
    }
}
