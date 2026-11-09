package io.trazo.core

import java.text.Normalizer

/**
 * Hoja de errores: resumen de las fallas que el Modo IA marcó en un cuaderno, agrupadas por tipo y,
 * dentro de cada tipo, por tema repetido (la misma frase corta), de lo más frecuente a lo menos.
 * Las marcas «dudosas» (el motor no coincidió) no cuentan.
 */
object ErrorSheet {
    data class Example(val pageIndex: Int, val short: String, val fix: String, val confirmed: Boolean)

    /** Un tema repetido dentro de un tipo de error. */
    data class Topic(val title: String, val count: Int, val pages: List<Int>, val examples: List<Example>)

    data class Kind(val kind: String, val label: String, val count: Int, val confirmed: Int, val topics: List<Topic>)

    data class Sheet(val total: Int, val kinds: List<Kind>, val skills: Map<PracticeSkill, Int>) {
        val isEmpty: Boolean get() = total == 0
    }

    fun label(kind: String): String = when (kind.trim().uppercase()) {
        "CALCULATION" -> "Cuenta o signo"
        "CONCEPT" -> "Concepto o regla"
        "MISSING_STEP" -> "Paso faltante"
        "NOTATION" -> "Notación"
        else -> "Otro"
    }

    private val KIND_ORDER = listOf("CALCULATION", "CONCEPT", "MISSING_STEP", "NOTATION")

    fun build(pages: List<Page>, examplesPerTopic: Int = 3): Sheet {
        val seen = HashSet<Pair<String, String>>()
        val found = mutableListOf<Pair<Int, AiNote>>()
        pages.forEachIndexed { index, page ->
            val ai = page.ai ?: return@forEachIndexed
            (ai.notes + ai.history.flatMap { it.notes }).forEach { note ->
                if (!note.disputed && seen.add(note.reviewId to note.id)) found += index to note
            }
        }
        val kinds = found.groupBy { (_, n) -> n.kind.trim().uppercase().let { if (it in KIND_ORDER) it else "OTHER" } }
            .map { (kind, items) ->
                val topics = items.groupBy { (_, n) -> topicKey(n.short) }.map { (_, group) ->
                    Topic(
                        title = group.first().second.short.trim().ifEmpty { label(kind) },
                        count = group.size,
                        pages = group.map { it.first }.distinct().sorted(),
                        examples = group.take(examplesPerTopic).map { (p, n) -> Example(p, n.short, n.fix, n.confirmed) },
                    )
                }.sortedWith(compareByDescending<Topic> { it.count }.thenBy { it.pages.first() })
                Kind(kind, label(kind), items.size, items.count { it.second.confirmed }, topics)
            }
            .sortedWith(compareByDescending<Kind> { it.count }.thenBy { KIND_ORDER.indexOf(it.kind).let { i -> if (i < 0) 99 else i } })
        return Sheet(found.size, kinds, PracticePlanner.counts(found.map { it.second }))
    }

    /** Clave de tema: sin tildes, mayúsculas, signos ni espacios sobrantes. */
    internal fun topicKey(short: String): String =
        Normalizer.normalize(short.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
