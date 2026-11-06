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

    @Test
    fun fractionHalosSitAboveAndBelowTheBar() {
        val bar = InkStroke(
            "b", pts(100f to 200f, 150f to 200f, 200f to 200f),
            groupId = "F", patternId = DefaultPatterns.FRACTION,
        )
        val halos = Halos.of(listOf(bar), "F")
        assertEquals(listOf(Halo.Kind.NUMERATOR, Halo.Kind.DENOMINATOR), halos.map { it.kind })
        val num = halos.first { it.kind == Halo.Kind.NUMERATOR }.zone
        val den = halos.first { it.kind == Halo.Kind.DENOMINATOR }.zone
        assertTrue("numerador arriba: $num", num.bottom <= bar.bounds.top && num.top < bar.bounds.top)
        assertTrue("denominador abajo: $den", den.top >= bar.bounds.bottom && den.bottom > bar.bounds.bottom)
        assertTrue("ancho con margen: $num", num.left < bar.bounds.left && num.right > bar.bounds.right)
        assertTrue("ancho con margen: $den", den.left < bar.bounds.left && den.right > bar.bounds.right)
        assertTrue(num.contains(150f, 170f))
        assertTrue(den.contains(150f, 230f))
    }

    @Test
    fun fractionNumeratorAndDenominatorCenterWithoutShrinking() {
        val bar = InkStroke(
            "b", pts(100f to 200f, 200f to 200f),
            groupId = "F", patternId = DefaultPatterns.FRACTION,
        )
        val b = bar.bounds
        val writtenNum = listOf(InkStroke("n", pts(110f to 140f, 140f to 180f)))
        val num = Halos.placeLimit(Halo.Kind.NUMERATOR, b, writtenNum).bounds()
        assertEquals(b.centerX, num.centerX, 0.01f)
        assertEquals(30f, num.width, 0.01f)
        assertEquals(40f, num.height, 0.01f)
        assertTrue("piso arriba de la barra: $num", num.bottom < b.top && b.top - num.bottom <= 0.3f * num.height)

        val writtenDen = listOf(InkStroke("d", pts(160f to 220f, 190f to 260f)))
        val den = Halos.placeLimit(Halo.Kind.DENOMINATOR, b, writtenDen).bounds()
        assertEquals(b.centerX, den.centerX, 0.01f)
        assertEquals(30f, den.width, 0.01f)
        assertEquals(40f, den.height, 0.01f)
        assertTrue("techo debajo de la barra: $den", den.top > b.bottom && den.top - b.bottom <= 0.3f * den.height)
    }

    @Test
    fun fractionInControllerCentersPartsAndExtendsBarWhenContentIsWider() {
        val controller = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential())
        val barOut = controller.insertPattern(DefaultPatterns.fraction(), 200f, 200f, 50f)
        val barId = barOut.single().id
        val initialBar = controller.page.find(barId)!!.bounds
        assertEquals(setOf(Halo.Kind.NUMERATOR, Halo.Kind.DENOMINATOR), controller.halos().map { it.kind }.toSet())

        // Numerador más angosto que la barra: se centra arriba sin alargar la barra.
        val numZone = controller.halos().first { it.kind == Halo.Kind.NUMERATOR }.zone
        val ny = numZone.centerY
        controller.penDown(initialBar.left + 5f, ny, 0.8f, 100L, PointerKind.STYLUS)
        controller.penMove(initialBar.left + 15f, ny + 10f, 0.8f, 120L)
        controller.penUp(initialBar.left + 25f, ny + 20f, 0.8f, 140L)
        val numId = controller.page.strokes.last().id
        assertTrue(controller.haloTick(1200L))
        val nb = controller.page.find(numId)!!.bounds
        assertEquals(initialBar.centerX, nb.centerX, 0.5f)
        assertTrue(nb.bottom < initialBar.top)
        assertEquals(initialBar.width, controller.page.find(barId)!!.bounds.width, 0.01f)

        // Denominador más ancho que la barra: se centra abajo y alarga la barra para cubrirlo.
        val denZone = controller.halos().first { it.kind == Halo.Kind.DENOMINATOR }.zone
        val dy = denZone.centerY
        val wideEnd = initialBar.left + initialBar.width + 80f
        controller.penDown(initialBar.left, dy, 0.8f, 2000L, PointerKind.STYLUS)
        controller.penMove((initialBar.left + wideEnd) / 2f, dy + 15f, 0.8f, 2020L)
        controller.penUp(wideEnd, dy + 30f, 0.8f, 2040L)
        val denId = controller.page.strokes.last().id
        assertTrue(controller.haloTick(3100L))
        val db = controller.page.find(denId)!!.bounds
        val grownBar = controller.page.find(barId)!!.bounds
        assertEquals(initialBar.centerX, db.centerX, 0.5f)
        assertTrue(db.top > grownBar.bottom)
        assertTrue("barra cubre al denominador: $grownBar vs $db", grownBar.left <= db.left && grownBar.right >= db.right)
        assertTrue(grownBar.width > initialBar.width)

        // Deshacer revierte el acomodamiento y el alargamiento de la barra juntos.
        assertTrue(controller.undo())
        assertEquals(initialBar.width, controller.page.find(barId)!!.bounds.width, 0.01f)
    }
}
