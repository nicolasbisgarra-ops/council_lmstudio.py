package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Núcleo de la salida: lo escrito se reemplaza en el mismo lugar y tamaño; lo demás acompaña. */
class PatternCoreTest {
    private var n = 0
    private fun s(vararg xy: Float) = InkStroke("s${n++}", xy.toList().chunked(2).map { InkPoint(it[0], it[1]) })

    /** «lim» (alto 40) con «x→a» debajo (alto 20). */
    private fun limOutput() = listOf(
        s(0f, 0f, 0f, 40f), // l
        s(15f, 10f, 15f, 40f), // i
        s(30f, 15f, 80f, 40f), // m
        s(0f, 60f, 20f, 80f), // x
        s(25f, 70f, 65f, 70f), // →
        s(70f, 62f, 90f, 80f), // a
    )

    private fun lim(core: List<Int>? = null) = InkPattern(
        "user-lim", "lim", listOf(listOf(s(0f, 0f, 80f, 40f))), limOutput(), core = core,
    )

    private fun place(p: InkPattern, anchor: Bounds) = InkReplacement.place(p, anchor, IdGenerator.RANDOM)

    @Test fun `el lim de arriba es el nucleo automatico`() {
        assertEquals(listOf(0, 1, 2), PatternCore.indices(lim()))
        val anchor = Bounds(200f, 100f, 280f, 140f)
        val out = place(lim(), anchor)
        val core = out.take(3).bounds()
        assertEquals(anchor.left, core.left, 0.5f)
        assertEquals(anchor.top, core.top, 0.5f)
        assertEquals(anchor.right, core.right, 0.5f)
        assertEquals(anchor.bottom, core.bottom, 0.5f)
        // La flecha queda debajo, a su escala (alto 20 → 20).
        val rest = out.drop(3).bounds()
        assertEquals(160f, rest.top, 0.5f)
        assertEquals(20f, rest.height, 0.5f)
    }

    @Test fun `con nucleo el alineado no mueve lo escrito`() {
        val g = Bounds(200f, 100f, 280f, 140f)
        assertSame(g, AutoAlign.anchor(lim(), g, listOf(AutoAlign.Item("x", Bounds(100f, 90f, 140f, 150f))), emptyList()) { null })
    }

    @Test fun `nucleo marcado a mano`() {
        assertEquals(listOf(3, 4, 5), PatternCore.indices(lim(core = listOf(5, 3, 4, 4, 99))))
        // Vacío o inválido = automático.
        assertEquals(listOf(0, 1, 2), PatternCore.indices(lim(core = listOf(42))))
        assertEquals(listOf(0, 1, 2), PatternCore.indices(lim(core = emptyList())))
    }

    @Test fun `i igual y dos puntos quedan enteros`() {
        val i = listOf(s(10f, 0f, 10f, 4f), s(10f, 12f, 10f, 60f))
        val eq = listOf(s(0f, 0f, 40f, 0f), s(0f, 20f, 40f, 20f))
        val colon = listOf(s(0f, 0f, 4f, 4f), s(0f, 40f, 4f, 44f))
        for (ink in listOf(i, eq, colon)) {
            val p = InkPattern("user-k", "k", listOf(ink), ink)
            assertEquals(ink.indices.toList(), PatternCore.indices(p))
        }
    }

    @Test fun `una fraccion con partes parecidas queda entera`() {
        val ink = listOf(s(0f, 0f, 30f, 30f), s(0f, 40f, 40f, 40f), s(5f, 50f, 35f, 78f))
        val p = InkPattern("user-f", "f", listOf(listOf(s(0f, 0f, 40f, 4f))), ink)
        assertEquals(listOf(0, 1, 2), PatternCore.indices(p))
    }

    @Test fun `los de fabrica sin marca son enteros`() {
        PresetPatterns.all().forEach { assertEquals(it.outputInk.size, PatternCore.indices(it).size) }
    }
}
