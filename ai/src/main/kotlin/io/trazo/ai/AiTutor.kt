package io.trazo.ai

/** Quién habla en el chat de revisión. */
enum class TutorRole { STUDENT, TUTOR }

data class TutorTurn(val role: TutorRole, val text: String)

/** El primer error que marcó el tutor ([line] numera los renglones de la hoja desde 1), o null si está bien. */
data class TutorVerdict(val correct: Boolean, val line: Int?, val summary: String)

/** Cómo contesta el chat la primera vez: corregir, resolver y después corregir, solo resolver o solo dar pistas. */
enum class ChatMode(val label: String, val hint: String) {
    CORRECT("Corregir", "Rehace tu resolución por tu mismo camino y marca el primer error."),
    SOLVE_THEN_CORRECT("Resolver y corregir", "Primero resuelve el ejercicio por su cuenta y después compara con lo tuyo."),
    SOLVE("Solo resolver", "Resuelve el ejercicio paso a paso, sin corregir lo tuyo."),
    HINTS("Solo pistas", "No da la solución: te marca dónde mirar y te deja seguir a vos."),
    ;

    /** Este modo pide la línea de veredicto (ERROR/CORRECTO) que la app marca en la hoja. */
    val verdicts: Boolean get() = this == CORRECT || this == SOLVE_THEN_CORRECT

    companion object {
        fun of(name: String?): ChatMode = entries.firstOrNull { it.name == name } ?: CORRECT
    }
}

/**
 * Chat de revisión: el modelo rehace la resolución del estudiante paso a paso, por el mismo camino, marca
 * el primer error y después contesta preguntas. Cada llamada es de un solo turno: la conversación previa
 * viaja como transcripción, y la imagen de la hoja solo en el primer pedido (después ya la conoce por la
 * transcripción y los renglones, que se mandan siempre).
 */
class AiTutor(private val client: AiClient = AiClient()) {
    fun ask(
        profile: AiProfile, key: String?, lines: List<String>, png: ByteArray?, history: List<TutorTurn>, question: String?,
        onUsage: ((AiTokenUsage) -> Unit)? = null, mode: ChatMode = ChatMode.CORRECT, onText: (String) -> Unit = {},
    ): String = client.stream(
        profile, key, AiTutorPrompt.system(mode), AiTutorPrompt.userText(lines, history, question, mode), png,
        maxTokens = profile.maxTokens.coerceAtLeast(MIN_TOKENS), onUsage = onUsage, onText = onText,
    )

    companion object {
        const val MIN_TOKENS = 4096
        val SYSTEM: String = AiTutorPrompt.SYSTEM

        /** Botones rápidos debajo del chat. */
        val QUICK = listOf("Explicá más", "Otro método", "Dame una pista")
    }
}

object AiTutorPrompt {
    /** La instrucción para cada modo: cambia solo lo que se pide en la primera respuesta. */
    fun system(mode: ChatMode): String = when (mode) {
        ChatMode.CORRECT -> SYSTEM
        ChatMode.SOLVE_THEN_CORRECT -> HEAD + SOLVE_THEN_CORRECT_PART + TAIL
        ChatMode.SOLVE -> HEAD + SOLVE_PART + TAIL
        ChatMode.HINTS -> HEAD + HINTS_PART + TAIL
    }

    private const val SOLVE_THEN_CORRECT_PART: String = """En la primera respuesta:
1. Bajo el título «## Resolución», resuelva usted el ejercicio completo, paso a paso y por el método más claro, sin mirar todavía lo que hizo el estudiante. Organícela con títulos «### 1. Nombre del paso» y, debajo de cada título, una o dos líneas breves de explicación y la cuenta de ese paso.
2. Bajo el título «## Corrección», compare con la resolución del estudiante: diga en qué coincide, dónde se separa y por qué. Si eligió otro camino válido, dígalo.
3. Al final, en un renglón aparte y sin formato (sin #, sin negrita), exactamente una línea con el veredicto: «ERROR (renglón N): qué estuvo mal y por qué» para el PRIMER renglón con un error matemático, o «CORRECTO: comentario breve» si no hay errores. N es el número de renglón de la lista. Si la hoja no tiene renglones manuscritos, omita la corrección y el veredicto.
En las respuestas siguientes conteste lo que pida el estudiante, breve, sin repetir toda la resolución salvo que la pida; si el pedido revela otro error, puede volver a dar la línea de veredicto. Las cuentas van siempre apiladas, igual que en la primera respuesta.
"""

    private const val SOLVE_PART: String = """En la primera respuesta resuelva el ejercicio completo, paso a paso y por el método más claro. Si el estudiante ya empezó, puede seguir su camino, pero NO lo corrija ni dé veredicto: solo resuelva. Organícela con títulos «### 1. Nombre del paso» y, debajo de cada título, una o dos líneas breves de explicación y la cuenta de ese paso. No escriba líneas que empiecen con «ERROR» ni con «CORRECTO».
En las respuestas siguientes conteste lo que pida el estudiante, breve, sin repetir toda la resolución salvo que la pida. Las cuentas van siempre apiladas, igual que en la primera respuesta.
"""

    private const val HINTS_PART: String = """Su papel es guiar, no resolver: NUNCA dé la solución completa ni el resultado final, aunque se lo pidan; si insisten, dé una pista más concreta.
En la primera respuesta:
1. Si hay un error en la resolución del estudiante, diga en qué renglón mirar y qué idea revisar, sin escribir la cuenta corregida.
2. Si no hay error o la hoja todavía no tiene la resolución, dé una o dos pistas para el próximo paso (qué propiedad, fórmula o método conviene usar), con a lo sumo una cuenta corta de ejemplo que no sea la del ejercicio.
3. Termine con una pregunta breve que invite al estudiante a seguir.
No escriba líneas que empiecen con «ERROR» ni con «CORRECTO».
En las respuestas siguientes siga guiando con pistas cada vez más concretas, breves. Las cuentas van siempre apiladas.
"""

    private const val HEAD: String = """Usted es un tutor de matemática que revisa la resolución manuscrita de un estudiante y conversa con él en un chat.
Datos: recibe una imagen con la hoja entera (incluidas las imágenes pegadas en ella, que pueden traer el enunciado) y, si el estudiante adjuntó una imagen en el chat, esa imagen debajo, separada por una franja gris. Además recibe, por renglón, etiquetas crudas (símbolos reconocidos y sus posiciones). Las etiquetas no son una expresión reconstruida ni un resultado del motor de Trazo: lea la escritura en la imagen y úselas solo para ubicarse. Si la hoja no tiene renglones manuscritos, trabaje con lo que se ve en las imágenes (por ejemplo, resuelva o explique el enunciado). Si algo no se entiende, dígalo en vez de inventarlo.
"""

    private const val CORRECT_PART: String = """En la primera respuesta:
1. Rehaga la resolución paso a paso por el MISMO camino que eligió el estudiante (mismo método, mismo orden), corrigiendo lo que haga falta. Organícela con títulos «### 1. Nombre del paso» y, debajo de cada título, una o dos líneas breves de explicación y la cuenta de ese paso.
2. Al final, en un renglón aparte y sin formato (sin #, sin negrita), exactamente una línea con el veredicto: «ERROR (renglón N): qué estuvo mal y por qué» para el PRIMER renglón con un error matemático, o «CORRECTO: comentario breve» si no hay errores. N es el número de renglón de la lista.
En las respuestas siguientes conteste lo que pida el estudiante, breve, sin repetir toda la resolución salvo que la pida; si el pedido revela otro error, puede volver a dar la línea de veredicto. Las cuentas van siempre apiladas, igual que en la primera respuesta.
"""

    private const val TAIL: String = """Notación: LaTeX normal (\frac, \sqrt, \int, \left[ \right]_a^b, \operatorname{sen}, \displaystyle, \Rightarrow, \boxed, \text, matrices, cases, letras griegas, etc.). Dentro de una oración, entre \( … \). Cada cuenta va SIEMPRE apilada, una igualdad por renglón y nunca una cadena larga en una sola línea:
$$
\begin{aligned}
x^2 - 5x + 6 &= 0 \\[8pt]
(x-2)(x-3) &= 0 \\[8pt]
\Rightarrow\ x &= 2 \quad \text{o} \quad x = 3
\end{aligned}
$$
Use un bloque $$\begin{aligned} … \end{aligned}$$ por paso, con & delante del = (o del ⇒) para alinear y \\[8pt] entre renglones; si un renglón sigue la cuenta anterior, empiécelo con &=. Encierre el resultado final de cada ejercicio en \boxed{…}. Puede usar listas con «* » para enumerar observaciones. No dibuje tablas ni imágenes en la respuesta.
Escriba en español claro y cercano, sin rodeos."""

    /** La instrucción del modo de siempre (corregir). Va después de sus partes: una const no puede usar las de abajo. */
    const val SYSTEM: String = HEAD + CORRECT_PART + TAIL

    /** Hasta cuántos turnos previos y cuánto de cada uno viajan en la transcripción. */
    const val MAX_TURNS = 16
    const val MAX_TURN_CHARS = 6000

    fun userText(
        lines: List<String>, history: List<TutorTurn>, question: String?, mode: ChatMode = ChatMode.CORRECT,
    ): String = buildString {
        appendLine("Renglones de la hoja (etiquetas crudas con posiciones, numerados desde 1):")
        lines.forEachIndexed { i, l -> appendLine("${i + 1}: ${l.ifBlank { "?" }}") }
        if (lines.isEmpty()) appendLine("(No hay renglones manuscritos: use la imagen.)")
        appendLine()
        val recent = history.takeLast(MAX_TURNS)
        if (recent.isEmpty()) {
            appendLine(when (mode) {
                ChatMode.CORRECT -> "Revise toda la resolución como se indicó: rehágala paso a paso por el mismo camino y marque el primer error."
                ChatMode.SOLVE_THEN_CORRECT -> "Como se indicó: primero resuelva el ejercicio usted y después corrija la resolución del estudiante."
                ChatMode.SOLVE -> "Resuelva el ejercicio paso a paso como se indicó, sin corregir."
                ChatMode.HINTS -> "Guíe al estudiante con pistas como se indicó, sin dar la solución."
            })
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
