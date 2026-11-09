package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class RulerTest {
    private val eps = 1e-3f
    private val ruler = Ruler(Ruler.Kind.RULER, 100f, 100f, length = 200f, thickness = 40f)

    @Test fun `ruler snaps to the nearest long edge and clamps at its ends`() {
        val t = ruler.snapTarget(150f, 125f, tolerance = 10f)!!
        assertEquals(Ruler.Target.Edge(20f, -100f, 100f), t)
        val (x, y) = ruler.project(t, 150f, 128f)
        assertEquals(150f, x, eps); assertEquals(120f, y, eps)
        val (ex, _) = ruler.project(t, 400f, 128f)
        assertEquals(200f, ex, eps)
        assertEquals(-20f, (ruler.snapTarget(100f, 95f, 10f) as Ruler.Target.Edge).offset, eps)
        assertNull(ruler.snapTarget(150f, 140f, 10f))
        assertNull(ruler.snapTarget(320f, 100f, 10f))
    }

    @Test fun `rotated ruler projects along its direction`() {
        val r = ruler.copy(angle = (PI / 2).toFloat())
        val t = r.snapTarget(75f, 130f, 10f)!!
        val (x, y) = r.project(t, 78f, 130f)
        assertEquals(80f, x, eps); assertEquals(130f, y, eps)
        assertEquals(90f, r.degrees, eps)
        assertTrue(r.contains(100f, 190f)); assertFalse(r.contains(130f, 100f))
    }

    @Test fun `protractor snaps to arc and base and reads angles`() {
        val p = Ruler(Ruler.Kind.PROTRACTOR, 0f, 0f, radius = 100f)
        assertEquals(Ruler.Target.Arc, p.snapTarget(0f, -95f, 10f))
        val (ax, ay) = p.project(Ruler.Target.Arc, 70f, -70f)
        assertEquals(70.7107f, ax, 1e-2f); assertEquals(-70.7107f, ay, 1e-2f)
        assertEquals(45f, p.angleAt(10f, -10f), 0.01f)
        assertEquals(135f, p.angleAt(-10f, -10f), 0.01f)
        assertTrue(p.snapTarget(30f, 4f, 10f) is Ruler.Target.Edge)
        assertNull(p.snapTarget(0f, -50f, 10f))
        assertNull(p.snapTarget(0f, 60f, 10f))
        assertTrue(p.contains(0f, -50f)); assertFalse(p.contains(0f, 50f))
    }

    @Test fun `two fingers move and rotate it`() {
        val r = ruler.transformed(90f, 100f, 110f, 100f, 100f, 90f, 100f, 110f)
        assertEquals(90f, r.degrees, eps)
        assertEquals(100f, r.cx, eps); assertEquals(100f, r.cy, eps)
        val m = ruler.transformed(0f, 0f, 10f, 0f, 5f, 5f, 15f, 5f)
        assertEquals(105f, m.cx, eps); assertEquals(105f, m.cy, eps); assertEquals(0f, m.degrees, eps)
        assertEquals(45f, ruler.copy(angle = (44.2 * PI / 180).toFloat()).settled().degrees, eps)
    }

    @Test fun `pen stuck to the ruler draws a straight line without recognition`() {
        val page = PageState()
        val c = InkController(page, ids = IdGenerator.sequential())
        c.ruler = ruler
        c.penDown(50f, 124f, 1f, 0L, PointerKind.STYLUS)
        c.penMove(100f, 127f, 1f, 20L)
        c.penMove(160f, 118f, 1f, 40L)
        c.penUp(170f, 122f, 1f, 60L)
        val s = page.strokes.single()
        assertTrue(s.points.all { abs(it.y - 120f) < eps })
        assertEquals(50f, s.points.first().x, eps); assertEquals(170f, s.points.last().x, eps)
        c.penDown(50f, 300f, 1f, 100L, PointerKind.STYLUS)
        c.penMove(80f, 310f, 1f, 120L)
        c.penUp(90f, 330f, 1f, 140L)
        assertEquals(330f, page.strokes.last().points.last().y, eps)
    }

    private fun abs(f: Float) = kotlin.math.abs(f)

    @Test fun `degrees never show 180 or minus zero`() {
        assertEquals("0.0", ruler.copy(angle = PI.toFloat()).degrees.toString())
        assertEquals("0.0", ruler.copy(angle = -0f).degrees.toString())
        assertEquals("0.0", ruler.copy(angle = (179.8 * PI / 180).toFloat()).degrees.toString())
    }
}
