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

    @Test fun fraccionComoDivisorMantieneAgrupacion() {
        val entrada = listOf(s("8", 0f, 11f), s("Dividido", 12f, 11f)) + listOf(s("1", 28f, 0f), s("Barra", 24f, 20f, 24f, 2f), s("2", 28f, 27f))
        assertEquals("8/((1)/(2))", MathLayoutConverter.convert(entrada))
    }

    @Test fun barraExteriorSeResuelveAntesQueLaInterior() {
        assertEquals("((1)/(2))/(3)", MathLayoutConverter.convert(listOf(s("Barra", 0f, 38f, 40f, 2f), s("1", 4f, 0f), s("Barra", 0f, 14f, 24f, 2f), s("2", 4f, 21f), s("3", 12f, 46f))))
    }

    @Test fun fraccionDentroDeRaiz() {
        assertEquals("√((1)/(2))", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 50f, 60f), s("1", 20f, 0f), s("Barra", 15f, 25f, 30f, 2f), s("2", 20f, 35f))))
    }

    @Test fun noAsociaExponentesLejanos() {
        assertEquals("2\nx", MathLayoutConverter.convert(listOf(s("2", 100f, 0f, 6f, 10f), s("x", 0f, 100f))))
    }

    @Test fun menosNoEsBarraDeFraccion() {
        assertEquals("2-1", MathLayoutConverter.convert(listOf(s("2", 0f, 0f), s("Menos", 12f, 9f, 14f, 2f), s("1", 30f, 0f))))
    }

    @Test fun conservaFraccionesLaterales() {
        val a = listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("Más", 30f, 11f), s("3", 48f, 0f), s("Barra", 44f, 20f, 24f, 2f), s("4", 48f, 27f))
        assertEquals("(1)/(2)+(3)/(4)", MathLayoutConverter.convert(a))
    }

    @Test fun escalaMediaRaizNoSeConfundeConFraccion() {
        assertEquals("√(2)", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 25f, 10f), s("2", 6f, 0f, 5f, 10f))))
    }

    @Test fun multiplicaNumeroALaDerechaDeFraccion() {
        assertEquals("(1)/(2)*3", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("3", 30f, 11f))))
    }

    @Test fun fraccionNoMueveLaLineaSiguiente() {
        assertEquals("(1)/(2)\nx", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("x", 0f, 100f))))
    }

    @Test fun separaCifrasAlejadas() {
        assertEquals("2 3", MathLayoutConverter.convert(listOf(s("2", 0f, 0f), s("3", 100f, 0f))))
    }

    @Test fun aceptaCajaDeAnchoCeroEnNumerador() {
        assertEquals("(1)/(2)", MathLayoutConverter.convert(listOf(s("1", 4f, 0f, 0f, 20f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f))))
    }

    @Test fun errorAnidadoUsaPosicionDelTextoEmitido() {
        val error = assertThrows(MathException::class.java) { MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 40f, 20f), s("x", 12f, 0f), s("Más", 23f, 0f), s("garabato", 34f, 0f))) }
        assertEquals(4, (error.error as MathError.Syntax).position)
    }
}
