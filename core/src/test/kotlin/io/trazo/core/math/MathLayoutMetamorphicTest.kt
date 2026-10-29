package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** El resultado no depende del tamaño de la letra por eje, de dónde está en la hoja ni del orden de los trazos. */
class MathLayoutMetamorphicTest {
    private fun s(label: String, x: Float, y: Float, w: Float = 10f, h: Float = 20f) =
        LayoutSymbol(label, x, y, x + w, y + h)

    private fun cases(): List<Pair<String, List<LayoutSymbol>>> = listOf(
        "23+4" to listOf(s("3", 15f, 0f), s("Más", 28f, 0f), s("4", 45f, 0f), s("2", 0f, 0f)),
        "x^2" to listOf(s("x", 0f, 12f), s("2", 12f, 0f, 6f, 10f)),
        "2(x)" to listOf(s("2", 0f, 0f), s("Abre paréntesis", 12f, 0f), s("x", 24f, 0f), s("Cierra paréntesis", 36f, 0f)),
        "(1)/(2)" to listOf(s("1", 4f, 0f), s("Barra", 0f, 20f, 24f, 2f), s("2", 4f, 27f)),
        "√(2+3)" to listOf(s("Raíz", 0f, 0f, 40f, 20f), s("2", 12f, 0f), s("Más", 23f, 0f), s("3", 34f, 0f)),
        "x+1\ny=2" to listOf(s("x", 0f, 0f), s("Más", 12f, 0f), s("1", 24f, 0f), s("y", 0f, 40f), s("Igual", 12f, 40f), s("2", 28f, 40f)),
        "8/((1)/(2))" to listOf(s("8", 0f, 11f), s("Dividido", 12f, 11f), s("1", 28f, 0f), s("Barra", 24f, 20f, 24f, 2f), s("2", 28f, 27f)),
        "√((1)/(2))" to listOf(s("Raíz", 0f, 0f, 50f, 60f), s("1", 20f, 0f), s("Barra", 15f, 25f, 30f, 2f), s("2", 20f, 35f)),
    )

    private fun transform(symbols: List<LayoutSymbol>, sx: Float, sy: Float, dx: Float, dy: Float) =
        symbols.map { LayoutSymbol(it.label, it.minX * sx + dx, it.minY * sy + dy, it.maxX * sx + dx, it.maxY * sy + dy) }

    @Test fun losCasosBaseDanLoEsperado() {
        for ((expected, symbols) in cases()) assertEquals(expected, MathLayoutConverter.convert(symbols))
    }

    @Test fun escalaPorEjeTraslacionYOrdenNoCambianElResultado() {
        val scales = listOf(0.5f to 2f, 2f to 0.5f, 0.75f to 1.5f, 1.5f to 0.75f, 1f to 2f, 2f to 1f, 3f to 3f, 0.25f to 0.25f)
        val random = Random(20261029)
        for ((expected, symbols) in cases()) for ((sx, sy) in scales) repeat(4) {
            val moved = transform(symbols, sx, sy, random.nextFloat() * 2000f - 1000f, random.nextFloat() * 2000f - 1000f)
            assertEquals("$expected con escala $sx×$sy", expected, MathLayoutConverter.convert(moved.shuffled(random)))
        }
    }
}
