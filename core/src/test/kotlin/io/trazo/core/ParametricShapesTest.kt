package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Formas dinámicas: medidas independientes, edición en vivo con un solo paso de deshacer y persistencia. */
class ParametricShapesTest {
    private lateinit var c: InkController

    @Before fun setUp() {
        c = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential())
    }

    private fun shape() = c.selectedShape!!

    @Test fun `cuboid edits X, Y and Z independently`() {
        assertNotNull(c.insertParametric(GeoKind.CUBOID, 500f, 500f))
        val before = shape()
        c.setShapeDimension("x", 300f)
        assertEquals(300f, shape().width); assertEquals(before.depth, shape().depth); assertEquals(before.height, shape().height)
        c.setShapeDimension("y", 90f)
        assertEquals(300f, shape().width); assertEquals(90f, shape().depth); assertEquals(before.height, shape().height)
        c.setShapeDimension("z", 40f)
        assertEquals(300f, shape().width); assertEquals(90f, shape().depth); assertEquals(40f, shape().height)
    }

    @Test fun `dimensions clamp to the minimum and circle keeps equal sides`() {
        c.insertParametric(GeoKind.CIRCLE, 500f, 500f)
        c.setShapeDimension("x", 1f)
        assertEquals(ParametricGeometry.MIN_SIZE, shape().width)
        assertEquals(shape().width, shape().height)
    }

    @Test fun `corner radius is limited to half the short side`() {
        c.insertParametric(GeoKind.ROUNDED_RECTANGLE, 500f, 500f)
        c.setShapeDimension("x", 200f); c.setShapeDimension("z", 80f)
        c.setShapeDimension("r", 500f)
        assertEquals(40f, shape().cornerRadius)
    }

    @Test fun `rotation is normalized`() {
        c.insertParametric(GeoKind.RECTANGLE, 500f, 500f)
        c.setShapeRotation(270f)
        assertEquals(-90f, shape().rotationDegrees, 0.01f)
    }

    @Test fun `live slider drag undoes in a single step`() {
        c.insertParametric(GeoKind.RECTANGLE, 500f, 500f)
        val original = shape()
        for (w in 170..260 step 10) c.setShapeDimension("x", w.toFloat(), commit = false)
        assertEquals(260f, shape().width)
        c.commitShapeEdit()
        assertTrue(c.undo())
        assertEquals(original, c.page.findShape(original.id))
        assertTrue(c.redo())
        assertEquals(260f, c.page.findShape(original.id)!!.width)
    }

    @Test fun `delete and undo restore the shape`() {
        val s = c.insertParametric(GeoKind.TRIANGLE, 500f, 500f)!!
        c.deleteShape()
        assertTrue(c.page.shapes.isEmpty())
        assertNull(c.selectedShape)
        c.undo()
        assertEquals(s, c.page.findShape(s.id))
    }

    @Test fun `duplicate creates an offset copy`() {
        val s = c.insertParametric(GeoKind.HEXAGON, 500f, 500f)!!
        val d = c.duplicateShape()!!
        assertEquals(2, c.page.shapes.size)
        assertFalse(d.id == s.id)
        assertEquals(s.origin.x + 24f, d.origin.x)
    }

    @Test fun `clear page removes strokes and shapes in one undoable step`() {
        c.insertPattern(DefaultPatterns.root(), 300f, 300f, 100f)
        c.insertParametric(GeoKind.CUBOID, 500f, 500f)
        val strokes = c.page.strokes.toList(); val shapes = c.page.shapes.toList()
        c.clearPage()
        assertTrue(c.page.strokes.isEmpty() && c.page.shapes.isEmpty())
        c.undo()
        assertEquals(strokes, c.page.strokes); assertEquals(shapes, c.page.shapes)
    }

    @Test fun `convert to ink replaces the shape with plain strokes`() {
        c.insertParametric(GeoKind.CYLINDER, 500f, 500f)
        val ink = c.convertShapeToInk()
        assertTrue(ink.isNotEmpty())
        assertTrue(c.page.shapes.isEmpty())
        assertEquals(ink.map { it.id }, c.page.strokes.map { it.id })
        assertTrue(ink.all { it.patternId == null })
        c.undo()
        assertEquals(1, c.page.shapes.size); assertTrue(c.page.strokes.isEmpty())
    }

    @Test fun `allInk includes the derived ink of shapes`() {
        c.insertParametric(GeoKind.SQUARE, 500f, 500f)
        assertTrue(c.page.strokes.isEmpty())
        assertTrue(c.page.allInk.isNotEmpty())
    }

    @Test fun `every kind renders and round trips through json`() {
        GeoKind.entries.filter { it != GeoKind.FREEFORM }.forEachIndexed { i, k ->
            c.insertParametric(k, 100f + i * 40f, 500f)
        }
        val page = Page("p1", c.page.strokes.toList(), c.page.shapes.toList())
        assertTrue(page.parametricShapes.all { it.render().isNotEmpty() })
        val n = Notebook("nb", "Formas", listOf(page), Paper.GRID, 1L, 2L)
        assertEquals(n, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(n)))
    }

    @Test fun `old notebooks without shapes still load`() {
        val n = Notebook("nb", "Viejo", listOf(Page("p1")), Paper.GRID, 1L, 2L)
        val text = TrazoFormat.encodeNotebook(n).replace(",\"parametricShapes\":[]", "")
        assertFalse(text.contains("parametricShapes"))
        assertEquals(n, TrazoFormat.decodeNotebook(text))
    }
}
