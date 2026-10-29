package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class MathLayoutScopeInvariantTest {
    private fun comprobar(entrada: List<LayoutSymbol>, esperado: String) {
        for ((sx, sy) in listOf(1e-6f to 1e-6f, 1f to 1f, 1e6f to 1e6f, .01f to 100f, 100f to .01f)) {
            val simbolos = entrada.map { LayoutSymbol(it.label, it.minX * sx, it.minY * sy, it.maxX * sx, it.maxY * sy) }
            for (orden in listOf(simbolos, simbolos.reversed())) {
                val texto = MathLayoutConverter.convert(orden)
                assertEquals("sx=$sx; sy=$sy; texto=$texto", esperado.split('\n').map(MathParser::parseExpression), texto.split('\n').map(MathParser::parseExpression))
            }
        }
    }

    @Test fun operadorFinoNoCruzaCifrasNiCambiaDeRenglonDentroDelCuerpo() {
        for ((etiqueta, operador) in listOf("Menos" to "-", "Dividido" to "/", "Barra" to "/")) {
            for (y in 0..18 step 2) comprobar(listOf(
                LayoutSymbol("1", 0f, 0f, 8f, 20f), LayoutSymbol(etiqueta, 9f, y.toFloat(), 13f, y + 2f),
                LayoutSymbol("2", 15f, 0f, 23f, 20f)), "1${operador}2")
        }
    }

    @Test fun limiteConocidoSinNingunaAlturaDeReferencia() {
        // Las mismas cajas puntuales admiten dos renglones o un exponente. Sin una altura
        // de escritura medida, se conservan como renglones, sin inventar una potencia.
        comprobar(listOf(LayoutSymbol("1", 0f, 0f, 2f, 0f),
            LayoutSymbol("2", 6f, -10f, 8f, -10f)), "2\n1")
    }

    @Test fun raizConPrimerHijoGrandeConservaElComienzo() {
        comprobar(listOf(LayoutSymbol("Raíz", 0f, 0f, 500f, 500f),
            LayoutSymbol("Raíz", 12f, 10f, 412f, 410f), LayoutSymbol("4", 24f, 190f, 34f, 210f)), "√(√(4))")
    }
}
