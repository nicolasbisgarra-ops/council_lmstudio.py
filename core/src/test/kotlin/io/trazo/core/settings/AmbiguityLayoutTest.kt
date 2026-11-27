package io.trazo.core.settings

import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbiguityLayoutTest {
    private val ink = listOf(InkStroke("s", listOf(InkPoint(0f, 0f), InkPoint(10f, 0f))))

    private fun m(id: String, score: Float = 0.8f) =
        Match(InkPattern(id = id, name = id, gestureExamples = listOf(ink), outputInk = ink), score)

    private fun ids(list: List<Match>) = list.map { it.pattern.id }

    @Test fun `la primera vez se recuerda el orden y despues se mantiene`() {
        val (first, order) = AmbiguityLayout.arrange(emptyMap(), listOf(m("fraccion"), m("preset-menos")))
        assertEquals(listOf("fraccion", "preset-menos"), ids(first))
        assertEquals("fraccion", order[AmbiguityLayout.key("fraccion", "preset-menos")])

        // El reconocedor ahora puntúa al revés: igual sale división a la izquierda.
        val (again, same) = AmbiguityLayout.arrange(order, listOf(m("preset-menos", 0.9f), m("fraccion", 0.7f), m("otro")))
        assertEquals(listOf("fraccion", "preset-menos", "otro"), ids(again))
        assertSame(order, same)
    }

    @Test fun `parejas distintas se recuerdan por separado`() {
        var order = emptyMap<String, String>()
        order = AmbiguityLayout.arrange(order, listOf(m("a"), m("b"))).second
        order = AmbiguityLayout.arrange(order, listOf(m("c"), m("a"))).second
        assertEquals(listOf("a", "b"), ids(AmbiguityLayout.arrange(order, listOf(m("b"), m("a"))).first))
        assertEquals(listOf("c", "a"), ids(AmbiguityLayout.arrange(order, listOf(m("a"), m("c"))).first))
    }

    @Test fun `ida y vuelta del texto y basura ignorada`() {
        val order = AmbiguityLayout.arrange(emptyMap(), listOf(m("preset-menos"), m("fraccion"))).second
        assertEquals(order, AmbiguityLayout.read(AmbiguityLayout.serialize(order)))
        assertEquals(emptyMap<String, String>(), AmbiguityLayout.read(null))
        assertEquals(mapOf("a|b" to "b"), AmbiguityLayout.read("a|b=b;b|a=a;x|y=z;=;a|b|c=a;roto"))
    }

    @Test fun `no crece sin limite`() {
        var order = emptyMap<String, String>()
        repeat(AmbiguityLayout.MAX_PAIRS + 20) { order = AmbiguityLayout.arrange(order, listOf(m("p$it"), m("q$it"))).second }
        assertEquals(AmbiguityLayout.MAX_PAIRS, order.size)
        assertTrue(AmbiguityLayout.key("p${AmbiguityLayout.MAX_PAIRS + 19}", "q${AmbiguityLayout.MAX_PAIRS + 19}") in order)
    }

    @Test fun `cada opcion tiene un color distinto y el menos sigue rojo`() {
        val fixed = { id: String -> SymbolColors.colorFor(SymbolColors.defaults, id) }
        val c = AmbiguityLayout.colors(listOf("fraccion", "preset-menos"), fixed)
        assertEquals(SymbolColors.RED, c[1])
        assertNotEquals(c[0], c[1])
        // Mismo patrón, mismo color, siempre.
        assertEquals(c[0], AmbiguityLayout.colors(listOf("fraccion", "preset-mas"), fixed)[0])
        // Dos sin color fijo nunca coinciden.
        for (i in 0 until 30) {
            val two = AmbiguityLayout.colors(listOf("x$i", "y$i")) { null }
            assertNotEquals(two[0], two[1])
        }
        // Dos con el mismo color fijo: la segunda toma otro.
        val same = AmbiguityLayout.colors(listOf("a", "b")) { SymbolColors.GREEN }
        assertNotEquals(same[0], same[1])
    }
}
