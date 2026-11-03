package io.trazo.core

import io.trazo.core.math.LineResult
import io.trazo.core.math.VariableGraph
import java.text.Normalizer

/** Skills for which Trazo has deterministic, engine-verifiable practice templates. */
enum class PracticeSkill(val label: String) {
    SIGN_AND_ARITHMETIC("Signos y operaciones"),
    DISTRIBUTIVE_PROPERTY("Propiedad distributiva"),
    ONE_STEP_EQUATION("Ecuaciones de un paso"),
}

enum class PracticeAnswerKind { EXPRESSION, EQUATION_VALUE }

data class PracticeExercise(
    val id: String,
    val skill: PracticeSkill,
    val prompt: String,
    val correctAnswer: String,
    val explanation: String,
    val sourceErrorCount: Int,
    val answerKind: PracticeAnswerKind,
    val exactLinear: PracticeLinearProblem? = null,
) {
    /** Answers are checked by Trazo's math engine; no model-generated content or answer key is trusted. */
    fun isCorrect(raw: String): Boolean {
        exactLinear?.let { return it.verifyAnswer(raw) }
        val candidate = raw.trim().replace(',', '.')
        if (candidate.isEmpty()) return false
        val result = when (answerKind) {
            PracticeAnswerKind.EXPRESSION -> runCatching {
                VariableGraph.evaluate(listOf("$candidate = $correctAnswer"), symbolic = true).single()
            }.getOrNull()
            PracticeAnswerKind.EQUATION_VALUE -> {
                val value = candidate.substringAfter('=', candidate).trim()
                if (value.isEmpty()) return false
                runCatching {
                    VariableGraph.evaluate(listOf("x = $value", prompt.substringAfter(':', prompt).trim()), symbolic = false).last()
                }.getOrNull()
            }
        }
        return (result as? LineResult.Check)?.holds == true
    }
}

/** Counts actual, non-disputed AI review notes by supported skill. Unknown kinds are ignored. */
object PracticePlanner {
    private data class Template(
        val prompt: String,
        val answer: String,
        val explanation: String,
        val kind: PracticeAnswerKind,
    )

    private val templates = mapOf(
        PracticeSkill.SIGN_AND_ARITHMETIC to listOf(
            Template("Calcula: (−8) + 13", "5", "Restar 8 a 13 deja 5.", PracticeAnswerKind.EXPRESSION),
            Template("Calcula: (−12) − (−5)", "-7", "Restar un número negativo equivale a sumar su opuesto: −12 + 5 = −7.", PracticeAnswerKind.EXPRESSION),
            Template("Calcula: 7 − (−9)", "16", "Restar −9 equivale a sumar 9: 7 + 9 = 16.", PracticeAnswerKind.EXPRESSION),
        ),
        PracticeSkill.DISTRIBUTIVE_PROPERTY to listOf(
            Template("Desarrolla: 3(x + 4)", "3x + 12", "Multiplica 3 por cada término dentro del paréntesis.", PracticeAnswerKind.EXPRESSION),
            Template("Desarrolla: −2(x − 5)", "-2x + 10", "Multiplica −2 por x y por −5.", PracticeAnswerKind.EXPRESSION),
            Template("Desarrolla: 4(2x − 3)", "8x - 12", "Multiplica 4 por ambos términos: 8x − 12.", PracticeAnswerKind.EXPRESSION),
        ),
        PracticeSkill.ONE_STEP_EQUATION to listOf(
            Template("Resuelve: 2x + 3 = 13", "5", "Resta 3 en ambos lados y divide por 2.", PracticeAnswerKind.EQUATION_VALUE),
            Template("Resuelve: 3x − 4 = 11", "5", "Suma 4 en ambos lados y divide por 3.", PracticeAnswerKind.EQUATION_VALUE),
            Template("Resuelve: −2x + 6 = 16", "-5", "Resta 6 en ambos lados y divide por −2.", PracticeAnswerKind.EQUATION_VALUE),
        ),
    )

    fun counts(notes: Iterable<AiNote>): Map<PracticeSkill, Int> {
        val unique = notes.filterNot { it.disputed }.distinctBy { it.reviewId to it.id }
        val out = mutableMapOf<PracticeSkill, Int>()
        unique.forEach { note ->
            val detail = fold("${note.short} ${note.detail} ${note.fix}")
            val skill = when (note.kind.trim().uppercase()) {
                "CALCULATION" -> if (listOf("signo", "suma", "resta", "negativo", "positivo", "aritmet").any { detail.contains(it) }) {
                    PracticeSkill.SIGN_AND_ARITHMETIC
                } else null
                "CONCEPT" -> if (listOf("distributiv", "parentes", "parenthesis").any { detail.contains(it) }) {
                    PracticeSkill.DISTRIBUTIVE_PROPERTY
                } else null
                "MISSING_STEP" -> if (listOf("ecuacion", "despej", "aisl", "resolver x").any { detail.contains(it) }) {
                    PracticeSkill.ONE_STEP_EQUATION
                } else null
                else -> null // notation and unknown categories do not map to validated templates
            }
            if (skill != null) out[skill] = (out[skill] ?: 0) + 1
        }
        return out
    }

    private fun fold(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    /** Collects current and historical review notes; counts() de-duplicates the active review copy. */
    fun notesFromPages(pages: Iterable<Page>): List<AiNote> = pages.flatMap { page ->
        val ai = page.ai ?: return@flatMap emptyList()
        ai.notes + ai.history.flatMap { it.notes }
    }

    /**
     * Returns [count] exercises. Skills are sampled proportionally to observed error frequency;
     * [seed] makes the choice reproducible and is persisted by StudyStore.
     */
    fun build(frequencies: Map<PracticeSkill, Int>, seed: Long, count: Int = 1): List<PracticeExercise> {
        require(count >= 0) { "count must be non-negative" }
        val eligible = PracticeSkill.entries.mapNotNull { skill ->
            val weight = frequencies[skill]?.coerceAtLeast(0) ?: 0
            if (weight > 0) skill to weight else null
        }
        require(eligible.isNotEmpty() || count == 0) { "no supported error history" }
        if (count == 0) return emptyList()
        val total = eligible.sumOf { it.second }
        return List(count) { offset ->
            val n = Math.floorMod(seed + offset, total.toLong()).toInt()
            var cursor = n
            val skill = eligible.first { (_, weight) ->
                if (cursor < weight) true else { cursor -= weight; false }
            }.first
            val variants = templates.getValue(skill)
            val variant = variants[Math.floorMod(seed + offset, variants.size.toLong()).toInt()]
            val ex = PracticeExercise(
                id = "${skill.name.lowercase()}-${seed + offset}",
                skill = skill,
                prompt = variant.prompt,
                correctAnswer = variant.answer,
                explanation = variant.explanation,
                sourceErrorCount = frequencies[skill] ?: 0,
                answerKind = variant.kind,
            )
            check(ex.isCorrect(ex.correctAnswer)) { "Unverified practice template: ${ex.id}" }
            ex
        }
    }
}
