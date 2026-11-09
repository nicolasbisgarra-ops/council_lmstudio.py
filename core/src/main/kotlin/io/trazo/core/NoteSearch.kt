package io.trazo.core

import java.text.Normalizer

/**
 * Buscar en lo escrito a mano: cada renglón reconocido de cada hoja (lo mismo que lee el Modo IA) queda en un
 * índice; la búsqueda compara sin tildes, mayúsculas ni espacios, y con ×, ·, * como el mismo signo.
 * Los renglones con escritura sin reconocer («?») no se indexan.
 */
object NoteSearch {
    /** Un renglón de una hoja. [bounds] permite resaltarlo al abrir la hoja. */
    data class Entry(val notebookId: String, val notebookName: String, val pageIndex: Int, val text: String, val bounds: Bounds) {
        internal val key = fold(text)
    }

    /** Forma comparable de [s]: sin tildes, minúsculas, sin espacios, con los signos de multiplicar y menos unificados. */
    fun fold(s: String): String {
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder(plain.length)
        for (c in plain.lowercase()) {
            when {
                c.isWhitespace() -> {}
                c == '×' || c == '·' || c == '⋅' || c == '∙' -> sb.append('*')
                c == '−' || c == '–' || c == '—' -> sb.append('-')
                c == '÷' -> sb.append('/')
                // La coma decimal se lee como punto: «1,5» encuentra «1.5».
                c == ',' -> sb.append('.')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Renglones legibles de una hoja, de arriba hacia abajo. */
    fun pageLines(strokes: List<InkStroke>, patterns: List<InkPattern>): List<AiNotes.ReviewLine> =
        if (strokes.isEmpty()) emptyList()
        else MathNotes.reviewLines(strokes, patterns).filter { it.text.isNotBlank() && it.text != "?" }

    /** Entradas del índice para la hoja [pageIndex] del cuaderno. */
    fun index(notebookId: String, notebookName: String, pageIndex: Int, page: Page, patterns: List<InkPattern>): List<Entry> =
        pageLines(page.allInk, patterns).map { Entry(notebookId, notebookName, pageIndex, it.text, it.bounds) }

    /** Entradas que contienen [query]; vacío si la consulta no tiene nada comparable. */
    fun search(entries: List<Entry>, query: String, limit: Int = 100): List<Entry> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        return entries.asSequence().filter { q in it.key }.take(limit).toList()
    }
}
