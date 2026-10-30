package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA53: una marca o el tilde se van si la tinta cambió aunque los ids y la caja total sigan iguales. */
class AiNotesQa53Test {
    private val ids = IdGenerator.sequential("q")

    /** Un renglón de dos trazos; el segundo puede cambiar por dentro sin mover la caja. */
    private fun ink(middleY: Float = 20f) = listOf(
        InkStroke("a", listOf(InkPoint(0f, 0f), InkPoint(100f, 40f))),
        InkStroke("b", listOf(InkPoint(120f, 0f), InkPoint(160f, middleY), InkPoint(200f, 40f))),
    )

    private fun noteFor(strokes: List<InkStroke>): PageAi {
        val lines = MathNotes.reviewLines(strokes, emptyList())
        return PageAi(AiNotes.place(listOf(AiNotes.Mark(1, "CALCULATION", "x")), lines, ids, "r"))
    }

    @Test fun editingAStrokeInPlaceRemovesTheMark() {
        val ai = noteFor(ink())
        assertEquals(1, ai.notes.size)
        assertTrue(ai.notes.single().fingerprint.isNotEmpty())
        assertEquals(ai, AiNotes.prune(ai, ink()))
        assertTrue(AiNotes.prune(ai, ink(middleY = 35f))!!.notes.isEmpty())
    }

    @Test fun strikingThroughTheLineRemovesTheMark() {
        val ai = noteFor(ink())
        // Tachado largo: su centro cae fuera del renglón, pero lo cruza casi entero.
        val strike = InkStroke("z", listOf(InkPoint(-10f, 20f), InkPoint(210f, 20f), InkPoint(210f, 200f)))
        assertTrue(AiNotes.prune(ai, ink() + strike)!!.notes.isEmpty())
    }

    @Test fun inkThatOnlyGrazesTheLineKeepsTheMark() {
        val ai = noteFor(ink())
        val below = InkStroke("y", listOf(InkPoint(0f, 41f), InkPoint(0f, 200f)))
        assertEquals(1, AiNotes.prune(ai, ink() + below)!!.notes.size)
    }

    @Test fun verdictUsesTheFingerprintToo() {
        val strokes = ink()
        val rec = AiReviewRecord(
            "r", 1, "m", true, 10.0, area = strokes.bounds(), strokeIds = listOf("a", "b"),
            fingerprint = AiNotes.fingerprint(listOf("a", "b"), strokes),
        )
        val ai = PageAi(history = listOf(rec))
        assertNotNull(ai.verdict(strokes))
        assertNull(ai.verdict(ink(middleY = 35f)))
    }

    @Test fun fingerprintIgnoresOrderAndOtherStrokes() {
        val s = ink()
        val extra = InkStroke("c", listOf(InkPoint(500f, 500f)))
        assertEquals(AiNotes.fingerprint(listOf("a", "b"), s), AiNotes.fingerprint(listOf("b", "a"), (s + extra).reversed()))
    }

    @Test fun oldNotesWithoutFingerprintStillWork() {
        val ai = noteFor(ink())
        val old = PageAi(ai.notes.map { it.copy(fingerprint = "") })
        assertEquals(old, AiNotes.prune(old, ink(middleY = 35f)))
    }
}
