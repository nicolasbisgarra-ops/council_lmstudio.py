package io.trazo.core

import org.junit.Assert.*
import org.junit.Test

class PracticeTest {
    private fun note(id: String, kind: String, review: String = "r1", disputed: Boolean = false) =
        AiNote(
            id, 1, kind,
            short = when (kind.uppercase()) {
                "CALCULATION" -> "Error de signo al sumar"
                "CONCEPT" -> "Regla distributiva mal aplicada"
                "MISSING_STEP" -> "Falta despejar la ecuación"
                else -> "error"
            },
            bounds = Bounds(0f, 0f, 10f, 10f), disputed = disputed, reviewId = review,
        )

    @Test fun `counts supported errors but ignores disputed duplicate and unsupported notes`() {
        val counts = PracticePlanner.counts(listOf(
            note("a", "CALCULATION"), note("b", "calculation"),
            note("c", "CONCEPT"), note("c", "CONCEPT"), // same review and note: duplicate evidence
            note("d", "MISSING_STEP", review = "r2"),
            note("e", "CALCULATION", disputed = true), note("f", "NOTATION"), note("g", "future-kind"),
        ))
        assertEquals(2, counts[PracticeSkill.SIGN_AND_ARITHMETIC])
        assertEquals(1, counts[PracticeSkill.DISTRIBUTIVE_PROPERTY])
        assertEquals(1, counts[PracticeSkill.ONE_STEP_EQUATION])
    }

    @Test fun `notes are collected from persisted review history and active copy is not double counted`() {
        val mark = note("mark", "CONCEPT")
        val page = Page("p", ai = PageAi(
            notes = listOf(mark),
            history = listOf(AiReviewRecord("r1", 1L, "model", false, notes = listOf(mark))),
        ))
        val counts = PracticePlanner.counts(PracticePlanner.notesFromPages(listOf(page)))
        assertEquals(1, counts[PracticeSkill.DISTRIBUTIVE_PROPERTY])
    }

    @Test fun `every arithmetic template answer is verified by the math engine`() {
        repeat(3) { seed ->
            val ex = PracticePlanner.build(mapOf(PracticeSkill.SIGN_AND_ARITHMETIC to 1), seed.toLong()).single()
            assertTrue(ex.prompt, ex.isCorrect(ex.correctAnswer))
            assertFalse(ex.prompt, ex.isCorrect("999"))
            assertEquals(PracticeSkill.SIGN_AND_ARITHMETIC, ex.skill)
        }
    }

    @Test fun `distributive answers are algebraically checked rather than string matched`() {
        repeat(3) { seed ->
            val ex = PracticePlanner.build(mapOf(PracticeSkill.DISTRIBUTIVE_PROPERTY to 1), seed.toLong()).single()
            assertTrue(ex.prompt, ex.isCorrect(ex.correctAnswer))
        }
        val first = PracticePlanner.build(mapOf(PracticeSkill.DISTRIBUTIVE_PROPERTY to 1), 0L).single()
        assertTrue(first.isCorrect("12 + 3x")) // equivalent ordering is accepted
    }

    @Test fun `linear equation answers are checked by substitution`() {
        repeat(3) { seed ->
            val ex = PracticePlanner.build(mapOf(PracticeSkill.ONE_STEP_EQUATION to 1), seed.toLong()).single()
            assertTrue(ex.prompt, ex.isCorrect(ex.correctAnswer))
            assertFalse(ex.prompt, ex.isCorrect("4"))
        }
    }

    @Test fun `seed yields reproducible exercises and selectable skills follow frequencies`() {
        val frequencies = mapOf(PracticeSkill.SIGN_AND_ARITHMETIC to 1, PracticeSkill.ONE_STEP_EQUATION to 4)
        assertEquals(PracticePlanner.build(frequencies, 18L, 8), PracticePlanner.build(frequencies, 18L, 8))
        val selected = PracticePlanner.build(frequencies, 0L, 5)
        assertEquals(PracticeSkill.SIGN_AND_ARITHMETIC, selected.first().skill)
        assertEquals(PracticeSkill.ONE_STEP_EQUATION, selected[1].skill)
        assertEquals(4, selected[1].sourceErrorCount)
    }

    @Test fun `no supported history fails clearly and zero count is empty`() {
        assertTrue(PracticePlanner.build(emptyMap(), 1L, 0).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PracticePlanner.build(emptyMap(), 1L) }
    }

    @Test fun `bad or blank answers never count as correct`() {
        val ex = PracticePlanner.build(mapOf(PracticeSkill.SIGN_AND_ARITHMETIC to 1), 0L).single()
        assertFalse(ex.isCorrect(""))
        assertFalse(ex.isCorrect("not math"))
    }
}
