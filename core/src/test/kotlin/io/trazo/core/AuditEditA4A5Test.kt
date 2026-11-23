package io.trazo.core

import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auditoría A4 (cancelar un arrastre devuelve las imágenes) y A5 (la goma no duplica glifos). */
class AuditEditA4A5Test {
    private val name = "a".repeat(64) + ".webp"

    @Test fun `cancel while moving the selection puts the image back`() {
        val c = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential())
        val pen = SyntheticPen(c)
        val original = PageImage("img", name, 100f, 100f, 400f, 300f, ImagePreset.DOCUMENT)
        c.insertImage(original)
        val b = c.selectionBounds!!
        pen.begin(listOf(b.centerX to b.centerY, b.centerX + 120f to b.centerY + 60f))
        assertTrue("el arrastre movió la imagen", c.page.images.single() != original)
        c.cancel()
        assertEquals(original, c.page.images.single())
    }

    @Test fun `erasing through a single stroke glyph keeps one glyph`() {
        val pattern = DefaultPatterns.all().first()
        val patterns = mapOf(pattern.id to pattern)
        val stroke = InkStroke("s1", (0..20).map { InkPoint(it * 10f, 50f) }, patternId = pattern.id)
        val before = MathNotes.glyphs(listOf(stroke), patterns)
        val pieces = Eraser.erase(stroke, 100f, 50f, 8f, IdGenerator.sequential())!!
        assertEquals(2, pieces.size)
        val after = MathNotes.glyphs(pieces, patterns)
        assertEquals(before.size, after.size)
        assertEquals(1, after.size)
    }

    @Test fun `erasing plain ink does not invent a group`() {
        val stroke = InkStroke("s1", (0..20).map { InkPoint(it * 10f, 50f) })
        val pieces = Eraser.erase(stroke, 100f, 50f, 8f, IdGenerator.sequential())!!
        assertTrue(pieces.all { it.groupId == null })
    }
}
