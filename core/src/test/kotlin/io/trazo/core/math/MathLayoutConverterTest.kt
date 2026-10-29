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

    @Test fun conservaRaicesYOperadoresLateralesYEnRenglonesSeparados() {
        assertEquals("√(4)+1", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 30f, 20f), s("4", 12f, 0f), s("Más", 36f, 0f), s("1", 48f, 0f))))
        assertEquals("3+√(4)", MathLayoutConverter.convert(listOf(s("3", 0f, 0f), s("Más", 12f, 0f), s("Raíz", 24f, 0f, 30f, 20f), s("4", 36f, 0f))))
        assertEquals("√(4)\nx", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 30f, 20f), s("4", 12f, 0f), s("x", 0f, 100f))))
        assertEquals("(1)/(√(2))", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 30f, 2f), s("Raíz", 0f, 27f, 30f, 20f), s("2", 12f, 27f))))
        assertEquals("√(4)^2", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 10f, 30f, 20f), s("4", 12f, 10f), s("2", 32f, 0f, 6f, 10f))))
    }

    @Test fun conservaFraccionesPorRenglonOperadoresYEstructurasRecursivas() {
        assertEquals("(1)/(2)\n(3)/(4)", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("3", 4f, 100f), s("Barra", 0f, 120f, 24f, 2f), s("4", 4f, 127f))))
        assertEquals("(1)/(2)+(3)/(4)+(5)/(6)", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("Más", 30f, 11f), s("3", 48f, 0f), s("Barra", 44f, 20f, 24f, 2f), s("4", 48f, 27f), s("Más", 74f, 11f), s("5", 92f, 0f), s("Barra", 88f, 20f, 24f, 2f), s("6", 92f, 27f))))
        assertEquals("((1)/(2)+(3)/(4))/(5)", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 25f, 24f, 2f), s("2", 4f, 32f), s("Más", 30f, 10f), s("3", 48f, 0f), s("Barra", 44f, 25f, 24f, 2f), s("4", 48f, 32f), s("Barra", 0f, 60f, 80f, 2f), s("5", 32f, 70f))))
    }

    @Test fun exponenteEspacialmenteLejanoNoSeAsocia() {
        assertEquals("2\nx", MathLayoutConverter.convert(listOf(s("2", 12f, 0f, 6f, 10f), s("x", 0f, 100f))))
    }

    @Test fun erroresUsanPosicionDeSalida() {
        val e = assertThrows(MathException::class.java) { MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("Más", 30f, 11f), s("garabato", 44f, 11f))) }
        assertEquals(8, (e.error as MathError.Syntax).position)
        val fraccion = assertThrows(MathException::class.java) { MathLayoutConverter.convert(listOf(s("garabato", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f))) }
        assertEquals(1, (fraccion.error as MathError.Syntax).position)
    }

    @Test fun noAbsorbeLateralesAlResolverRaiz() {
        assertEquals("√((1)/(2))+3", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 0f, 50f, 60f), s("1", 20f, 0f), s("Barra", 15f, 25f, 30f, 2f), s("2", 20f, 35f), s("Más", 56f, 20f), s("3", 68f, 20f))))
        assertEquals("3+√(4)", MathLayoutConverter.convert(listOf(s("3", 0f, 0f), s("Más", 12f, 0f), s("Raíz", 24f, 0f, 30f, 20f), s("4", 36f, 0f))))
        assertEquals("√(4)^2", MathLayoutConverter.convert(listOf(s("Raíz", 0f, 10f, 30f, 20f), s("4", 12f, 10f), s("2", 32f, 0f, 6f, 10f))))
    }

    @Test fun procesaRaicesComoPartesDeFraccionesAcumuladas() {
        val a = listOf(s("Raíz", 0f, 0f, 30f, 20f), s("4", 12f, 0f), s("Barra", 0f, 25f, 36f, 2f), s("2", 12f, 32f), s("Más", 42f, 25f), s("Raíz", 56f, 0f, 30f, 20f), s("9", 68f, 0f), s("Barra", 56f, 25f, 36f, 2f), s("3", 68f, 32f))
        assertEquals("(√(4))/(2)+(√(9))/(3)", MathLayoutConverter.convert(a))
    }

    @Test fun exponentesPuedenSerEstructurasCompletas() {
        assertEquals("((1)/(2))^2", MathLayoutConverter.convert(listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f), s("2", 26f, -3f, 6f, 10f))))
        assertEquals("x^(2+3)", MathLayoutConverter.convert(listOf(s("x", 0f, 14f), s("2", 12f, 0f, 6f, 10f), s("Más", 20f, 0f, 6f, 10f), s("3", 28f, 0f, 6f, 10f))))
        assertEquals("x^((1)/(2))", MathLayoutConverter.convert(listOf(s("x", 0f, 18f, 10f, 40f), s("1", 16f, 0f, 4f, 8f), s("Barra", 12f, 10f, 14f, 1f), s("2", 16f, 14f, 4f, 8f))))
    }

    @Test fun conservaFraccionComoDenominadorDesdeLaIzquierda() {
        assertEquals("8/((1)/(2))", MathLayoutConverter.convert(listOf(s("8", 0f, 11f), s("Barra", 12f, 11f), s("1", 28f, 0f), s("Barra", 24f, 20f, 24f, 2f), s("2", 28f, 27f))))
    }
}
