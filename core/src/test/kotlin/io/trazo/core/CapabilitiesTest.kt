package io.trazo.core

import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Enseñar gestos desde la hoja, edición de selección, páginas y fusión de bibliotecas. */
class CapabilitiesTest {
    private var patterns = DefaultPatterns.all()
    private lateinit var controller: InkController
    private lateinit var pen: SyntheticPen

    @Before fun setUp() {
        controller = InkController(patterns = { patterns }, ids = IdGenerator.sequential())
        pen = SyntheticPen(controller)
    }

    private val strokes get() = controller.page.strokes
    private val converted get() = pen.events.filterIsInstance<InkEvent.Converted>()

    private fun zigzag(x: Float, y: Float, step: Float = 20f, amp: Float = 60f) =
        (0..12).map { i -> (x + i * step) to (if (i % 2 == 0) y else y + amp) }

    private fun st(id: String, x: Float, y: Float, w: Float = 10f, h: Float = 10f, group: String? = null, start: Long = 0L) =
        InkStroke(id, listOf(InkPoint(x, y, 0.4f, 0L), InkPoint(x + w, y + h, 0.9f, 30L)), color = 7, width = 2.5f,
            startTime = start, groupId = group, patternId = group?.let { "p" })

    private fun pattern(id: String = "zz", name: String = "Zigzag", example: List<InkStroke> = listOf(st("g", 0f, 0f, 50f, 50f))) =
        InkPattern(id, name, listOf(example), listOf(st("o", 0f, 0f, 40f, 10f)), sizing = OutputSizing.FIT_GESTURE)

    // ------------------------------------------------------------ ejemplos de gesto

    @Test fun `normalize translates to margin and clears ids, groups and times`() {
        val g = listOf(st("a", 100f, 200f, group = "x", start = 5_000L), st("b", 130f, 210f, start = 5_400L))
        val n = GestureExamples.normalize(g)
        assertEquals(listOf("ex-1", "ex-2"), n.map { it.id })
        assertEquals(Bounds(20f, 20f, 60f, 40f), n.bounds())
        assertEquals(listOf(0L, 400L), n.map { it.startTime })
        assertTrue(n.all { it.groupId == null && it.patternId == null })
        assertTrue(n.all { it.color == 7 && it.width == 2.5f })
        assertEquals(g[0].points.map { it.pressure to it.timeMs }, n[0].points.map { it.pressure to it.timeMs })
    }

    @Test fun `normalize scales large gestures uniformly to fit the box`() {
        val n = GestureExamples.normalize(listOf(st("a", -50f, 0f, w = 720f, h = 360f)))
        val b = n.bounds()
        assertEquals(20f, b.left, 1e-3f); assertEquals(20f, b.top, 1e-3f)
        assertEquals(360f, b.width, 1e-3f); assertEquals(180f, b.height, 1e-3f)
        assertEquals(2.5f, n.single().width)
    }

    @Test fun `withGestureExample appends, caps keeping first and most recent and sets updatedAt`() {
        val original = pattern()
        val one = original.withGestureExample(listOf(st("n1", 300f, 300f)), now = 42L)
        assertEquals(2, one.gestureExamples.size)
        assertEquals(42L, one.updatedAt)
        assertEquals("ex-1", one.gestureExamples[1].single().id)
        assertEquals(1, original.gestureExamples.size)
        assertEquals(0L, original.updatedAt)

        var p = original
        for (i in 1..10) p = p.withGestureExample(listOf(st("n$i", 0f, 0f, w = i * 10f)), maxExamples = 4)
        assertEquals(4, p.gestureExamples.size)
        assertEquals(original.gestureExamples[0], p.gestureExamples[0])
        assertEquals(listOf(80f, 90f, 100f), p.gestureExamples.drop(1).map { it.single().bounds.width })
        assertEquals(InkPattern.MAX_EXAMPLES, 8)
    }

    // ------------------------------------------------------------ aprender desde la hoja

    @Test fun `unrecognized gesture can be converted, then taught and recognized automatically`() {
        pen.stroke(zigzag(100f, 100f), holdMs = 1100)
        val nr = pen.events.filterIsInstance<InkEvent.NotRecognized>().single()
        val current = strokes.single()
        assertEquals(listOf(current.id), nr.candidates[0])
        assertEquals(100f, nr.anchor.left, 1e-3f)
        assertEquals(160f, nr.anchor.bottom, 1e-3f)

        val custom = pattern()
        val out = controller.convertWith(custom, nr.candidates[0])
        assertNotNull(out)
        assertEquals(out, strokes)
        assertTrue(strokes.all { it.patternId == "zz" })
        assertEquals(1f, converted.single().score)
        assertTrue(controller.undo())
        assertEquals(listOf(current), strokes)

        val taught = custom.withGestureExample(controller.strokesById(nr.candidates[0])!!)
        patterns = DefaultPatterns.all() + taught
        pen.pause(3_000)
        pen.stroke(zigzag(450f, 600f, step = 22f, amp = 70f), holdMs = 1100)
        assertEquals(2, converted.size)
        assertEquals("zz", converted.last().pattern.id)
        assertEquals(current, strokes.first())
        assertTrue(strokes.drop(1).all { it.patternId == "zz" })
    }

    @Test fun `not recognized without enabled patterns still offers candidates`() {
        patterns = emptyList()
        pen.stroke(zigzag(100f, 100f), holdMs = 1100)
        val nr = pen.events.filterIsInstance<InkEvent.NotRecognized>().single()
        assertEquals(listOf(listOf(strokes.single().id)), nr.candidates)
    }

    @Test fun `not recognized candidates include a previous nearby stroke`() {
        pen.stroke(listOf(90f to 80f, 120f to 80f))
        val previous = strokes.single().id
        pen.stroke(zigzag(100f, 100f), holdMs = 1100)
        val nr = pen.events.filterIsInstance<InkEvent.NotRecognized>().single()
        val current = strokes.last().id
        assertEquals(listOf(listOf(current), listOf(previous, current)), nr.candidates)
    }

    @Test fun `convertWith returns null when a stroke is missing or a contact is active`() {
        pen.stroke(zigzag(100f, 100f))
        val id = strokes.single().id
        assertNull(controller.convertWith(pattern(), listOf(id, "nope")))
        assertNull(controller.strokesById(listOf(id, "nope")))
        pen.begin(listOf(500f to 500f, 600f to 600f))
        assertNull(controller.convertWith(pattern(), listOf(id)))
        assertFalse(controller.chooseAmbiguous(pattern(), listOf(id)))
        assertEquals(listOf(id), controller.strokesById(listOf(id))!!.map { it.id })
        controller.cancel()
        assertTrue(controller.chooseAmbiguous(pattern(), listOf(id)))
    }

    // ------------------------------------------------------------ selección

    private fun withPage(vararg s: InkStroke) {
        controller.attach(PageState(s.toList()), History())
    }

    @Test fun `duplicateSelection copies with new ids, consistent groups and offset`() {
        withPage(st("a", 0f, 0f, group = "g1"), st("b", 20f, 0f, group = "g1"), st("c", 40f, 0f), st("d", 60f, 0f, group = "g2"))
        controller.select(listOf("a", "b", "c", "d"))
        val ids = controller.duplicateSelection(dx = 10f, dy = 5f)
        assertEquals(4, ids.size)
        assertEquals(ids, controller.selection)
        assertEquals(8, strokes.size)
        val copies = controller.strokesById(ids)!!
        val originals = strokes.take(4)
        assertTrue(copies.none { c -> originals.any { it.id == c.id } })
        assertEquals(copies[0].groupId, copies[1].groupId)
        assertNotNull(copies[0].groupId)
        assertTrue(copies[0].groupId !in setOf("g1", "g2"))
        assertNull(copies[2].groupId)
        assertNotEquals(copies[0].groupId, copies[3].groupId)
        originals.zip(copies).forEach { (o, c) ->
            assertEquals(o.bounds.left + 10f, c.bounds.left, 1e-4f)
            assertEquals(o.bounds.top + 5f, c.bounds.top, 1e-4f)
            assertEquals(o.width, c.width)
            assertEquals(o.patternId, c.patternId)
        }
        assertTrue(controller.undo())
        assertEquals(originals, strokes)
    }

    @Test fun `recolorSelection changes color in one undoable step`() {
        withPage(st("a", 0f, 0f), st("b", 20f, 0f), st("c", 40f, 0f))
        val before = strokes.toList()
        controller.select(listOf("a", "c"))
        controller.recolorSelection(0xFFFF0000.toInt())
        assertEquals(listOf("a", "b", "c"), strokes.map { it.id })
        assertEquals(listOf(0xFFFF0000.toInt(), 7, 0xFFFF0000.toInt()), strokes.map { it.color })
        assertTrue(controller.undo())
        assertEquals(before, strokes)
        assertFalse(controller.history.canUndo)
    }

    @Test fun `selectAll selects every stroke`() {
        withPage(st("a", 0f, 0f), st("b", 20f, 0f))
        controller.selectAll()
        assertEquals(listOf("a", "b"), controller.selection)
    }

    // ------------------------------------------------------------ páginas

    private fun notebook() = Notebook("n", "Cuaderno", listOf(
        Page("p1", listOf(st("a", 0f, 0f, group = "g"), st("b", 10f, 0f, group = "g"), st("c", 20f, 0f))),
        Page("p2"),
        Page("p3"),
    ))

    @Test fun `remove page`() {
        assertEquals(listOf("p1", "p3"), notebook().withPageRemoved(1).pages.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { notebook().withPageRemoved(3) }
        val single = Notebook("n", "x", listOf(Page("p")))
        assertThrows(IllegalArgumentException::class.java) { single.withPageRemoved(0) }
    }

    @Test fun `duplicate page is a deep copy with remapped ids`() {
        val n = notebook().withPageDuplicated(0, IdGenerator.sequential("d"))
        assertEquals(listOf("p1", "d1", "p2", "p3"), n.pages.map { it.id })
        val src = n.pages[0].strokes
        val dup = n.pages[1].strokes
        assertEquals(3, dup.size)
        assertTrue(dup.map { it.id }.intersect(src.map { it.id }.toSet()).isEmpty())
        assertEquals(dup[0].groupId, dup[1].groupId)
        assertNotEquals("g", dup[0].groupId)
        assertNull(dup[2].groupId)
        assertEquals(src.map { it.points }, dup.map { it.points })
        assertEquals(src.map { it.patternId }, dup.map { it.patternId })
        assertEquals(notebook().pages[0], n.pages[0])
    }

    @Test fun `move page`() {
        assertEquals(listOf("p2", "p3", "p1"), notebook().withPageMoved(0, 2).pages.map { it.id })
        assertEquals(listOf("p3", "p1", "p2"), notebook().withPageMoved(2, 0).pages.map { it.id })
        assertEquals(listOf("p1", "p2", "p3"), notebook().withPageMoved(1, 1).pages.map { it.id })
    }

    // ------------------------------------------------------------ biblioteca

    @Test fun `pattern library merge skips identical, renames conflicts and appends new`() {
        val a = pattern("a", "A")
        val b = pattern("b", "B")
        val existing = listOf(a, b)
        val incoming = listOf(
            a.copy(updatedAt = 99L, enabled = false),
            b.copy(outputScale = 2f),
            pattern("c", "C"),
        )
        val merged = PatternLibrary.merge(existing, incoming, IdGenerator.sequential("m"))
        assertEquals(listOf("a", "b", "m1", "c"), merged.map { it.id })
        assertEquals("B (importado)", merged[2].name)
        assertEquals(2f, merged[2].outputScale)
        assertEquals(existing, merged.take(2))
        assertEquals(merged, PatternLibrary.merge(merged, incoming, IdGenerator.sequential("x")))
    }
}
