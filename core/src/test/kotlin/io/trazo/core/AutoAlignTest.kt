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
