package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class MathLayoutLexicalBoundaryTest {
    @Test fun conservaLosAtomosReconocidosAlEmitirIdentificadores() {
        val entradas = listOf(
            listOf(LayoutSymbol("x", 0f, 0f, 10f, 20f), LayoutSymbol("y", 12f, 0f, 22f, 20f)) to "x*y",
            listOf(LayoutSymbol("alfa", 0f, 0f, 10f, 20f), LayoutSymbol("beta", 12f, 0f, 22f, 20f)) to "α*β",
            listOf(LayoutSymbol("x", 0f, 12f, 10f, 32f), LayoutSymbol("y", 12f, 0f, 18f, 10f),
                LayoutSymbol("z", 24f, 12f, 34f, 32f)) to "x^y*z"
        )
        for ((entrada, esperado) in entradas) for (escala in listOf(.01f, .5f, 1f, 3f, 100f)) {
            val simbolos = entrada.map { LayoutSymbol(it.label, it.minX * escala, it.minY * escala, it.maxX * escala, it.maxY * escala) }
            for (orden in listOf(simbolos, simbolos.reversed())) {
                val texto = MathLayoutConverter.convert(orden)
                assertEquals("escala=$escala; texto=$texto", MathParser.parseExpression(esperado), MathParser.parseExpression(texto))
            }
        }
    }
}
