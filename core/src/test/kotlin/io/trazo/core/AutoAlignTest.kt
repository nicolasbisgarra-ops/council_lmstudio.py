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

    /** Piso (línea de escritura) de la salida de [key] anclada en [out]. */
    private fun floorOf(key: String, out: Bounds): Float {
        val p = preset(key)
        val h = out.height * p.outputScale
        return out.centerY - h / 2f + h * AutoAlign.floor(p)
    }

    private fun refFloor(key: String, top: Float, bottom: Float) = top + (bottom - top) * AutoAlign.floor(preset(key))

    /** Alto sobre la línea (la escala) de la salida de [p] anclada en [out]. */
    private fun body(p: InkPattern, out: Bounds) = out.height * p.outputScale * AutoAlign.floor(p)
    private fun body(key: String, out: Bounds) = body(preset(key), out)
    private fun refBody(key: String, top: Float, bottom: Float) = (bottom - top) * AutoAlign.floor(preset(key))

    @Test fun `un numero toma la escala del mas cercano y se apoya en su piso`() {
        val g = Bounds(300f, 130f, 340f, 200f)
        val out = align("2", g, item("1", 100f, 100f, 140f, 180f), item("3", 900f, 0f, 940f, 60f))
        assertEquals(refBody("1", 100f, 180f), body("2", out), 0.5f)
        assertEquals(g.width / g.height, out.width / out.height, 0.01f)
        assertEquals(refFloor("1", 100f, 180f), floorOf("2", out), 0.5f)
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
        assertEquals(refBody("5", 100f, 180f), body("3", out), 0.5f)
        assertEquals(refFloor("5", 100f, 180f), floorOf("3", out), 0.5f)
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

    @Test fun `una variable toma la escala del numero de al lado y baja a su piso`() {
        // «5x»: la x toma la escala del 5 y se apoya en su piso.
        val g = Bounds(160f, 130f, 190f, 165f)
        val out = alignUser(letter("x"), g, item("5", 100f, 100f, 140f, 180f))
        assertEquals(refBody("5", 100f, 180f), body(letter("x"), out), 0.5f)
        assertEquals(180f, out.bottom, 0.5f)
        // «Equis» y una incógnita con otro nombre también son variables.
        assertEquals(180f, alignUser(letter("Equis"), g, item("5", 100f, 100f, 140f, 180f)).bottom, 0.5f)
        assertEquals(180f, alignUser(letter("Mi letra", unknown = "a"), g, item("5", 100f, 100f, 140f, 180f)).bottom, 0.5f)
    }

    @Test fun `el piso elegido por el usuario manda sobre el automatico`() {
        // «lim» con «x→k» debajo: el usuario pone el piso a mitad de la salida.
        val lim = letter("lim").copy(floor = 0.5f)
        assertEquals(0.5f, AutoAlign.floor(lim), 1e-6f)
        val g = Bounds(160f, 130f, 200f, 210f)
        val out = alignUser(lim, g, item("5", 100f, 100f, 140f, 180f))
        assertEquals(refFloor("5", 100f, 180f), out.top + out.height * 0.5f, 0.5f)
        // Un valor fuera de rango se acota.
        assertEquals(AutoAlign.MIN_USER_FLOOR, AutoAlign.floor(lim.copy(floor = -1f)), 1e-6f)
        assertEquals(1f, AutoAlign.floor(lim.copy(floor = 3f)), 1e-6f)
    }

    @Test fun `la cola de la y baja del piso`() {
        val g = Bounds(160f, 130f, 190f, 190f)
        val out = alignUser(letter("y"), g, item("5", 100f, 100f, 140f, 180f))
        // El cuerpo (sin la cola) mide lo del 5.
        assertEquals(refBody("5", 100f, 180f), body(letter("y"), out), 0.5f)
        assertEquals(180f, out.top + out.height * (1f - AutoAlign.DESCENDER), 0.5f)
    }

    @Test fun `una letra griega de la biblioteca toma la escala del numero y se apoya en su linea`() {
        val g = Bounds(160f, 120f, 200f, 160f)
        val out = align("pi", g, item("5", 100f, 100f, 140f, 180f))
        assertEquals(refBody("5", 100f, 180f), body("pi", out), 0.5f)
        assertEquals(180f, out.top + out.height * AutoAlign.floor(preset("pi")), 0.5f)
    }

    @Test fun `un numero se apoya en otro numero, no en una letra`() {
        // «5x + 3»: el 3 iguala al 5 aunque la x esté más cerca.
        val out = alignUser(preset("3"), Bounds(260f, 120f, 290f, 170f), item("5", 100f, 100f, 140f, 180f), AutoAlign.Item("user-x", Bounds(150f, 150f, 180f, 180f)))
        assertEquals(refBody("5", 100f, 180f), body("3", out), 0.5f)
        assertEquals(refFloor("5", 100f, 180f), floorOf("3", out), 0.5f)
        // Solo una letra al lado: toma su escala y apoya en su piso.
        val g = Bounds(260f, 120f, 290f, 170f)
        val alone = alignUser(preset("3"), g, AutoAlign.Item("user-x", Bounds(150f, 150f, 180f, 180f)))
        assertEquals(30f, body("3", alone), 0.5f)
        assertEquals(180f, alone.top + alone.height * AutoAlign.floor(preset("3")), 0.5f)
    }

    @Test fun `una variable de exponente queda donde se escribio`() {
        val g = Bounds(145f, 60f, 165f, 85f)
        assertSame(g, alignUser(letter("x"), g, item("2", 100f, 100f, 140f, 180f)))
    }

    @Test fun `un exponente escrito sobre la mitad de arriba del numero queda donde se escribio`() {
        // El 2 de «5²» toca la mitad de arriba del 5: antes bajaba al renglón y tomaba su altura.
        val s = preset("2").outputScale
        val g = Bounds(145f, 140f - 35f / s, 165f, 140f - 5f / s)
        assertSame(g, align("2", g, item("5", 100f, 100f, 140f, 180f)))
        // Lo mismo con una letra de base y un subíndice.
        val sub = Bounds(145f, 160f - 10f / s, 165f, 160f + 30f / s)
        assertSame(sub, align("2", sub, item("5", 100f, 100f, 140f, 180f)))
    }

    @Test fun `lo que sigue a una potencia se alinea con la base y no con el exponente`() {
        // «5² + 3»: el 3 iguala al 5, aunque el exponente esté más cerca.
        val out = align(
            "3", Bounds(260f, 115f, 290f, 175f),
            item("5", 100f, 100f, 140f, 180f), item("2", 145f, 80f, 165f, 120f), item("mas", 180f, 125f, 220f, 165f),
        )
        assertEquals(refBody("5", 100f, 180f), body("3", out), 0.5f)
        assertEquals(refFloor("5", 100f, 180f), floorOf("3", out), 0.5f)
        // El + de «x² + 1» se centra en el 5, no en el exponente.
        val plus = align("mas", Bounds(180f, 110f, 210f, 140f), item("5", 100f, 100f, 140f, 180f), item("2", 145f, 80f, 165f, 120f))
        assertEquals(140f, plus.centerY, 0.5f)
    }

    @Test fun `un menos de exponente no baja al renglon`() {
        val g = Bounds(145f, 108f, 170f, 114f)
        assertSame(g, align("menos", g, item("5", 100f, 100f, 140f, 180f)))
    }

    @Test fun `lo escrito lejos de todo no se alinea`() {
        val g = Bounds(600f, 130f, 640f, 200f)
        assertSame(g, align("2", g, item("1", 100f, 100f, 140f, 180f)))
    }

    @Test fun `solo toma la escala de lo que esta a menos de tres veces el tamano`() {
        // A 2,9 altos del 1 todavía la toma; a 3,1 ya es otra cuenta y queda como se escribió.
        val s = preset("2").outputScale
        val h = 70f / s
        val near = Bounds(140f + 2.9f * 80f, 130f, 140f + 2.9f * 80f + 40f, 130f + h)
        assertEquals(refBody("1", 100f, 180f), body("2", align("2", near, item("1", 100f, 100f, 140f, 180f))), 0.5f)
        val far = Bounds(140f + 3.1f * 80f, 130f, 140f + 3.1f * 80f + 40f, 130f + h)
        assertSame(far, align("2", far, item("1", 100f, 100f, 140f, 180f)))
    }

    @Test fun `toma la escala del mas cercano de dos`() {
        // «1 … 4  2»: el 2 iguala al 4, que está más cerca, y no al 1.
        val out = align("2", Bounds(300f, 120f, 330f, 170f), item("1", 100f, 100f, 140f, 180f), item("4", 240f, 125f, 265f, 165f))
        assertEquals(refBody("4", 125f, 165f), body("2", out), 0.5f)
        assertEquals(refFloor("4", 125f, 165f), floorOf("2", out), 0.5f)
    }

    @Test fun `una coma no le cambia la escala al numero siguiente`() {
        val g = Bounds(200f, 120f, 230f, 170f)
        val out = align("3", g, item("coma", 160f, 170f, 168f, 190f))
        assertEquals(g.height, out.height, 0.01f)
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

    @Test fun `un parentesis junto a un numero, una x, un mas o un menos se centra sin cambiar de tamano`() {
        val g = Bounds(200f, 140f, 230f, 240f)
        for (next in listOf(item("2", 100f, 120f, 140f, 200f), item("letra-x", 100f, 130f, 140f, 190f), item("mas", 100f, 140f, 140f, 180f), item("menos", 100f, 158f, 140f, 162f))) {
            val out = align("parentesis-abre", g, next)
            assertEquals("junto a ${next.patternId}", 100f, out.height, 0.5f)
            assertEquals("junto a ${next.patternId}", next.bounds.centerY, out.centerY, 0.5f)
        }
    }

    @Test fun `un parentesis junto a una integral se centra en su medio sin cambiar de tamano`() {
        val g = Bounds(160f, 120f, 180f, 200f)
        for (side in listOf(AutoAlign.Item(DefaultPatterns.INTEGRAL, Bounds(100f, 50f, 140f, 250f)), AutoAlign.Item(DefaultPatterns.INTEGRAL, Bounds(200f, 50f, 240f, 250f)))) {
            val out = align("parentesis-abre", g, side)
            assertEquals(80f, out.height, 0.01f)
            assertEquals(150f, out.centerY, 0.5f)
        }
    }

    @Test fun `un corchete toma la altura de otro signo de agrupacion parecido`() {
        val p = preset("parentesis-abre")
        val out = align("parentesis-abre", Bounds(200f, 140f, 230f, 240f), item("corchete-abre", 150f, 110f, 175f, 250f))
        assertEquals(140f / p.outputScale, out.height, 0.5f)
        assertEquals(180f, out.centerY, 0.5f)
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
