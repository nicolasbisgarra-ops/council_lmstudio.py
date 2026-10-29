package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MathLayoutConverterTest {
    private fun s(label: String, x: Float, y: Float, w: Float = 10f, h: Float = 20f) =
        LayoutSymbol(label, x, y, x + w, y + h)

    @Test fun ordenaYUneDigitos() {
        assertEquals("23+4", MathLayoutConverter.convert(listOf(s("3", 15f, 0f), s("Más", 28f, 0f), s("4", 45f, 0f), s("2", 0f, 0f))))
    }

    @Test fun detectaExponente() {
        assertEquals("x^2", MathLayoutConverter.convert(listOf(s("x", 0f, 12f), s("2", 12f, 0f, 6f, 10f))))
    }

    @Test fun conservaMultiplicacionImplicitaYParentesis() {
        assertEquals("2(x)", MathLayoutConverter.convert(listOf(s("2", 0f, 0f), s("Abre paréntesis", 12f, 0f), s("x", 24f, 0f), s("Cierra paréntesis", 36f, 0f))))
    }

    @Test fun armaFraccion() {
        assertEquals("(1)/(2)", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f))))
    }

    @Test fun agrupaRadicandoDebajoDeLaBarra() {
        assertEquals("√(2+3)", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 40f, 20f), s("2", 12f, 0f), s("Más", 23f, 0f), s("3", 34f, 0f))))
    }

    @Test fun emiteVariasLineas() {
        assertEquals("x+1\ny=2", MathLayoutConverter.convert(listOf(s("x", 0f, 0f), s("Más", 12f, 0f), s("1", 24f, 0f), s("y", 0f, 40f), s("Igual", 12f, 40f), s("2", 28f, 40f))))
    }

    @Test fun extremoAExtremoConParserYEvaluator() {
        val text = MathLayoutConverter.convert(listOf(s("2", 0f, 0f), s("Más", 12f, 0f), s("3", 24f, 0f)))
        val expr = MathParser.parseExpression(text)
        assertEquals(5.0, Evaluator.evaluate(expr) { error("no hay variables") }.toDouble(), 0.0)
    }

    @Test fun etiquetaDesconocidaFallaConPosicion() {
        val error = assertThrows(MathException::class.java) { MathLayoutConverter.convert(listOf(s("garabato", 7f, 0f))) }
        assertEquals(0, (error.error as MathError.Syntax).position)
    }
}
