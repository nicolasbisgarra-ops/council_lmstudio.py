package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Barra elástica de la raíz: el ✓ conserva su forma y la barra llega hasta donde terminó el gesto. */
class StretchTest {
    private val root = DefaultPatterns.root()
    private val src = root.outputInk.bounds()

    private fun place(pattern: InkPattern, anchor: Bounds, elastic: Boolean = true) =
        InkReplacement.place(pattern, anchor, IdGenerator.sequential(), elastic = elastic)

    /** Escala uniforme que aplica MATCH_HEIGHT para un gesto de alto [h]. */
    private fun scaleFor(h: Float) = h / src.height * root.outputScale

    @Test fun `long gesture - the bar reaches the end of the gesture and the tick keeps its shape`() {
        val anchor = Bounds(100f, 100f, 500f, 160f)
        val out = place(root, anchor)
        val b = out.bounds()
        assertEquals(100f, b.left, 0.5f)
        assertEquals(500f, b.right, 1f)
        assertEquals(src.height * scaleFor(60f), b.height, 0.5f)
        assertEquals(anchor.centerY, b.centerY, 0.5f)

        // El ✓ es el mismo que con escala uniforme, solo desplazado.
        val tick = out[0].points
        val uniform = place(root, anchor, elastic = false)[0].points
        val dx = tick[0].x - uniform[0].x
        tick.zip(uniform).forEach { (p, q) ->
            assertEquals(dx, p.x - q.x, 1e-3f)
            assertEquals(q.y, p.y, 1e-3f)
        }
        // La barra empieza donde termina el ✓.
        assertEquals(tick.maxOf { it.x }, out[1].bounds.left, 1f)
    }

    @Test fun `short gesture - the bar shrinks to the end of the gesture`() {
        val out = place(root, Bounds(100f, 100f, 170f, 160f)).bounds()
        assertEquals(100f, out.left, 0.5f)
        assertEquals(170f, out.right, 1f)
    }

    @Test fun `tiny gesture - the bar keeps a minimum length and overflows to the right`() {
        val s = scaleFor(60f)
        val out = place(root, Bounds(100f, 100f, 130f, 160f))
        val bar = out[1].bounds
        assertEquals(100f, out.bounds().left, 0.5f)
        assertTrue("barra ${bar.width}", bar.width >= 0.2f * 150f * s - 1f)
        assertTrue(out.bounds().right > 130f)
    }

    @Test fun `inserting from the library keeps the natural bar`() {
        val c = InkController(PageState(emptyList()), History(), { listOf(root) }, IdGenerator.sequential())
        val b = c.insertPattern(root, 300f, 300f, 100f).bounds()
        assertEquals(100f, b.height, 0.5f)
        assertEquals(100f * src.width / src.height, b.width, 1f)
        assertEquals(300f, b.centerX, 0.5f)
        assertEquals(300f, b.centerY, 0.5f)
    }

    @Test fun `without stretch or with elastic off the output is centred`() {
        val anchor = Bounds(100f, 100f, 500f, 160f)
        for (b in listOf(place(root.copy(stretch = null), anchor).bounds(), place(root, anchor, elastic = false).bounds())) {
            assertEquals(anchor.centerX, b.centerX, 0.01f)
            assertEquals(anchor.centerY, b.centerY, 0.01f)
            assertEquals(src.width * scaleFor(60f), b.width, 0.5f)
        }
    }

    @Test fun `invalid stretch is ignored`() {
        val anchor = Bounds(100f, 100f, 500f, 160f)
        val plain = place(root.copy(stretch = null), anchor)
        for (bad in listOf(Stretch(from = Float.NaN), Stretch(from = 0.8f, to = 0.3f), Stretch(from = 0.5f, to = 0.5f))) {
            assertEquals(bad.toString(), plain, place(root.copy(stretch = bad), anchor))
        }
    }

    @Test fun `upgrade adds the bar to an old root and keeps the user's examples`() {
        val extra = root.gestureExamples[0].map { s -> s.copy(id = "mine", points = s.points.map { it.copy(x = it.x + 3f) }) }
        val old = root.copy(stretch = null, category = null, enabled = false, gestureExamples = root.gestureExamples + listOf(extra))
        val up = PatternUpgrades.upgrade(listOf(old)).single()
        assertEquals(old.copy(stretch = root.stretch, category = root.category), up)
        assertFalse(up.enabled)
        assertEquals(4, up.gestureExamples.size)
        // Cambiar color o grosor de la salida no es redibujarla.
        val recoloured = old.copy(outputInk = old.outputInk.map { it.copy(color = 0xFF1E88E5.toInt(), width = 5f) })
        assertEquals(root.stretch, PatternUpgrades.upgrade(listOf(recoloured)).single().stretch)
    }

    @Test fun `upgrade leaves redrawn outputs and user patterns alone`() {
        val redrawn = root.copy(stretch = null, outputInk = listOf(root.outputInk[0]))
        assertNull(PatternUpgrades.upgrade(listOf(redrawn)).single().stretch)
        val mine = root.copy(id = "mi-raiz", builtIn = false, stretch = null, category = null)
        assertEquals(mine, PatternUpgrades.upgrade(listOf(mine)).single())
        val sameIdNotBuiltIn = root.copy(builtIn = false, stretch = null, category = null)
        assertEquals(sameIdNotBuiltIn, PatternUpgrades.upgrade(listOf(sameIdNotBuiltIn)).single())
        // Los que ya están al día no cambian.
        assertEquals(DefaultPatterns.all(), PatternUpgrades.upgrade(DefaultPatterns.all()))
    }

    @Test fun `stretch survives the json round trip and old files decode without it`() {
        assertEquals(listOf(root), TrazoFormat.decodePatterns(TrazoFormat.encodePatterns(listOf(root))))
        val plain = root.copy(stretch = null)
        val text = TrazoFormat.encodePatterns(listOf(plain))
        assertFalse(text.contains("stretch"))
        assertNull(TrazoFormat.decodePatterns(text).single().stretch)
    }

    @Test fun `detectBar finds the root bar and nothing in the integral or the fraction`() {
        val found = checkNotNull(Stretch.detectBar(root.outputInk)) { "la raíz tiene barra" }
        assertEquals(Axis.HORIZONTAL, found.axis)
        assertTrue("from ${found.from}", found.from in 0.19f..0.26f)
        assertEquals(1f, found.to, 1e-6f)
        assertNull(Stretch.detectBar(DefaultPatterns.integral().outputInk))
        assertNull(Stretch.detectBar(DefaultPatterns.fraction().outputInk))
        assertNull(Stretch.detectBar(emptyList()))
    }
}
