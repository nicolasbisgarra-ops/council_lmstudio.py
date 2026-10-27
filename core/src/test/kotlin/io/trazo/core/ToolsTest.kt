package io.trazo.core

import io.trazo.core.fixtures.HandScript
import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Herramientas: botón lateral del lápiz, puntas, resaltador, formas, estilo de la tinta convertida. */
class ToolsTest {
    private var patterns = DefaultPatterns.core()
    private lateinit var controller: InkController
    private lateinit var pen: SyntheticPen

    @Before fun setUp() {
        controller = InkController(patterns = { patterns }, ids = IdGenerator.sequential())
        pen = SyntheticPen(controller)
    }

    private val strokes get() = controller.page.strokes
    private val converted get() = pen.events.filterIsInstance<InkEvent.Converted>()

    private fun moveAlong(vararg path: Pair<Float, Float>) {
        for ((x, y) in SyntheticPen.resample(path.toList(), 3f).drop(1)) {
            pen.advance(8)
            controller.penMove(x, y, 0.7f, pen.nowMs)
        }
    }

    // ------------------------------------------------------------ botón lateral (S Pen)

    @Test fun `side button erases by default and the pen writes again once released`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        pen.stroke(listOf(200f to 90f, 200f to 110f), kind = PointerKind.STYLUS_BUTTON)
        assertEquals("la línea quedó partida en dos", 2, strokes.size)
        assertTrue(strokes.all { s -> s.points.none { kotlin.math.abs(it.x - 200f) < 10f } })
        pen.stroke(listOf(100f to 300f, 300f to 300f))
        assertEquals(3, strokes.size)
        assertEquals(Tool.PEN, controller.tool)
    }

    @Test fun `side button can select with the lasso or be turned off`() {
        pen.stroke(listOf(100f to 100f, 200f to 100f))
        controller.sideButtonTool = Tool.LASSO
        pen.stroke(listOf(50f to 50f, 250f to 50f, 250f to 150f, 50f to 150f, 50f to 50f), kind = PointerKind.STYLUS_BUTTON)
        assertEquals(1, controller.selection.size)
        assertEquals(1, strokes.size)

        controller.sideButtonTool = null
        pen.stroke(listOf(100f to 400f, 200f to 400f), kind = PointerKind.STYLUS_BUTTON)
        assertEquals("sin herramienta para el botón, escribe", 2, strokes.size)
        assertTrue("escribir fuera quita la selección", controller.selection.isEmpty())
    }

    @Test fun `stylus eraser end can be set to erase whole strokes`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        controller.hardwareEraserTool = Tool.STROKE_ERASER
        pen.stroke(listOf(200f to 95f, 200f to 105f), kind = PointerKind.STYLUS_ERASER)
        assertTrue(strokes.isEmpty())
    }

    @Test fun `pressing the side button mid-stroke keeps what was drawn and starts erasing`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        controller.penDown(50f, 300f, 0.7f, pen.nowMs, PointerKind.STYLUS)
        moveAlong(50f to 300f, 250f to 300f, 250f to 200f)
        controller.changeKind(PointerKind.STYLUS_BUTTON, 250f, 200f, 0.7f, pen.nowMs)
        assertEquals(Tool.ERASER, controller.contactTool)
        moveAlong(250f to 200f, 250f to 80f)
        controller.penUp(250f, 80f, 0.7f, pen.nowMs)

        val written = strokes.filter { s -> s.points.first().y > 250f }
        assertEquals("el trazo escrito se conserva (menos la punta borrada)", 1, written.size)
        assertTrue(written.single().length > 250f)
        assertEquals("la línea quedó partida en dos", 2, strokes.count { s -> s.points.all { it.y < 120f } })

        assertTrue(controller.undo())
        assertEquals("deshacer revierte el borrado entero", 2, strokes.size)
        assertTrue(controller.undo())
        assertEquals(1, strokes.size)
    }

    @Test fun `a side button press right at touch down leaves no dot`() {
        controller.penDown(100f, 100f, 0.7f, pen.nowMs, PointerKind.STYLUS)
        moveAlong(100f to 100f, 102f to 101f)
        controller.changeKind(PointerKind.STYLUS_BUTTON, 102f, 101f, 0.7f, pen.nowMs)
        moveAlong(102f to 101f, 160f to 140f)
        controller.penUp(160f, 140f, 0.7f, pen.nowMs)
        assertTrue(strokes.isEmpty())
        assertFalse(controller.history.canUndo)
    }

    @Test fun `releasing the side button keeps erasing until the pen lifts`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        controller.penDown(150f, 40f, 0.7f, pen.nowMs, PointerKind.STYLUS_BUTTON)
        moveAlong(150f to 40f, 150f to 70f)
        controller.changeKind(PointerKind.STYLUS, 150f, 70f, 0.7f, pen.nowMs)
        assertEquals(Tool.ERASER, controller.contactTool)
        moveAlong(150f to 70f, 150f to 130f)
        controller.penUp(150f, 130f, 0.7f, pen.nowMs)
        assertEquals(2, strokes.size)
        assertNull(controller.contactTool)
    }

    // ------------------------------------------------------------ puntas y resaltador

    @Test fun `brush and color are taken at touch down`() {
        pen.begin(listOf(100f to 100f, 200f to 100f))
        controller.color = RED
        controller.brush = Brush.PENCIL
        controller.penUp(200f, 100f, 0.7f, pen.nowMs)
        assertEquals(DEFAULT_INK_COLOR, strokes.single().color)
        assertEquals(Brush.PEN, strokes.single().brush)
        pen.stroke(listOf(100f to 200f, 200f to 200f))
        assertEquals(RED, strokes.last().color)
        assertEquals(Brush.PENCIL, strokes.last().brush)
    }

    @Test fun `highlighter never converts and never joins a gesture`() {
        controller.brush = Brush.HIGHLIGHTER
        controller.color = YELLOW
        controller.width = 24f
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1500)
        assertTrue(converted.isEmpty())
        val hl = strokes.single()
        assertEquals(Brush.HIGHLIGHTER, hl.brush)
        assertFalse(hl.isHandwriting)

        val current = InkStroke("c", listOf(InkPoint(120f, 120f), InkPoint(160f, 160f, 1f, 50L)), startTime = hl.endTime + 100)
        val groups = controller.recognizer.candidateGroups(current, strokes)
        assertTrue(groups.none { g -> g.any { it.id == hl.id } })
    }

    @Test fun `dwell indicator stays off while highlighting`() {
        controller.brush = Brush.HIGHLIGHTER
        pen.begin(HandScript.integralGesture(100f, 100f, 150f), stillMs = 600)
        assertEquals(0f, controller.dwellProgress(pen.nowMs))
        controller.penUp(0f, 0f, 0.5f, pen.nowMs)
    }

    // ------------------------------------------------------------ estilo de la tinta convertida

    @Test fun `converted ink takes the color, width and brush it was written with`() {
        controller.color = RED
        controller.width = 6f
        controller.brush = Brush.BALLPOINT
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        assertEquals(1, converted.size)
        assertTrue(strokes.all { it.color == RED && it.brush == Brush.BALLPOINT })
        val expected = DefaultPatterns.integral().outputInk.map { it.width * 2f }
        assertEquals(expected.size, strokes.size)
        expected.zip(strokes).forEach { (w, s) -> assertEquals(w, s.width, 0.001f) }
    }

    @Test fun `output ink taught in its own color keeps it`() {
        val p = DefaultPatterns.integral().let { it.copy(id = "azul", outputInk = it.outputInk.map { s -> s.copy(color = BLUE) }) }
        patterns = listOf(p)
        controller.color = RED
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        assertEquals(1, converted.size)
        assertTrue(strokes.all { it.color == BLUE })
    }

    @Test fun `inserted patterns use the current ink but never the highlighter`() {
        controller.color = RED
        val a = controller.insertPattern(DefaultPatterns.root(), 300f, 300f, 100f)
        assertTrue(a.all { it.color == RED })
        controller.brush = Brush.HIGHLIGHTER
        controller.color = YELLOW
        val b = controller.insertPattern(DefaultPatterns.root(), 600f, 600f, 100f)
        assertTrue(b.all { it.color == DEFAULT_INK_COLOR && it.brush == Brush.PEN })
    }

    // ------------------------------------------------------------ formas

    @Test fun `shape tool drags a rectangle as one undoable grouped stroke`() {
        controller.tool = Tool.SHAPE
        controller.shape = ShapeKind.RECTANGLE
        controller.color = RED
        pen.begin(listOf(100f to 100f, 300f to 200f))
        assertEquals("vista previa mientras se arrastra", 1, controller.wetInk.size)
        assertTrue(strokes.isEmpty())
        controller.penUp(300f, 200f, 0.7f, pen.nowMs)

        val r = strokes.single()
        assertEquals(100f, r.bounds.left, 0.01f)
        assertEquals(300f, r.bounds.right, 0.01f)
        assertEquals(200f, r.bounds.bottom, 0.01f)
        assertEquals(RED, r.color)
        assertNotNull(r.groupId)
        assertFalse("una forma nunca es parte de un gesto", r.isHandwriting)
        assertTrue(controller.wetInk.isEmpty())
        assertTrue(controller.undo())
        assertTrue(strokes.isEmpty())
    }

    @Test fun `a tap with the shape tool leaves nothing`() {
        controller.tool = Tool.SHAPE
        pen.stroke(listOf(100f to 100f, 103f to 102f))
        assertTrue(strokes.isEmpty())
        assertFalse(controller.history.canUndo)
    }

    @Test fun `shapes snap lines, squares and circles and group multi-stroke shapes`() {
        controller.tool = Tool.SHAPE
        controller.shape = ShapeKind.LINE
        pen.stroke(listOf(100f to 100f, 300f to 110f))
        val line = strokes.single()
        assertEquals("casi horizontal → horizontal", line.points.first().y, line.points.last().y, 0.01f)

        controller.shape = ShapeKind.AXES
        pen.stroke(listOf(400f to 400f, 600f to 600f))
        val axes = strokes.drop(1)
        assertEquals(4, axes.size)
        assertEquals(1, axes.map { it.groupId }.toSet().size)

        controller.shape = ShapeKind.ARROW
        pen.stroke(listOf(100f to 700f, 300f to 700f))
        assertEquals(2, strokes.drop(5).size)

        val circle = Shapes.paths(ShapeKind.ELLIPSE, 0f, 0f, 100f, 104f).single()
        val w = circle.maxOf { it.first } - circle.minOf { it.first }
        val h = circle.maxOf { it.second } - circle.minOf { it.second }
        assertEquals("casi redonda → círculo", w, h, 0.5f)
        val ellipse = Shapes.paths(ShapeKind.ELLIPSE, 0f, 0f, 100f, 60f).single()
        assertEquals(60f, ellipse.maxOf { it.second } - ellipse.minOf { it.second }, 0.5f)
    }

    // ------------------------------------------------------------ selección con la pluma

    @Test fun `the pen drags a selection and a tap outside only deselects`() {
        val out = controller.insertPattern(DefaultPatterns.root(), 300f, 300f, 100f)
        controller.select(out.map { it.id })
        val b = controller.selectionBounds!!
        pen.stroke(listOf(b.centerX to b.centerY, b.centerX + 100f to b.centerY))
        assertEquals(b.left + 100f, controller.selectionBounds!!.left, 1f)
        assertEquals("arrastrar no deja tinta", out.size, strokes.size)

        pen.stroke(listOf(700f to 700f))
        assertTrue(controller.selection.isEmpty())
        assertEquals("el toque que quita la selección no deja un punto", out.size, strokes.size)

        pen.stroke(listOf(700f to 700f, 800f to 700f))
        assertEquals(out.size + 1, strokes.size)
        assertTrue(controller.undo()); assertTrue(controller.undo())
        assertEquals(b, strokes.bounds())
    }

    @Test fun `writing outside a selection deselects and keeps the stroke`() {
        val out = controller.insertPattern(DefaultPatterns.root(), 300f, 300f, 100f)
        controller.select(out.map { it.id })
        pen.stroke(listOf(700f to 700f, 800f to 700f))
        assertTrue(controller.selection.isEmpty())
        assertEquals(out.size + 1, strokes.size)
    }

    @Test fun `pan tool leaves the page alone`() {
        controller.tool = Tool.PAN
        controller.penDown(100f, 100f, 0.7f, pen.nowMs, PointerKind.STYLUS)
        assertEquals(Tool.PAN, controller.contactTool)
        moveAlong(100f to 100f, 300f to 100f)
        controller.penUp(300f, 100f, 0.7f, pen.nowMs)
        assertTrue(strokes.isEmpty())
        assertNull(controller.contactTool)
    }

    @Test fun `pressing the side button while panning starts erasing`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        controller.tool = Tool.PAN
        controller.penDown(200f, 40f, 0.7f, pen.nowMs, PointerKind.STYLUS)
        controller.changeKind(PointerKind.STYLUS_BUTTON, 200f, 40f, 0.7f, pen.nowMs)
        assertEquals(Tool.ERASER, controller.contactTool)
        moveAlong(200f to 40f, 200f to 160f)
        controller.penUp(200f, 160f, 0.7f, pen.nowMs)
        assertEquals(2, strokes.size)
    }

    // ------------------------------------------------------------ formato

    @Test fun `brush and category are saved and old files load with defaults`() {
        val s = InkStroke("a", listOf(InkPoint(1f, 2f)), brush = Brush.CALLIGRAPHY)
        val nb = Notebook("n", "N", listOf(Page("p", listOf(s))))
        assertEquals(Brush.CALLIGRAPHY, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(nb)).pages[0].strokes[0].brush)

        val old = """{"format":"trazo-notebook","schemaVersion":1,"notebook":{"id":"n","name":"N","pages":[{"id":"p","strokes":[{"id":"a","points":[{"x":1,"y":2}]}]}]}}"""
        assertEquals(Brush.PEN, TrazoFormat.decodeNotebook(old).pages[0].strokes[0].brush)

        val p = DefaultPatterns.root().copy(category = PatternCategory.CALCULUS)
        assertEquals(PatternCategory.CALCULUS, TrazoFormat.decodePatterns(TrazoFormat.encodePatterns(listOf(p))).single().category)
        assertEquals(PatternCategory.MINE, PatternCategory.of(p.copy(category = null)))
    }

    private companion object {
        val RED = 0xFFD32F2F.toInt()
        val BLUE = 0xFF1565C0.toInt()
        val YELLOW = 0xFFFFEB3B.toInt()
    }
}
