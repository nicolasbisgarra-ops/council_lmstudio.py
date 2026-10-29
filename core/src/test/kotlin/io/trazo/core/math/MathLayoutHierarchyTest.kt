package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class MathLayoutHierarchyTest {
    private fun s(label: String, x: Float, y: Float, w: Float = 10f, h: Float = 8f) =
        LayoutSymbol(label, x, y, x + w, y + h)

    private fun comprobar(entrada: List<LayoutSymbol>, esperado: String) {
        for (escala in listOf(.01f, .5f, 1f, 3f, 100f)) {
            val simbolos = entrada.map { LayoutSymbol(it.label, it.minX * escala, it.minY * escala, it.maxX * escala, it.maxY * escala) }
            for (orden in listOf(simbolos, simbolos.reversed())) {
                val texto = MathLayoutConverter.convert(orden)
                assertEquals("escala=$escala; texto=$texto", MathParser.parseExpression(esperado), MathParser.parseExpression(texto))
            }
        }
    }

    @Test fun tresNivelesEnElNumeradorConservanLaJerarquiaDeBarras() {
        comprobar(listOf(s("1", 4f, 0f), s("Barra", 0f, 12f, 24f, 2f), s("2", 4f, 18f),
            s("Barra", 0f, 32f, 40f, 2f), s("3", 12f, 40f),
            s("Barra", 0f, 54f, 60f, 2f), s("4", 24f, 62f)), "(((1)/(2))/(3))/(4)")
    }

    @Test fun tresNivelesEnElDenominadorConservanLaJerarquiaDeBarras() {
        comprobar(listOf(s("1", 24f, 0f), s("Barra", 0f, 12f, 60f, 2f), s("2", 12f, 18f),
            s("Barra", 0f, 32f, 40f, 2f), s("3", 4f, 40f),
            s("Barra", 0f, 54f, 24f, 2f), s("4", 4f, 62f)), "(1)/((2)/((3)/(4)))")
    }

    @Test fun denominadorAnidadoConDosBarras() {
        comprobar(listOf(s("1", 12f, 0f, h = 10f), s("Barra", 0f, 20f, 40f, 2f),
            s("2", 4f, 30f, h = 10f), s("Barra", 0f, 44f, 24f, 2f), s("3", 4f, 51f, h = 10f)), "(1)/((2)/(3))")
    }

    @Test fun limiteConocidoBarrasIdenticasYEquidistantes() {
        // Sin otra señal, estas mismas cajas admiten (1/2)/3 y 1/(2/3).
        // El desempate geométrico cierra primero la barra superior: nunca depende del orden de entrada.
        comprobar(listOf(s("1", 4f, 0f), s("Barra", 0f, 12f, 24f, 2f), s("2", 4f, 24f),
            s("Barra", 0f, 36f, 24f, 2f), s("3", 4f, 48f)), "((1)/(2))/(3)")
    }
}
