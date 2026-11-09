package io.trazo.core

import io.trazo.core.fixtures.HandScript
import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScratchOutTest {
    private lateinit var controller: InkController
    private lateinit var pen: SyntheticPen

    @Before fun setUp() {
        controller = InkController(patterns = { emptyList() }, ids = IdGenerator.sequential())
        controller.recognitionEnabled = false
        controller.mathEnabled = false
        pen = SyntheticPen(controller)
    }

    private val strokes get() = controller.page.strokes

    /** Zigzag horizontal apretado sobre la caja ([x0],[y0])–([x1],[y1]). */
    private fun zigzag(x0: Float, y0: Float, x1: Float, y1: Float, passes: Int = 8): List<Pair<Float, Float>> =
        (0..passes).map { i -> (if (i % 2 == 0) x0 else x1) to (y0 + (y1 - y0) * i / passes) }

    private fun writeWord(text: String = "123", x: Float = 100f, y: Float = 100f, size: Float = 40f) =
        HandScript.write(text, x, y, size).first.forEach { pen.stroke(it) }

    @Test fun `zigzag over a word erases it in one undo step`() {
        writeWord()
        val before = strokes.toList()
        assertTrue(before.isNotEmpty())
        pen.stroke(zigzag(95f, 98f, 185f, 142f))
        assertTrue("el tachado y lo tachado desaparecen", strokes.isEmpty())
        assertEquals(1, pen.events.filterIsInstance<InkEvent.ScratchErased>().size)
        assertTrue(controller.undo())
        assertEquals(before.map { it.id }, strokes.map { it.id })
    }

    @Test fun `zigzag on empty paper stays as ink`() {
        pen.stroke(zigzag(100f, 100f, 200f, 150f))
        assertEquals(1, strokes.size)
    }

    @Test fun `zigzag away from the word keeps everything`() {
        writeWord()
        val n = strokes.size
        pen.stroke(zigzag(400f, 100f, 500f, 150f))
        assertEquals(n + 1, strokes.size)
    }

    @Test fun `scratch only erases strokes mostly inside it`() {
        writeWord("123", x = 100f)
        writeWord("456", x = 300f)
        val n = strokes.size
        pen.stroke(zigzag(95f, 98f, 185f, 142f))
        assertTrue(strokes.size in 1 until n)
        assertTrue(strokes.all { it.bounds.left > 250f })
    }

    @Test fun `normal writing over ink is not a scratch`() {
        writeWord("123")
        val n = strokes.size
        // Escribir encima (otra cuenta) no borra nada.
        writeWord("x+8=0", x = 100f, y = 100f, size = 40f)
        assertTrue(strokes.size > n)
        for (c in "0123456789x+-=()") {
            val path = HandScript.write(c.toString(), 0f, 0f, 40f).first
            path.forEach { assertFalse("'$c' parece tachado", ScratchOut.isScratch(SyntheticPen.resample(it, 1f).map { (x, y) -> InkPoint(x, y, 0.5f, 0L) })) }
        }
    }

    @Test fun `highlighter never scratches`() {
        writeWord()
        val n = strokes.size
        controller.brush = Brush.HIGHLIGHTER
        pen.stroke(zigzag(95f, 98f, 185f, 142f))
        assertEquals(n + 1, strokes.size)
    }

    @Test fun `setting off keeps the zigzag as ink`() {
        writeWord()
        val n = strokes.size
        controller.scratchToErase = false
        pen.stroke(zigzag(95f, 98f, 185f, 142f))
        assertEquals(n + 1, strokes.size)
    }

    @Test fun `few passes are not a scratch`() {
        val pts = zigzag(0f, 0f, 100f, 40f, passes = 2).map { (x, y) -> InkPoint(x, y, 0.5f, 0L) }
        assertFalse(ScratchOut.isScratch(SyntheticPen.resample(pts.map { it.x to it.y }, 3f).map { (x, y) -> InkPoint(x, y, 0.5f, 0L) }))
    }
}
