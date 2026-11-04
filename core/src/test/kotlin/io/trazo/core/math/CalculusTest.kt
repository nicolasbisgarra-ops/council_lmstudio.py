package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Derivadas, integrales, vectores y matrices. */
class CalculusTest {
    private fun eval(vararg lines: String): MathValue =
        when (val r = VariableGraph.evaluate(lines.toList(), symbolic = true).last()) {
            is LineResult.Value -> r.value
            else -> error("${lines.last()} → $r")
        }

    private fun shows(expected: String, vararg lines: String) = assertEquals(lines.last(), expected, eval(*lines).display())

    private fun fails(text: String) {
        val r = VariableGraph.evaluate(listOf(text), symbolic = true).single()
        assertTrue("$text → $r", r is LineResult.Failure)
    }

    @Test fun `derivadas de polinomios`() {
        shows("3x² + 2", "d/dx(x^3+2x)=")
        shows("3x² + 2", "d/d x (x^3+2x)")
        shows("-1/(x²)", "d/dx(1/x)")
        shows("2y", "d/dy(x + y^2)")
        // La forma que arma la fracción escrita a mano.
        shows("6x", "((d)/(d x))(3x^2+1)")
        // La letra de la derivada no toma el valor del cuaderno.
        shows("2x", "x = 5", "d/dx(x^2)")
    }

    @Test fun `integrales indefinidas con constante`() {
        shows("x³/3 + C", "∫x^2 dx")
        shows("x² + 3x + C", "∫(2x+3)dx")
        shows("x² + C", "∫ 2xdx")
        fails("∫ 1/x dx")
        fails("∫ x^2")
    }

    @Test fun `integrales definidas`() {
        shows("1/3", "∫_0^1 x^2 dx=")
        shows("6", "∫_(-1)^(2) (2x+1) dx")
        shows("7/3", "∫_1² x^2 dx")
        assertEquals(kotlin.math.ln(2.0), eval("∫_1^2 1/x dx").toDouble(), 1e-9)
        assertEquals(2.0 / 3, eval("∫_0^1 √x dx").toDouble(), 1e-3)
        assertTrue(eval("d/dx(∫x^3 dx)").sameAs(eval("x^3 + 0", "x^3")) || eval("d/dx(∫x^3 dx)").display() == "x³")
    }

    @Test fun `matrices`() {
        shows("((2, 1), (4, 3))", "[[1;2];[3;4]]*[[0;1];[1;0]]=")
        shows("-2", "det([[1;2];[3;4]])")
        shows("-2", "d e t([[1;2];[3;4]])")
        shows("((1, 3), (2, 4))", "[[1;2];[3;4]]^T")
        shows("((-2, 1), (3/2, -1/2))", "[[1;2];[3;4]]^(-1)")
        shows("((7, 10), (15, 22))", "A = [[1;2];[3;4]]", "A^2")
        shows("((2, 4), (6, 8))", "2[[1;2];[3;4]]")
        shows("(5, 11)", "[[1;2];[3;4]]*[1;2]")
        // Escrita a mano: los números de una fila van separados por espacio.
        shows("((1, 2), (3, 4))", "[[1 2];[3 4]]")
        fails("det([[1;2];[2;4]])^(-1) + [[1;2];[2;4]]^(-1)")
        fails("[[1;2];[3;4]]*[[1;2;3]]")
    }

    @Test fun `vectores`() {
        shows("(4, 6)", "[1;2] + [3;4]")
        shows("(k, 2k)", "k*[1;2]")
    }

    @Test fun `el corchete y la integral escritos a mano`() {
        assertEquals("[", MathLayoutConverter.spelling("Abre corchete"))
        assertEquals("]", MathLayoutConverter.spelling("Cierra corchete"))
        assertEquals("∫", MathLayoutConverter.spelling("Integral"))
        val matrix = listOf(
            s("Abre corchete", 0f, -4f, 8f, 60f), s("1", 12f), s("2", 42f), s("3", 12f, 32f), s("4", 42f, 32f),
            s("Cierra corchete", 58f, -4f, 8f, 60f),
        )
        val text = MathLayoutConverter.convert(matrix)
        assertEquals("((1, 2), (3, 4))", eval(text).display())
    }

    private fun s(label: String, x: Float, y: Float = 0f, w: Float = 10f, h: Float = 20f) = LayoutSymbol(label, x, y, x + w, y + h)

    /** ∫ alto con el límite de arriba en su punta y el de abajo al pie, como se escribe en la hoja. */
    private fun definite(lower: List<LayoutSymbol>, upper: List<LayoutSymbol>, integrand: List<LayoutSymbol>) =
        listOf(s("Integral", 0f, -10f, 12f, 50f)) + lower + upper + integrand

    private val xdx = listOf(s("x", 30f, 5f), s("d", 44f, 5f), s("x", 56f, 5f))

    @Test fun `integral definida escrita a mano con límites en las puntas`() {
        val text = MathLayoutConverter.convert(definite(listOf(s("0", 10f, 36f, 6f, 12f)), listOf(s("1", 14f, -12f, 6f, 12f)), xdx))
        assertEquals("∫_(0)^(1)x d x", text)
        shows("1/2", text)
    }

    @Test fun `integral definida con límites arriba y abajo del signo`() {
        val text = MathLayoutConverter.convert(definite(listOf(s("0", 3f, 44f, 6f, 12f)), listOf(s("2", 3f, -26f, 6f, 12f)), xdx))
        shows("2", text)
    }

    @Test fun `integral definida con límites de varias cifras, negativos y un integrando con exponente`() {
        val lower = listOf(s("Menos", 10f, 41f, 5f, 2f), s("1", 16f, 36f, 6f, 12f))
        val upper = listOf(s("1", 14f, -12f, 6f, 12f), s("0", 21f, -12f, 6f, 12f))
        val integrand = listOf(s("x", 34f, 5f), s("2", 45f, -2f, 6f, 10f), s("d", 56f, 5f), s("x", 68f, 5f))
        val text = MathLayoutConverter.convert(definite(lower, upper, integrand))
        assertEquals("∫_(-1)^(10)x^2d x", text)
        shows("1001/3", text)
    }

    @Test fun `una integral sin los dos límites sigue siendo indefinida`() {
        val text = MathLayoutConverter.convert(listOf(s("Integral", 0f, -10f, 12f, 50f)) + xdx)
        assertEquals("∫x d x", text)
        shows("x²/2 + C", text)
    }
}
