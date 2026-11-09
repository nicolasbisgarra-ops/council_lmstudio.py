package io.trazo.core.math

import io.trazo.core.math.FunctionPlot.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionPlotNotableTest {

    private fun fn(s: String, params: Map<String, Double> = emptyMap()): FunctionPlot.Fn {
        val c = FunctionPlot.compile(s, params)
        assertTrue("$s → $c", c is FunctionPlot.Compiled.Ok)
        return (c as FunctionPlot.Compiled.Ok).fn
    }

    private fun points(kind: Kind, vararg src: String, a: Double = -5.0, b: Double = 5.0): List<FunctionPlot.Notable> =
        FunctionPlot.notablePoints(src.map { fn(it) }, a, b).filter { it.kind == kind }.sortedBy { it.x }

    @Test
    fun rootsOfParabola() {
        val roots = points(Kind.ROOT, "x^2-4")
        assertEquals(2, roots.size)
        assertEquals(-2.0, roots[0].x, 1e-9)
        assertEquals(2.0, roots[1].x, 1e-9)
        assertEquals(0.0, roots[0].y, 1e-12)
        assertEquals(0, roots[0].fn)
    }

    @Test
    fun yInterceptWhenZeroIsInRange() {
        val c = points(Kind.Y_INTERCEPT, "x^2-4")
        assertEquals(1, c.size)
        assertEquals(0.0, c[0].x, 1e-12)
        assertEquals(-4.0, c[0].y, 1e-9)
        assertTrue(points(Kind.Y_INTERCEPT, "x^2-4", a = 1.0, b = 5.0).isEmpty())
    }

    @Test
    fun minimumOfShiftedParabola() {
        val mins = points(Kind.MIN, "(x-1)^2-3")
        assertEquals(1, mins.size)
        assertEquals(1.0, mins[0].x, 1e-6)
        assertEquals(-3.0, mins[0].y, 1e-9)
        assertTrue(points(Kind.MAX, "(x-1)^2-3").isEmpty())
    }

    @Test
    fun maximumOfInvertedParabola() {
        val maxs = points(Kind.MAX, "-x^2+2")
        assertEquals(1, maxs.size)
        assertEquals(0.0, maxs[0].x, 1e-6)
        assertEquals(2.0, maxs[0].y, 1e-9)
        assertTrue(points(Kind.MIN, "-x^2+2").isEmpty())
    }

    @Test
    fun intersectionOfTwoLines() {
        val cuts = points(Kind.INTERSECTION, "x", "2-x")
        assertEquals(1, cuts.size)
        assertEquals(1.0, cuts[0].x, 1e-9)
        assertEquals(1.0, cuts[0].y, 1e-9)
        assertEquals(0, cuts[0].fn)
        assertEquals(1, cuts[0].other)
    }

    @Test
    fun noFalsePointsOnAsymptote() {
        // Con el 0 justo en una muestra y con el polo entre dos muestras.
        for ((a, b) in listOf(-5.0 to 5.0, -4.99 to 5.01)) {
            val all = FunctionPlot.notablePoints(listOf(fn("1/x")), a, b)
            assertTrue("$a..$b → $all", all.none { it.kind == Kind.ROOT || it.kind == Kind.MIN || it.kind == Kind.MAX })
        }
        assertTrue(points(Kind.ROOT, "tan(x)", a = 1.0, b = 2.0).isEmpty())
    }

    @Test
    fun tangentTouchIsARoot() {
        val roots = points(Kind.ROOT, "(x-2)^2")
        assertEquals(1, roots.size)
        assertEquals(2.0, roots[0].x, 1e-6)
    }

    @Test
    fun identicalFunctionsDoNotIntersectEverywhere() {
        assertTrue(points(Kind.INTERSECTION, "x^2", "x^2").isEmpty())
    }

    @Test
    fun resultIsCapped() {
        val all = FunctionPlot.notablePoints(listOf(fn("sin(x)")), -1000.0, 1000.0)
        assertTrue(all.size <= 40)
    }

    @Test
    fun parametersOfExpressions() {
        assertEquals(listOf("a", "b"), FunctionPlot.parameters("a·x²+b"))
        assertEquals(listOf("a", "b"), FunctionPlot.parameters("y = a·x² + b + a"))
        assertEquals(listOf("a"), FunctionPlot.parameters("ax"))
        assertEquals(emptyList<String>(), FunctionPlot.parameters("2π x + e^x - sin(x)"))
        assertEquals(emptyList<String>(), FunctionPlot.parameters("foo(x)"))
    }

    @Test
    fun compileUsesParams() {
        val f = fn("a·x²+b", mapOf("a" to 2.0, "b" to 3.0))
        assertEquals(11.0, f(2.0), 1e-9)
        assertEquals(6.0, fn("a x", mapOf("a" to 3.0))(2.0), 1e-9)
        // Sin valores dados, un parámetro vale 1.
        assertEquals(5.0, fn("a·x²+b")(2.0), 1e-9)
        assertTrue(FunctionPlot.compile("foo(x)") is FunctionPlot.Compiled.Error)
    }
}
