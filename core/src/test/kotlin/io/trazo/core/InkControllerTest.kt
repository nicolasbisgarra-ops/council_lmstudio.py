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

/**
 * Criterios de aceptación del handoff (05_PRUEBAS_ACEPTACION.md) verificados a nivel motor (JVM),
 * con lápiz sintético. No sustituyen las pruebas instrumentadas ni las físicas con S Pen.
 */
class InkControllerTest {
    private var patterns = DefaultPatterns.all()
    private lateinit var controller: InkController
    private lateinit var pen: SyntheticPen

    @Before fun setUp() {
        controller = InkController(patterns = { patterns }, ids = IdGenerator.sequential())
        pen = SyntheticPen(controller)
    }

    private val strokes get() = controller.page.strokes
    private val converted get() = pen.events.filterIsInstance<InkEvent.Converted>()

    @Test fun `T01 free drawing is stored with points, pressure and time`() {
        pen.stroke(listOf(10f to 10f, 200f to 40f, 300f to 200f))
        assertEquals(1, strokes.size)
        val s = strokes.single()
        assertTrue(s.points.size > 50)
        assertTrue(s.points.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
        assertTrue(s.points.all { it.pressure in 0.05f..1f })
        assertNull(s.patternId)
    }

    @Test fun `T02 integral held 1000 ms while touching converts to output ink only`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100, tremor = 1.5f)
        assertEquals(1, converted.size)
        assertEquals(DefaultPatterns.INTEGRAL, converted.single().pattern.id)
        assertTrue(strokes.isNotEmpty())
        assertTrue("solo tinta de salida", strokes.all { it.patternId == DefaultPatterns.INTEGRAL })
        assertEquals(DefaultPatterns.integral().outputInk.size, strokes.size)
        // La salida es la tinta del patrón (mismos puntos y presión, transformados), no texto.
        assertEquals(DefaultPatterns.integral().outputInk[0].points.size, strokes[0].points.size)
    }

    @Test fun `conversion happens at 1000 ms of stillness, not before`() {
        pen.begin(HandScript.integralGesture(100f, 100f, 150f), stillMs = 950)
        assertTrue(converted.isEmpty())
        pen.advance(80)
        assertEquals(1, converted.size)
        controller.penUp(0f, 0f, 0.5f, pen.nowMs)
    }

    @Test fun `T03 lifting at 900 ms never converts and ink is identical`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 900)
        assertTrue(converted.isEmpty())
        assertEquals(1, strokes.size)
        assertNull(strokes.single().patternId)
        val expected = SyntheticPen.resample(HandScript.integralGesture(100f, 100f, 150f), 3f)
        assertEquals(expected.first().first, strokes.single().points.first().x, 1e-4f)
    }

    @Test fun `T04 touching without drawing changes nothing`() {
        pen.pressAndHold(300f, 300f, 1500)
        assertTrue(strokes.isEmpty())
        assertTrue(converted.isEmpty())
    }

    @Test fun `T05 tremor below radius does not reset the hold`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100, tremor = 3f)
        assertEquals(1, converted.size)
    }

    @Test fun `T06 continuous movement never converts`() {
        // Escritura lenta pero continua durante ~4 s.
        val path = (0..400).map { i -> (50f + i * 2f) to (300f + 20f * kotlin.math.sin(i / 10f)) }
        pen.stroke(path)
        assertTrue(converted.isEmpty())
        assertEquals(1, strokes.size)
    }

    @Test fun `T07 unknown gesture held keeps the original ink`() {
        val zigzag = (0..12).map { i -> (100f + i * 20f) to (if (i % 2 == 0) 100f else 160f) }
        pen.stroke(zigzag, holdMs = 2000)
        assertTrue(converted.isEmpty())
        assertTrue(pen.events.any { it is InkEvent.NotRecognized })
        assertEquals(1, strokes.size)
        assertNull(strokes.single().patternId)
    }

    @Test fun `T08 two similar patterns are not converted automatically and user can choose`() {
        val twin = DefaultPatterns.integral().copy(id = "twin", name = "Gemela")
        patterns = DefaultPatterns.all() + twin
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1200)
        assertTrue(converted.isEmpty())
        val amb = pen.events.filterIsInstance<InkEvent.Ambiguous>().single()
        assertEquals(setOf(DefaultPatterns.INTEGRAL, "twin"), amb.options.map { it.pattern.id }.toSet())
        assertEquals(1, strokes.size)
        assertTrue(controller.chooseAmbiguous(twin, amb.gestureIds))
        assertTrue(strokes.all { it.patternId == "twin" })
        controller.undo()
        assertEquals(1, strokes.size)
        assertNull(strokes.single().patternId)
    }

    @Test fun `T09 custom pattern inserts the user's own drawing`() {
        val gesture = listOf(InkStroke("g", (0..30).map { InkPoint(it * 3f, 0f + (it % 2)) }.let { it }))
        val userOutput = listOf(
            InkStroke("u1", listOf(InkPoint(0f, 0f, 0.3f), InkPoint(40f, 60f, 0.9f), InkPoint(80f, 0f, 0.4f))),
            InkStroke("u2", listOf(InkPoint(0f, 70f, 0.5f), InkPoint(80f, 70f, 0.5f))),
        )
        val custom = InkPattern("mio", "Mío", listOf(gesture), userOutput, sizing = OutputSizing.MATCH_WIDTH)
        patterns = listOf(custom)
        pen.stroke((0..30).map { (300f + it * 4f) to 500f }, holdMs = 1100)
        assertEquals("mio", converted.single().pattern.id)
        assertEquals(2, strokes.size)
        val out = strokes
        // Misma forma que el dibujo del usuario (escala uniforme), presión conservada.
        val s = out[0].points[1].x - out[0].points[0].x
        assertEquals(s / 40f * 60f, out[0].points[1].y - out[0].points[0].y, 0.01f)
        assertEquals(0.9f, out[0].points[1].pressure, 1e-4f)
    }

    @Test fun `T10 two-stroke root is recognised and replaced as vectors`() {
        pen.stroke(listOf(100f to 160f, 112f to 153f, 130f to 195f, 160f to 100f))
        pen.pause(200)
        pen.stroke(listOf(160f to 100f, 200f to 101f, 240f to 100f), holdMs = 1100)
        assertEquals(DefaultPatterns.ROOT, converted.single().pattern.id)
        assertEquals(2, converted.single().removed.size)
        assertEquals(2, strokes.size)
        assertTrue(strokes.all { it.patternId == DefaultPatterns.ROOT })
    }

    @Test fun `T10b one-stroke root works with short and long bars`() {
        for ((i, bar) in listOf(40f, 60f, 90f, 120f, 160f).withIndex()) {
            pen.stroke(HandScript.rootGesture(100f, 100f + i * 200f, 90f, bar), holdMs = 1100)
            pen.pause(2000)
            assertEquals("barra $bar", i + 1, converted.size)
            assertEquals(DefaultPatterns.ROOT, converted.last().pattern.id)
        }
    }

    @Test fun `T11 fraction line converts to handwritten bar`() {
        pen.stroke(HandScript.fractionGesture(100f, 400f, 160f), holdMs = 1100)
        assertEquals(DefaultPatterns.FRACTION, converted.single().pattern.id)
        val bar = strokes.single()
        assertTrue(bar.bounds.width > 150f)
    }

    @Test fun `T12 output can be written over and partially erased`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        val output = strokes.single()
        pen.stroke(listOf(200f to 150f, 240f to 190f)) // escritura nueva encima
        assertEquals(2, strokes.size)
        controller.tool = Tool.ERASER
        val mid = output.points[output.points.size / 2]
        pen.stroke(listOf(mid.x - 2f to mid.y, mid.x + 2f to mid.y))
        val pieces = strokes.filter { it.patternId == DefaultPatterns.INTEGRAL }
        assertEquals("la integral quedó partida en dos tramos", 2, pieces.size)
        assertTrue(strokes.any { it.patternId == null })
        // Deshacer el borrado restaura el trazo exacto.
        controller.undo()
        assertEquals(output, strokes.first { it.patternId != null })
    }

    @Test fun `T13 undo restores the complete original gesture in one action`() {
        pen.stroke(listOf(100f to 160f, 112f to 153f, 130f to 195f, 160f to 100f))
        pen.pause(200)
        pen.stroke(listOf(160f to 100f, 200f to 101f, 240f to 100f), holdMs = 1100)
        val removed = converted.single().removed
        assertTrue(controller.undo())
        assertEquals(removed.map { it.id }, strokes.map { it.id })
        assertEquals(removed, strokes)
    }

    @Test fun `T14 redo restores exactly the output strokes`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        val output = strokes.toList()
        controller.undo()
        assertTrue(controller.redo())
        assertEquals(output, strokes)
    }

    @Test fun `T15 editing a pattern never alters notes already written`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        val before = strokes.toList()
        patterns = patterns.map {
            if (it.id == DefaultPatterns.INTEGRAL) it.copy(outputInk = listOf(InkStroke("new", listOf(InkPoint(0f, 0f), InkPoint(9f, 9f))))) else it
        }
        assertEquals(before, strokes)
    }

    @Test fun `no ghost stroke after conversion while pen stays down`() {
        pen.begin(HandScript.integralGesture(100f, 100f, 150f), stillMs = 1100)
        assertEquals(1, converted.size)
        val after = strokes.toList()
        controller.penMove(400f, 400f, 0.5f, pen.nowMs + 10)
        controller.penMove(450f, 420f, 0.5f, pen.nowMs + 20)
        assertNull(controller.inProgress)
        controller.penUp(450f, 420f, 0.5f, pen.nowMs + 30)
        assertEquals(after, strokes)
    }

    @Test fun `cancel during hold never converts and discards pending ink`() {
        pen.begin(HandScript.integralGesture(100f, 100f, 150f), stillMs = 600)
        controller.cancel()
        pen.advance(1000)
        assertTrue(converted.isEmpty())
        assertTrue(strokes.isEmpty())
    }

    @Test fun `unrelated nearby writing is not swallowed by the gesture`() {
        pen.stroke(listOf(600f to 600f, 700f to 650f)) // lejos
        pen.pause(300)
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        assertEquals(1, converted.single().removed.size)
        assertTrue(strokes.any { it.patternId == null })
    }

    @Test fun `pattern hold override waits longer`() {
        patterns = patterns.map { if (it.id == DefaultPatterns.INTEGRAL) it.copy(holdMillis = 1600L) else it }
        pen.begin(HandScript.integralGesture(100f, 100f, 150f), stillMs = 1200)
        assertTrue(converted.isEmpty())
        pen.advance(500)
        assertEquals(1, converted.size)
        controller.penUp(0f, 0f, 0.5f, pen.nowMs)
    }

    @Test fun `finger input is passed only when the host allows it and hold still works`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100, kind = PointerKind.FINGER)
        assertEquals(1, converted.size)
    }

    @Test fun `stylus eraser end erases regardless of selected tool`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        pen.stroke(listOf(200f to 95f, 200f to 105f), kind = PointerKind.STYLUS_ERASER)
        assertEquals(2, strokes.size)
        assertEquals(Tool.PEN, controller.tool)
    }

    @Test fun `stroke eraser removes whole strokes`() {
        pen.stroke(listOf(100f to 100f, 300f to 100f))
        pen.stroke(listOf(100f to 300f, 300f to 300f))
        controller.tool = Tool.STROKE_ERASER
        pen.stroke(listOf(200f to 95f, 200f to 105f))
        assertEquals(1, strokes.size)
        controller.undo()
        assertEquals(2, strokes.size)
    }

    @Test fun `lasso selects, moves and scales converted ink as one undoable step each`() {
        pen.stroke(HandScript.integralGesture(100f, 100f, 150f), holdMs = 1100)
        pen.stroke(listOf(600f to 600f, 700f to 650f))
        controller.tool = Tool.LASSO
        pen.stroke(listOf(50f to 50f, 300f to 50f, 300f to 300f, 50f to 300f, 50f to 50f))
        assertEquals(1, controller.selection.size)
        val before = controller.selectionBounds!!
        pen.stroke(listOf(before.centerX to before.centerY, before.centerX + 100f to before.centerY + 50f))
        val after = controller.selectionBounds!!
        assertEquals(before.left + 100f, after.left, 1f)
        controller.scaleSelection(2f)
        assertEquals(after.height * 2, controller.selectionBounds!!.height, 1f)
        controller.undo(); controller.undo()
        assertEquals(before, strokes.first { it.patternId != null }.bounds)
    }

    @Test fun `insert pattern from library adds ink`() {
        val out = controller.insertPattern(DefaultPatterns.integral(), 500f, 500f, 120f)
        assertEquals(120f, out.bounds().height, 0.5f)
        assertTrue(controller.undo())
        assertTrue(strokes.isEmpty())
    }

    @Test fun `dwell progress indicator only while a conversion is possible`() {
        pen.begin(HandScript.integralGesture(100f, 100f, 150f), stillMs = 500)
        val p = controller.dwellProgress(pen.nowMs)
        assertTrue(p in 0.4f..0.6f)
        controller.penUp(0f, 0f, 0.5f, pen.nowMs)
        assertEquals(0f, controller.dwellProgress(pen.nowMs))
        assertFalse(controller.isContactActive)
        assertNotNull(strokes.singleOrNull())
    }
}
