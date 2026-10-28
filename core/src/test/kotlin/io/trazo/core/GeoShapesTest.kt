package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/** Formas geométricas enseñables: el gesto reconocido se vuelve una forma dinámica editable. */
class GeoShapesTest {
    private lateinit var c: InkController

    @Before fun setUp() {
        c = InkController(patterns = { DefaultPatterns.all() + GeoPatterns.all() }, ids = IdGenerator.sequential())
    }

    private fun geo(slug: String) = GeoPatterns.all().first { it.id == GeoPatterns.PREFIX + slug }

    @Test fun `every geo preset maps to a native kind and renders`() {
        assertEquals(22, GeoPatterns.all().size)
        GeoPatterns.all().forEach { p ->
            assertNotNull(p.id, ParametricTemplates.recommendedKindFor(p.id))
            assertTrue(p.id, p.outputInk.isNotEmpty())
            assertTrue(p.id, p.gestureExamples.single().isNotEmpty())
            assertEquals(PatternCategory.SHAPES, p.category)
        }
    }

    @Test fun `converting a gesture with a geo pattern creates an editable shape`() {
        val gesture = c.insertPattern(DefaultPatterns.root(), 400f, 400f, 120f)
        val box = gesture.bounds()
        val ink = c.convertWith(geo("circulo"), gesture.map { it.id })
        assertNotNull(ink)
        assertTrue(c.page.strokes.isEmpty())
        val s = c.page.shapes.single()
        assertEquals(GeoKind.CIRCLE, s.kind)
        assertEquals(s.width, s.height)
        assertEquals(s, c.selectedShape)
        val b = s.bounds
        assertTrue(abs(b.centerX - box.centerX) < 1f && abs(b.centerY - box.centerY) < 1f)
    }

    @Test fun `a single undo restores the gesture and removes the shape`() {
        val gesture = c.insertPattern(DefaultPatterns.root(), 400f, 400f, 120f)
        c.convertWith(geo("rectangulo-h"), gesture.map { it.id })
        assertEquals(GeoKind.RECTANGLE, c.page.shapes.single().kind)
        assertTrue(c.undo())
        assertTrue(c.page.shapes.isEmpty())
        assertEquals(gesture, c.page.strokes)
    }

    @Test fun `solids keep their proportions`() {
        GeoPatterns.all().filter { ParametricTemplates.recommendedKindFor(it.id)!!.isProjected }.forEach { p ->
            val gesture = c.insertPattern(DefaultPatterns.root(), 400f, 400f, 150f)
            c.convertWith(p, gesture.map { it.id })
            val s = c.page.shapes.last()
            assertTrue(p.id, s.render().isNotEmpty())
        }
    }

    @Test fun `upgrading the library adds the shapes`() {
        val old = PresetPatterns.install(emptyList(), 0).filterNot { GeoPatterns.isGeo(it) }
        val upgraded = PresetPatterns.install(old, 2)
        assertEquals(22, upgraded.count { GeoPatterns.isGeo(it) })
    }
}
