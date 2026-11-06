package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFitAndHalosTest {

    private fun pts(vararg xy: Pair<Float, Float>) = xy.mapIndexed { i, (x, y) -> InkPoint(x, y, timeMs = i * 10L) }

    private val gesture = listOf(InkStroke("g", pts(100f to 155f, 112f to 150f, 127f to 179f, 147f to 110f, 260f to 110f)))

    @Test
    fun rootKeepsTheHandDrawnCheckAndStaysWithinTheGesture() {
        val out = checkNotNull(RootFit.fit(DefaultPatterns.root(), gesture, IdGenerator.sequential(), 0L, null))
        assertEquals(2, out.size)
        val g = gesture.flatMap { it.points }.let { Bounds.of(it) }
        val all = out.bounds()
        // No se agranda: queda dentro del gesto (con un margen de suavizado mínimo).
        assertTrue("ancho $all vs $g", all.left >= g.left - 1f && all.right <= g.right + 1f)
        assertTrue("alto $all vs $g", all.top >= g.top - 1f && all.bottom <= g.bottom + 1f)
        // El ✓ empieza donde empezó la mano y la barra es recta y sale de la punta.
        val check = out.first()
        assertEquals(100f, check.points.first().x, 0.01f)
        assertEquals(155f, check.points.first().y, 0.01f)
        val bar = out.last()
        assertTrue(bar.points.all { it.y == bar.points.first().y })
        assertEquals(260f, bar.bounds.right, 0.5f)
        assertEquals(DefaultPatterns.ROOT, check.patternId)
        assertEquals(check.groupId, bar.groupId)
        assertEquals(bar, RootFit.barOf(out))
    }

    @Test
    fun rootFitFallsBackWhenTheGestureIsNotACheck() {
        val flat = listOf(InkStroke("f", pts(0f to 10f, 50f to 10f, 100f to 12f, 150f to 11f)))
        assertNull(RootFit.fit(DefaultPatterns.root(), flat, IdGenerator.sequential(), 0L, null))
    }

    @Test
    fun rootHaloSitsUnderTheBarAndTheBarGrows() {
        val out = checkNotNull(RootFit.fit(DefaultPatterns.root(), gesture, IdGenerator.sequential(), 0L, null))
        val halos = Halos.of(out, out.first().groupId!!)
        assertEquals(listOf(Halo.Kind.RADICAND), halos.map { it.kind })
        val zone = halos.single().zone
        assertTrue(zone.contains(200f, 140f))
        val bar = RootFit.barOf(out)!!
        val longer = checkNotNull(Halos.extendBar(bar, 320f))
        assertEquals(320f, longer.bounds.right, 0.01f)
        assertEquals(bar.bounds.left, longer.bounds.left, 0.01f)
        assertNull(Halos.extendBar(bar, 200f))
    }

    @Test
    fun integralLimitsShrinkAndMoveNextToTheIntegral() {
        val integral = InkStroke(
            "i", pts(60f to 0f, 50f to 10f, 45f to 50f, 40f to 95f, 30f to 100f),
            groupId = "G", patternId = DefaultPatterns.INTEGRAL,
        )
        val halos = Halos.of(listOf(integral), "G")
        assertEquals(setOf(Halo.Kind.UPPER_LIMIT, Halo.Kind.LOWER_LIMIT), halos.map { it.kind }.toSet())
        val b = integral.bounds
        // Un "2" grande escrito en el halo de arriba.
        val written = listOf(InkStroke("w", pts(70f to -40f, 100f to -40f, 70f to 20f, 100f to 20f)))
        val upper = Halos.placeLimit(Halo.Kind.UPPER_LIMIT, b, written)
        val u = upper.bounds()
        assertTrue("achicado: $u", u.height <= 0.3f * b.height + 0.01f)
        assertTrue("a la derecha: $u", u.left >= b.right && u.left <= b.right + 0.1f * b.height)
        assertTrue("arriba: $u", u.bottom <= b.top + 0.3f * b.height)
        val lower = Halos.placeLimit(Halo.Kind.LOWER_LIMIT, b, written).bounds()
        assertTrue("abajo: $lower", lower.top >= b.bottom - 0.25f * b.height && lower.left < b.right)
        // Un segundo bloque del mismo extremo va a la derecha del primero.
        val next = Halos.placeLimit(Halo.Kind.UPPER_LIMIT, b, written, after = u.right).bounds()
        assertTrue(next.left > u.right)
    }
}
