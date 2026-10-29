package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Auto-alineado: las formas nuevas se igualan con el renglón y los cierres con su apertura. */
class AutoAlignTest {
    private val presets = PresetPatterns.all().associateBy { it.id }
    private fun preset(key: String) = presets.getValue(PresetPatterns.PREFIX + key)
    private val lookup: (String) -> InkPattern? = { presets[it] }

    private fun item(key: String, l: Float, t: Float, r: Float, b: Float) =
        AutoAlign.Item(PresetPatterns.PREFIX + key, Bounds(l, t, r, b))

    private fun align(key: String, gesture: Bounds, vararg items: AutoAlign.Item) =
        AutoAlign.anchor(preset(key), gesture, items.toList(), lookup)

    @Test fun `a similar sized shape takes the height and line of the nearest one`() {
        val p = preset("2")
        val out = align("2", Bounds(300f, 130f, 340f, 200f), item("1", 100f, 100f, 140f, 180f), item("3", 900f, 0f, 940f, 60f))
        assertEquals(80f / p.outputScale, out.height, 0.5f)
        assertEquals(140f, out.centerY, 0.5f)
        assertEquals(320f, out.centerX, 0.5f)
    }

    @Test fun `a much smaller sign is only centered on the line`() {
        val g = Bounds(200f, 170f, 260f, 200f)
        val out = align("igual", g, item("1", 100f, 100f, 140f, 200f))
        assertEquals(g.height, out.height, 0.01f)
        assertEquals(150f, out.centerY, 0.5f)
    }

    @Test fun `un signo no marca la altura del renglon para el numero siguiente`() {
        // «5 + 3»: el 3 toma la altura del 5 aunque el + esté más cerca.
        val out = align("3", Bounds(260f, 120f, 290f, 170f), item("5", 100f, 100f, 140f, 180f), item("mas", 170f, 125f, 210f, 165f))
        assertEquals(80f / preset("3").outputScale, out.height, 0.5f)
        assertEquals(140f, out.centerY, 0.5f)
        // Solo un signo al lado: el número queda como se escribió.
        val g = Bounds(260f, 120f, 290f, 170f)
        assertSame(g, align("3", g, item("mas", 170f, 125f, 210f, 165f)))
    }

    @Test fun `un signo se centra en la linea de los numeros sin cambiar de tamano`() {
        // Un + casi del alto del número: antes tomaba su altura; ahora solo se centra.
        val g = Bounds(170f, 150f, 230f, 210f)
        val out = align("mas", g, item("5", 100f, 100f, 140f, 180f), item("menos", 240f, 150f, 280f, 160f))
        assertEquals(g.height, out.height, 0.01f)
        assertEquals(140f, out.centerY, 0.5f)
        // Sin números al lado, se centra en otro signo.
        val alone = align("igual", Bounds(300f, 128f, 340f, 146f), item("mas", 200f, 120f, 240f, 160f))
        assertEquals(140f, alone.centerY, 0.5f)
    }

    @Test fun `lo escrito a proposito mas arriba queda donde se escribio`() {
        // Un numerador sobre el renglón del 3 no baja a su línea.
        val num = Bounds(300f, 70f, 330f, 125f)
        assertSame(num, align("1", num, item("3", 100f, 100f, 140f, 180f)))
        // La barra de la fracción, debajo del numerador, tampoco se mueve.
        val bar = Bounds(290f, 137f, 345f, 143f)
        val fraction = presets.getValue(DefaultPatterns.FRACTION)
        assertSame(bar, AutoAlign.anchor(fraction, bar, listOf(item("3", 100f, 100f, 140f, 180f), item("1", 300f, 70f, 330f, 125f)), lookup))
    }

    private fun letter(name: String, unknown: String? = null): InkPattern {
        val ink = listOf(InkStroke("$name-o", listOf(InkPoint(0f, 0f), InkPoint(40f, 40f))))
        return InkPattern("user-$name", name, listOf(ink), ink, unknown = unknown)
    }

    private fun alignUser(p: InkPattern, gesture: Bounds, vararg items: AutoAlign.Item) =
        AutoAlign.anchor(p, gesture, items.toList()) { presets[it] ?: if (it == "user-x") letter("x") else null }

    @Test fun `una variable no toma la altura del numero, solo baja a su piso`() {
        // «5x»: la x queda de su tamaño, apoyada en el piso del 5.
        val g = Bounds(160f, 130f, 190f, 165f)
        val out = alignUser(letter("x"), g, item("5", 100f, 100f, 140f, 180f))
        assertEquals(g.height, out.height, 0.01f)
        assertEquals(180f, out.bottom, 0.5f)
        // «Equis» y una incógnita con otro nombre también son variables.
        assertEquals(180f, alignUser(letter("Equis"), g, item("5", 100f, 100f, 140f, 180f)).bottom, 0.5f)
        assertEquals(180f, alignUser(letter("Mi letra", unknown = "a"), g, item("5", 100f, 100f, 140f, 180f)).bottom, 0.5f)
    }

    @Test fun `la cola de la y baja del piso`() {
        val g = Bounds(160f, 130f, 190f, 190f)
        val out = alignUser(letter("y"), g, item("5", 100f, 100f, 140f, 180f))
        assertEquals(g.height, out.height, 0.01f)
        assertEquals(180f, out.top + out.height * (1f - AutoAlign.DESCENDER), 0.5f)
    }

    @Test fun `una letra griega de la biblioteca se apoya en su linea sin cambiar de tamano`() {
        val g = Bounds(160f, 120f, 200f, 160f)
        val out = align("pi", g, item("5", 100f, 100f, 140f, 180f))
        assertEquals(g.height, out.height, 0.01f)
        assertEquals(180f, out.top + out.height * AutoAlign.floor(preset("pi")), 0.5f)
    }

    @Test fun `un numero toma la altura de otro numero, no la de una letra`() {
        // «5x + 3»: el 3 iguala al 5 aunque la x esté más cerca.
        val out = alignUser(preset("3"), Bounds(260f, 120f, 290f, 170f), item("5", 100f, 100f, 140f, 180f), AutoAlign.Item("user-x", Bounds(150f, 150f, 180f, 180f)))
        assertEquals(80f / preset("3").outputScale, out.height, 0.5f)
        // Solo una letra al lado: no cambia de tamaño, apoya en su piso.
        val g = Bounds(260f, 120f, 290f, 170f)
        val alone = alignUser(preset("3"), g, AutoAlign.Item("user-x", Bounds(150f, 150f, 180f, 180f)))
        assertEquals(g.height, alone.height, 0.01f)
        assertEquals(180f, alone.top + alone.height * AutoAlign.floor(preset("3")), 0.5f)
    }

    @Test fun `una variable de exponente queda donde se escribio`() {
        val g = Bounds(145f, 60f, 165f, 85f)
        assertSame(g, alignUser(letter("x"), g, item("2", 100f, 100f, 140f, 180f)))
    }

    @Test fun `closing bracket copies the height and level of its opening`() {
        val p = preset("parentesis-cierra")
        val out = align(
            "parentesis-cierra", Bounds(600f, 150f, 630f, 260f),
            item("parentesis-abre", 100f, 80f, 130f, 320f),
            item("2", 300f, 160f, 340f, 240f),
        )
        assertEquals(240f / p.outputScale, out.height, 0.5f)
        assertEquals(200f, out.centerY, 0.5f)
    }

    @Test fun `nested brackets close the innermost open one`() {
        val out = align(
            "corchete-cierra", Bounds(600f, 180f, 620f, 260f),
            item("corchete-abre", 100f, 50f, 120f, 350f),
            item("corchete-abre", 200f, 160f, 220f, 280f),
        )
        assertEquals(220f, out.centerY, 0.5f)
        assertEquals(120f / preset("corchete-cierra").outputScale, out.height, 0.5f)
    }

    @Test fun `a number inside a matrix is not centered on the bracket`() {
        val g = Bounds(150f, 100f, 180f, 150f)
        val out = align("2", g, item("corchete-abre", 100f, 80f, 120f, 320f))
        assertSame(g, out)
    }

    @Test fun `shapes on another line are ignored`() {
        val g = Bounds(150f, 400f, 180f, 450f)
        assertSame(g, align("2", g, item("1", 100f, 100f, 140f, 180f)))
    }

    @Test fun `the controller aligns a manual conversion and can be turned off`() {
        val open = preset("parentesis-abre")
        val close = preset("parentesis-cierra")
        val opener = InkReplacement.place(open, Bounds(100f, 100f, 140f, 300f), IdGenerator.sequential())
        val gesture = InkStroke("g", listOf(InkPoint(500f, 160f), InkPoint(520f, 200f), InkPoint(500f, 240f)))
        fun run(on: Boolean): Bounds {
            val c = InkController(PageState(opener + gesture), History(), { listOf(open, close) }, IdGenerator.sequential())
            c.autoAlign = on
            return c.convertWith(close, listOf("g"))!!.bounds()
        }
        val ob = opener.bounds()
        val aligned = run(true)
        assertEquals(ob.height, aligned.height, 1f)
        assertEquals(ob.centerY, aligned.centerY, 1f)
        val free = run(false)
        assertEquals(200f, free.centerY, 1f)
    }
}
