package io.trazo.core

import io.trazo.core.math.Calculus
import io.trazo.core.math.Evaluator
import io.trazo.core.math.ExactValue
import io.trazo.core.math.Expr
import io.trazo.core.math.FunctionPlot
import io.trazo.core.math.MathParser
import io.trazo.core.math.Rational
import io.trazo.core.math.SymbolicValue
import io.trazo.core.math.TupleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.sin

class AuditMathM2M3Test {

    // --- M2: Separación de elementos por espacio en matrices y matriz 1x1 ---

    @Test
    fun `fila de matriz con negativo separado por espacio se lee como dos elementos y no como resta`() {
        val expr = MathParser.parseExpression("[1 -2]")
        assertEquals(
            Expr.Tuple(listOf(Expr.Num(Rational.of(1)), Expr.Neg(Expr.Num(Rational.of(2))))),
            expr,
        )
        val value = Evaluator.evaluate(expr) { ExactValue.ZERO }
        assertEquals(
            TupleValue(listOf(ExactValue.of(Rational.of(1)), ExactValue.of(Rational.of(-2)))),
            value,
        )
    }

    @Test
    fun `resta con espacios o pegada sigue siendo resta dentro de corchetes`() {
        // [1 - 2] con espacio a ambos lados es la resta 1 - 2 = -1 (un corchete suelto agrupa)
        val exprSpaces = MathParser.parseExpression("[1 - 2]")
        assertEquals(Expr.Bin('-', Expr.Num(Rational.of(1)), Expr.Num(Rational.of(2))), exprSpaces)
        assertEquals(ExactValue.of(Rational.of(-1)), Evaluator.evaluate(exprSpaces) { ExactValue.ZERO })

        // [1-2] sin espacios es la resta 1 - 2 = -1
        val exprNoSpaces = MathParser.parseExpression("[1-2]")
        assertEquals(Expr.Bin('-', Expr.Num(Rational.of(1)), Expr.Num(Rational.of(2))), exprNoSpaces)
        // Como fila de una matriz, también es una resta: [[1 - 2]] es la matriz 1×1 [[-1]].
        assertEquals(
            Expr.Tuple(listOf(Expr.Tuple(listOf(Expr.Bin('-', Expr.Num(Rational.of(1)), Expr.Num(Rational.of(2))))))),
            MathParser.parseExpression("[[1 - 2]]"),
        )
    }

    @Test
    fun `varios elementos separados por espacio con negativos en fila de matriz`() {
        val expr = MathParser.parseExpression("[1 -2 3 -4]")
        val value = Evaluator.evaluate(expr) { ExactValue.ZERO }
        assertEquals(
            TupleValue(listOf(
                ExactValue.of(Rational.of(1)),
                ExactValue.of(Rational.of(-2)),
                ExactValue.of(Rational.of(3)),
                ExactValue.of(Rational.of(-4)),
            )),
            value,
        )
    }

    @Test
    fun `matriz 2x2 con elementos negativos separados por espacio calcula determinante`() {
        val expr = MathParser.parseExpression("[[1 -2]; [3 4]]")
        val value = Evaluator.evaluate(expr) { ExactValue.ZERO }
        val rows = Calculus.rows(value)
        assertEquals(
            listOf(
                listOf(ExactValue.of(Rational.of(1)), ExactValue.of(Rational.of(-2))),
                listOf(ExactValue.of(Rational.of(3)), ExactValue.of(Rational.of(4))),
            ),
            rows,
        )

        val detExpr = MathParser.parseExpression("det([[1 -2]; [3 4]])")
        val detValue = Evaluator.evaluate(detExpr) { ExactValue.ZERO }
        assertEquals(ExactValue.of(Rational.of(10)), detValue)
    }

    @Test
    fun `matriz 1x1 no se colapsa a un escalar`() {
        // Suelto, [a] es un agrupador (2[x+1] es 2(x+1)); [[a]] es una matriz 1x1.
        assertEquals(Expr.Var("a"), MathParser.parseExpression("[a]"))

        val matrix1x1 = MathParser.parseExpression("[[a]]")
        assertEquals(Expr.Tuple(listOf(Expr.Tuple(listOf(Expr.Var("a"))))), matrix1x1)

        val value1x1 = Evaluator.evaluate(matrix1x1) { SymbolicValue.variable(it) }
        val rows = Calculus.rows(value1x1)
        assertEquals(1, rows?.size)
        assertEquals(1, rows?.get(0)?.size)
        assertEquals(SymbolicValue.variable("a"), rows?.get(0)?.get(0))

        val det = Evaluator.evaluate(MathParser.parseExpression("det([[a]])")) { SymbolicValue.variable(it) }
        assertEquals(SymbolicValue.variable("a"), det)
    }

    @Test
    fun `corchete suelto sigue agrupando`() {
        val grouped = Evaluator.evaluate(MathParser.parseExpression("2[3+1]")) { ExactValue.ZERO }
        assertEquals(ExactValue.of(Rational.of(8)), grouped)
    }

    // --- M3: FunctionPlot acepta exponente unario -1 y sin^-1 es arcsin ---

    @Test
    fun `sin^-1 x se compila como arcsin evaluable`() {
        val compiled = FunctionPlot.compile("sin^-1 x")
        assertTrue(compiled is FunctionPlot.Compiled.Ok)
        val fn = (compiled as FunctionPlot.Compiled.Ok).fn

        assertEquals(0.0, fn(0.0), 1e-9)
        assertEquals(asin(0.5), fn(0.5), 1e-9)
        assertEquals(PI / 2.0, fn(1.0), 1e-9)
        assertEquals(-PI / 2.0, fn(-1.0), 1e-9)
    }

    @Test
    fun `sin^-1 con parentesis en argumento o exponente`() {
        val cArgParen = FunctionPlot.compile("sin^-1(x)")
        assertTrue(cArgParen is FunctionPlot.Compiled.Ok)
        val fnArgParen = (cArgParen as FunctionPlot.Compiled.Ok).fn
        assertEquals(asin(0.5), fnArgParen(0.5), 1e-9)

        val cExpParen = FunctionPlot.compile("sin^(-1) x")
        assertTrue(cExpParen is FunctionPlot.Compiled.Ok)
        val fnExpParen = (cExpParen as FunctionPlot.Compiled.Ok).fn
        assertEquals(asin(0.5), fnExpParen(0.5), 1e-9)
    }

    @Test
    fun `sin con superindice menos uno se interpreta como arcsin`() {
        val compiled = FunctionPlot.compile("sin⁻¹ x")
        assertTrue(compiled is FunctionPlot.Compiled.Ok)
        val fn = (compiled as FunctionPlot.Compiled.Ok).fn
        assertEquals(asin(0.5), fn(0.5), 1e-9)
    }

    @Test
    fun `cos^-1 y tan^-1 se interpretan como arccos y arctan`() {
        val cCos = FunctionPlot.compile("cos^-1 x")
        assertTrue(cCos is FunctionPlot.Compiled.Ok)
        val fnCos = (cCos as FunctionPlot.Compiled.Ok).fn
        assertEquals(acos(0.5), fnCos(0.5), 1e-9)

        val cTan = FunctionPlot.compile("tan^-1 x")
        assertTrue(cTan is FunctionPlot.Compiled.Ok)
        val fnTan = (cTan as FunctionPlot.Compiled.Ok).fn
        assertEquals(atan(1.0), fnTan(1.0), 1e-9)

        val cSen = FunctionPlot.compile("sen^-1 x")
        assertTrue(cSen is FunctionPlot.Compiled.Ok)
        val fnSen = (cSen as FunctionPlot.Compiled.Ok).fn
        assertEquals(asin(0.5), fnSen(0.5), 1e-9)
    }

    @Test
    fun `potencias normales de funciones trigonometricas no se rompen`() {
        val cSq = FunctionPlot.compile("sin^2 x")
        assertTrue(cSq is FunctionPlot.Compiled.Ok)
        val fnSq = (cSq as FunctionPlot.Compiled.Ok).fn
        val expected = sin(0.5).let { it * it }
        assertEquals(expected, fnSq(0.5), 1e-9)

        val cSin = FunctionPlot.compile("sin x")
        assertTrue(cSin is FunctionPlot.Compiled.Ok)
        val fnSin = (cSin as FunctionPlot.Compiled.Ok).fn
        assertEquals(sin(0.5), fnSin(0.5), 1e-9)
    }
}
