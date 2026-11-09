package io.trazo.core

import kotlin.math.sin
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame

class HighlighterLineTest {
    private fun stroke(vararg xy: Pair<Float, Float>) = InkStroke(
        "h", xy.mapIndexed { i, (x, y) -> InkPoint(x, y, 1f, i * 10L) }, width = 8f, brush = Brush.HIGHLIGHTER,
    )

    @Test
    fun `a slightly shaky horizontal pass becomes a straight horizontal line`() {
        val s = stroke(*Array(20) { i -> i * 10f to 100f + (if (i % 2 == 0) 1.5f else -1.5f) + i * 0.1f })
        val out = HighlighterLine.straighten(s)
        assertEquals(2, out.points.size)
        assertEquals(out.points[0].y, out.points[1].y, 0f)
        assertEquals(0f, out.points[0].x, 0f)
        assertEquals(190f, out.points[1].x, 0f)
        assertEquals(190L, out.points[1].timeMs)
    }

    @Test
    fun `a diagonal stroke stays diagonal`() {
        val out = HighlighterLine.straighten(stroke(*Array(10) { i -> i * 10f to i * 5f }))
        assertEquals(2, out.points.size)
        assertEquals(45f, out.points[1].y, 0f)
    }

    @Test
    fun `wavy or short strokes are kept as drawn`() {
        val wavy = stroke(*Array(40) { i -> i * 5f to 100f + 25f * sin(i / 3f) })
        assertSame(wavy, HighlighterLine.straighten(wavy))
        val short = stroke(0f to 0f, 5f to 0f, 10f to 0f)
        assertSame(short, HighlighterLine.straighten(short))
        val back = stroke(0f to 0f, 200f to 0f, 100f to 0f)
        assertSame(back, HighlighterLine.straighten(back))
    }
}
