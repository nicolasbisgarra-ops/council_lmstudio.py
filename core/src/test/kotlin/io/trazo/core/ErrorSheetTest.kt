package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSheetTest {
    private val box = Bounds(0f, 0f, 10f, 10f)

    private fun note(id: String, kind: String, short: String, review: String = "r1", disputed: Boolean = false, confirmed: Boolean = false) =
        AiNote(id, 1, kind, short, fix = "arreglo $id", bounds = box, disputed = disputed, confirmed = confirmed, reviewId = review)

    @Test fun `groups by kind and repeated topic, most frequent first`() {
        val a = note("a", "CALCULATION", "Signo mal distribuido", confirmed = true)
        val pages = listOf(
            Page("p0", ai = PageAi(notes = listOf(a), history = listOf(AiReviewRecord("r1", 0, "m", false, notes = listOf(a))))),
            Page("p1"),
            Page("p2", ai = PageAi(notes = listOf(
                note("b", "calculation", "signo  mal distribuído.", review = "r2"),
                note("c", "CONCEPT", "Regla de la cadena", review = "r2"),
                note("d", "CALCULATION", "Suma mal hecha", review = "r2"),
                note("e", "NOTATION", "Falta paréntesis", review = "r2", disputed = true),
            ))),
        )
        val sheet = ErrorSheet.build(pages)
        assertEquals(4, sheet.total)
        assertEquals(listOf("Cuenta o signo", "Concepto o regla"), sheet.kinds.map { it.label })
        val calc = sheet.kinds[0]
        assertEquals(3, calc.count)
        assertEquals(1, calc.confirmed)
        assertEquals("Signo mal distribuido", calc.topics[0].title)
        assertEquals(2, calc.topics[0].count)
        assertEquals(listOf(0, 2), calc.topics[0].pages)
        assertEquals(1, calc.topics[1].count)
        assertEquals(3, sheet.skills.values.sum())
    }

    @Test fun `empty notebook gives an empty sheet`() {
        assertTrue(ErrorSheet.build(listOf(Page("a"), Page("b"))).isEmpty)
    }
}
