package io.trazo.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.sin

class FunctionPlotTest {

    private fun fn(s: String): FunctionPlot.Fn {
        val c = FunctionPlot.compile(s)
        assertTrue("$s → $c", c is FunctionPlot.Compiled.Ok)
        return (c as FunctionPlot.Compiled.Ok).fn
    }

    @Test
    fun compilesHandwrittenStyleFunctions() {
        assertEquals(5.0, fn("2x^2-3")(2.0), 1e-9)
        assertEquals(sin(1.0), fn("y = sin(x)")(1.0), 1e-9)
        assertEquals(1 / E, fn("e^-x")(1.0), 1e-9)
        assertEquals(3.0, fn("|x|")(-3.0), 1e-9)
        assertEquals(3 * sin(2.0), fn("3sin x")(2.0), 1e-9)
        assertEquals(sin(2.0) * sin(2.0), fn("f(x) = sin^2 x")(2.0), 1e-9)
        assertEquals(512.0, fn("2^3^2")(0.0), 1e-9)
        assertEquals(6.0, fn("x(x+1)")(2.0), 1e-9)
        assertEquals(2.0, fn("√(4)")(0.0), 1e-9)
    }

    @Test
    fun reportsErrors() {
        assertTrue(FunctionPlot.compile("") is FunctionPlot.Compiled.Error)
        assertTrue(FunctionPlot.compile("2x+") is FunctionPlot.Compiled.Error)
        assertTrue(FunctionPlot.compile("(x+1") is FunctionPlot.Compiled.Error)
        assertTrue(FunctionPlot.compile("foo(x)") is FunctionPlot.Compiled.Error)
    }

    @Test
    fun sampleBreaksAtAsymptotes() {
        val parts = FunctionPlot.sample(fn("1/x"), -5.0, 5.0, 1001, -5.0, 5.0)
        assertEquals(2, parts.size)
        assertTrue(parts[0].last() < 0f && parts[1][1] > 0f)
        val sqrt = FunctionPlot.sample(fn("sqrt(x)"), -5.0, 5.0, 100, -5.0, 5.0)
        assertEquals(1, sqrt.size)
        assertTrue(sqrt[0][0] >= 0f)
    }

    @Test
    fun axesStepsAndTicks() {
        assertEquals(1.0, FunctionPlot.niceStep(10.0), 1e-12)
        assertEquals(0.2, FunctionPlot.niceStep(1.6), 1e-12)
        assertEquals(50.0, FunctionPlot.niceStep(400.0), 1e-12)
        val t = FunctionPlot.ticks(-2.5, 2.5, 1.0)
        assertEquals(listOf(-2.0, -1.0, 0.0, 1.0, 2.0), t)
        val (lo, hi) = FunctionPlot.fitY(listOf(fn("x^2")), -2.0, 2.0)
        assertTrue(lo < 0.0 && hi > 3.0)
    }
}
