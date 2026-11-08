package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-alineado de paréntesis, corchetes y llaves: con qué se empareja un cierre, en qué se centra una
 * apertura y qué nunca toma de referencia (otro renglón, lo de encima o debajo, otra cuenta lejana).
 */
class AutoAlignBracketTest {
    private val presets = PresetPatterns.all().associateBy { it.id }
    private fun preset(key: String) = presets.getValue(PresetPatterns.PREFIX + key)
    private val ink = listOf(InkStroke("o", listOf(InkPoint(0f, 0f), InkPoint(10f, 50f), InkPoint(0f, 100f))))
    private val userOpen = InkPattern("user-paren", "Abre paréntesis", listOf(ink), ink)
    private val userClose = InkPattern("user-paren-c", "Cierra paréntesis", listOf(ink), ink)
    private val lookup: (String) -> InkPattern? = { presets[it] ?: when (it) { userOpen.id -> userOpen; userClose.id -> userClose; else -> null } }

    private fun item(key: String, l: Float, t: Float, r: Float, b: Float) = AutoAlign.Item(PresetPatterns.PREFIX + key, Bounds(l, t, r, b))
    private fun align(p: InkPattern, g: Bounds, vararg items: AutoAlign.Item) = AutoAlign.anchor(p, g, items.toList(), lookup)

    // ---------- No se alinea con algo que no tiene que ver ----------

    @Test fun `a tall opener does not climb to a digit on the row above`() {
        val open = preset("parentesis-abre")
        val g = Bounds(200f, 100f, 230f, 300f)
        val c = align(open, g, item("5", 205f, 70f, 245f, 150f), item("3", 100f, 160f, 140f, 240f))
        assertEquals(200f, c.centerY, 0.5f)
        assertEquals(200f, c.height, 0.5f)
    }

    @Test fun `an opener between neighbours above and below stays put whatever their order`() {
        val open = preset("parentesis-abre")
        val g = Bounds(200f, 100f, 230f, 300f)
        val up = item("5", 205f, 70f, 245f, 150f)
        val down = item("7", 205f, 250f, 245f, 330f)
        assertSame(g, align(open, g, up, down))
        assertSame(g, align(open, g, down, up))
    }

    @Test fun `an opener ignores a digit almost on top of it and centres on the one beside it`() {
        val open = preset("parentesis-abre")
        val g = Bounds(200f, 100f, 230f, 300f)
        val c = align(open, g, item("5", 205f, 95f, 245f, 175f), item("3", 100f, 160f, 140f, 240f))
        assertEquals(200f, c.centerY, 0.5f)
    }

    @Test fun `an opener before a fraction centres on its bar`() {
        val open = preset("parentesis-abre")
        val g = Bounds(110f, 50f, 140f, 240f)
        val num = item("1", 150f, 60f, 180f, 130f)
        val den = item("2", 170f, 160f, 200f, 230f)
        val bar = AutoAlign.Item(DefaultPatterns.FRACTION, Bounds(160f, 140f, 260f, 150f))
        assertEquals(145f, align(open, g, num, bar, den).centerY, 0.5f)
        // La raya de un resultado (alto 0) nunca entra en el renglón, pero sigue siendo la barra.
        val flat = item("barra", 160f, 145f, 260f, 145f)
        assertEquals(145f, align(open, g, num, flat, den).centerY, 0.5f)
    }

    @Test fun `a closer does not pair with an opener of another expression far away`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(900f, 120f, 925f, 170f)
        val c = align(close, g, item("parentesis-abre", 100f, 100f, 130f, 200f), item("2", 850f, 120f, 880f, 170f))
        assertEquals(50f, c.height, 0.5f)
        assertEquals(145f, c.centerY, 0.5f)
    }

    @Test fun `a closer on the lower row does not copy an opener spanning two rows`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(400f, 310f, 420f, 370f)
        assertSame(g, align(close, g, item("parentesis-abre", 100f, 50f, 140f, 350f)))
    }

    @Test fun `an opener does not copy a tall bracket shifted off its line`() {
        val open = preset("parentesis-abre")
        val g = Bounds(360f, 120f, 380f, 220f)
        assertSame(g, align(open, g, item("corchete-cierra", 300f, 0f, 320f, 180f)))
    }

    @Test fun `a closer copies its own opener and not the big one of the row above`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(350f, 285f, 375f, 375f)
        val big = item("parentesis-abre", 300f, 40f, 330f, 320f)
        val own = item("parentesis-abre", 100f, 280f, 130f, 380f)
        val c = align(close, g, big, own)
        assertEquals(100f, c.height, 0.5f)
        assertEquals(330f, c.centerY, 0.5f)
    }

    @Test fun `a user opener named Abre parentesis keeps its size next to a digit`() {
        val g = Bounds(200f, 80f, 230f, 280f)
        val c = align(userOpen, g, item("2", 100f, 120f, 140f, 200f))
        assertEquals(200f, c.height, 0.5f)
        assertEquals(160f, c.centerY, 0.5f)
    }

    @Test fun `a digit does not grow next to a user parenthesis`() {
        val g = Bounds(150f, 120f, 180f, 170f)
        val paren = AutoAlign.Item(userOpen.id, Bounds(100f, 40f, 130f, 240f))
        assertSame(g, align(preset("2"), g, paren))
    }

    @Test fun `a user closer pairs with the user opener`() {
        val g = Bounds(600f, 150f, 630f, 260f)
        val open = AutoAlign.Item(userOpen.id, Bounds(100f, 80f, 130f, 320f))
        val c = align(userClose, g, open, item("2", 300f, 160f, 340f, 240f))
        assertEquals(240f, c.height, 0.5f)
        assertEquals(200f, c.centerY, 0.5f)
    }

    @Test fun `a closer does not copy an opener six times taller`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(200f, 125f, 215f, 175f)
        val c = align(close, g, item("parentesis-abre", 100f, 0f, 130f, 300f), item("2", 150f, 120f, 180f, 180f))
        assertEquals(50f, c.height, 0.5f)
        assertEquals(150f, c.centerY, 0.5f)
    }

    // ---------- Lo que sí se alinea ----------

    @Test fun `the closer after a fraction copies its opener`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(280f, 70f, 310f, 230f)
        val c = align(
            close, g,
            item("parentesis-abre", 100f, 50f, 130f, 240f), item("1", 150f, 60f, 180f, 130f),
            AutoAlign.Item(DefaultPatterns.FRACTION, Bounds(140f, 140f, 260f, 150f)), item("2", 170f, 160f, 200f, 230f),
        )
        assertEquals(190f, c.height, 0.5f)
        assertEquals(145f, c.centerY, 0.5f)
    }

    @Test fun `a long expression chains the closer to a far opener`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(600f, 105f, 625f, 195f)
        val items = buildList {
            add(item("parentesis-abre", 100f, 100f, 130f, 200f))
            for ((i, x) in listOf(150f, 250f, 350f, 450f, 550f).withIndex()) add(item("${i + 1}", x, 120f, x + 30f, 180f))
            for (x in listOf(200f, 300f, 400f, 500f)) add(item("mas", x, 135f, x + 30f, 165f))
        }.toTypedArray()
        val c = align(close, g, *items)
        assertEquals(100f, c.height, 0.5f)
        assertEquals(150f, c.centerY, 0.5f)
    }

    @Test fun `an opener after a closer takes its height`() {
        val open = preset("parentesis-abre")
        val g = Bounds(225f, 110f, 250f, 190f)
        val c = align(open, g, item("parentesis-abre", 100f, 100f, 125f, 200f), item("2", 140f, 120f, 170f, 180f), item("parentesis-cierra", 185f, 100f, 210f, 200f))
        assertEquals(100f, c.height, 0.5f)
        assertEquals(150f, c.centerY, 0.5f)
    }

    @Test fun `a square bracket does not close a parenthesis`() {
        val close = preset("corchete-cierra")
        val g = Bounds(200f, 110f, 220f, 190f)
        val c = align(close, g, item("parentesis-abre", 100f, 100f, 130f, 200f), item("2", 150f, 120f, 180f, 180f))
        assertEquals(80f, c.height, 0.5f)
        assertEquals(150f, c.centerY, 0.5f)
    }

    @Test fun `an opener far from everything stays where it was written`() {
        val open = preset("parentesis-abre")
        val g = Bounds(500f, 100f, 530f, 200f)
        assertSame(g, align(open, g, item("2", 100f, 120f, 140f, 180f)))
    }

    @Test fun `a parenthesis inside a numerator centres on the numerator, not on the bar`() {
        val open = preset("parentesis-abre")
        val g = Bounds(125f, 55f, 140f, 125f)
        val c = align(open, g, item("2", 150f, 60f, 180f, 120f), AutoAlign.Item(DefaultPatterns.FRACTION, Bounds(100f, 130f, 300f, 136f)), item("3", 180f, 145f, 210f, 205f))
        assertEquals(90f, c.centerY, 0.5f)
        assertEquals(70f, c.height, 0.5f)
    }

    // ---------- Apertura lejana: solo la escritura a mano del renglón la une ----------

    @Test fun `a closer six heights from its opener with nothing recognised between stays put`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(700f, 120f, 720f, 180f)
        assertSame(g, align(close, g, item("parentesis-abre", 100f, 100f, 130f, 200f)))
    }

    @Test fun `handwriting between the brackets links the closer to its opener`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(700f, 120f, 720f, 180f)
        val open = item("parentesis-abre", 100f, 100f, 130f, 200f)
        val hand = listOf(Bounds(200f, 110f, 260f, 190f), Bounds(350f, 110f, 410f, 190f), Bounds(500f, 110f, 560f, 190f))
        val c = AutoAlign.anchor(close, g, listOf(open), hand) { lookup(it) }
        assertEquals(100f, c.height, 0.5f)
        assertEquals(150f, c.centerY, 0.5f)
    }

    @Test fun `handwriting on another row links nothing`() {
        val close = preset("parentesis-cierra")
        val g = Bounds(700f, 120f, 720f, 180f)
        val open = item("parentesis-abre", 100f, 100f, 130f, 200f)
        val hand = listOf(Bounds(200f, 300f, 260f, 380f), Bounds(350f, 300f, 410f, 380f), Bounds(500f, 300f, 560f, 380f))
        assertSame(g, AutoAlign.anchor(close, g, listOf(open), hand) { lookup(it) })
    }

    @Test fun `bridges are only handwritten strokes, without the gesture`() {
        val hand = InkStroke("h", listOf(InkPoint(0f, 0f), InkPoint(10f, 20f)))
        val gesture = InkStroke("g", listOf(InkPoint(50f, 0f), InkPoint(60f, 20f)))
        val converted = InkStroke("c", listOf(InkPoint(100f, 0f), InkPoint(110f, 20f)), patternId = "preset-2")
        assertEquals(listOf(hand.bounds), AutoAlign.bridges(listOf(hand, gesture, converted), setOf("g")))
    }

    // ---------- Nombres de los paréntesis del usuario ----------

    private fun user(id: String, name: String) = InkPattern(id, name, listOf(ink), ink)

    @Test fun `user brackets pair whatever the word order, accents or case`() {
        val pairs = listOf(
            "Abrir corchete" to "Cerrar corchete",
            "Llave abre" to "Llave cierra",
            "ABRE PARENTESIS" to "cierra Paréntesis",
            "  paréntesis abrir " to "Paréntesis cerrar",
        )
        for ((o, cl) in pairs) {
            val open = user("u-open", o)
            val close = user("u-close", cl)
            val byId = mapOf(open.id to open, close.id to close)
            val g = Bounds(600f, 150f, 630f, 260f)
            val c = AutoAlign.anchor(close, g, listOf(AutoAlign.Item(open.id, Bounds(100f, 80f, 130f, 320f)), AutoAlign.Item(PresetPatterns.PREFIX + "2", Bounds(300f, 160f, 340f, 240f)))) { byId[it] ?: presets[it] }
            assertEquals("$o / $cl", 240f, c.height, 0.5f)
            assertEquals("$o / $cl", 200f, c.centerY, 0.5f)
        }
    }

    @Test fun `a bare Parentesis name is not a bracket`() {
        val p = user("u-p", "Paréntesis")
        val g = Bounds(150f, 120f, 180f, 170f)
        // Un '2' junto a él lo toma de base como a cualquier forma (no es contenedor): crece.
        val c = AutoAlign.anchor(preset("2"), g, listOf(AutoAlign.Item(p.id, Bounds(100f, 40f, 130f, 240f)))) { if (it == p.id) p else presets[it] }
        assertTrue("alto ${c.height}", c.height > 150f)
    }

    // ---------- Controlador: los trazos a mano de la página unen la expresión ----------

    private fun closeAfterHand(handTop: Float, brush: Brush? = null): Pair<Bounds, Bounds> {
        val open = preset("parentesis-abre")
        val close = preset("parentesis-cierra")
        val opener = InkReplacement.place(open, Bounds(100f, 100f, 140f, 300f), IdGenerator.sequential())
        val hand = listOf(300f, 500f, 700f).mapIndexed { i, x ->
            val s = InkStroke("h$i", listOf(InkPoint(x, handTop), InkPoint(x + 60f, handTop + 100f)))
            if (brush == null) s else s.copy(brush = brush)
        }
        val gesture = InkStroke("g", listOf(InkPoint(900f, 160f), InkPoint(920f, 200f), InkPoint(900f, 240f)))
        val c = InkController(PageState(opener + hand + gesture), History(), { listOf(open, close) }, IdGenerator.sequential())
        return opener.bounds() to c.convertWith(close, listOf("g"))!!.bounds()
    }

    @Test fun `the controller links a closer to a far opener through handwriting on its line`() {
        val (ob, aligned) = closeAfterHand(150f)
        assertEquals(ob.height, aligned.height, 1f)
        assertEquals(ob.centerY, aligned.centerY, 1f)
    }

    @Test fun `the controller ignores handwriting on another line`() {
        val (ob, aligned) = closeAfterHand(400f)
        assertTrue("alto ${aligned.height}", aligned.height < ob.height * 0.6f)
        assertEquals(200f, aligned.centerY, 1f)
    }

    @Test fun `the controller does not use highlighter strokes as bridges`() {
        val (ob, aligned) = closeAfterHand(150f, Brush.HIGHLIGHTER)
        assertTrue("alto ${aligned.height}", aligned.height < ob.height * 0.6f)
        assertEquals(200f, aligned.centerY, 1f)
    }
}
