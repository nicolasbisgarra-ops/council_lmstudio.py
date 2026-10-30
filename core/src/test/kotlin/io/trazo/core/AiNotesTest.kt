package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modo IA en la hoja: renglones numerados, doble verificación, marcas guardadas y su disposición. */
class AiNotesTest {
    private val patterns = PresetPatterns.all()
    private val signs = PresetPatterns.signs().associateBy { it.name }
    private val user = PresetPatterns.bundled().associateBy { it.name }
    private val ids = IdGenerator.sequential("t")

    private fun row(y: Float, vararg names: String): List<InkStroke> {
        var x = 0f
        return names.flatMap { name ->
            val p = signs[name] ?: user.getValue(name)
            val flat = p.sizing == OutputSizing.MATCH_WIDTH
            val anchor = if (flat) Bounds(x, y + 15f, x + 30f, y + 25f) else Bounds(x, y, x + 25f, y + 40f)
            InkReplacement.place(p, anchor, ids).also { x = it.bounds().right + 12f }
        }
    }

    private fun hand(id: String, x: Float, y: Float) = InkStroke(id, listOf(InkPoint(x, y), InkPoint(x + 20f, y + 30f)))

    private fun line(n: Int, text: String) = AiNotes.ReviewLine(n, text, Bounds(0f, n * 100f, 200f, n * 100f + 40f), listOf("s$n"))

    @Test fun `numera los renglones de arriba hacia abajo y la escritura a mano va como signo de pregunta`() {
        val page = row(0f, "2", "Más", "3", "Igual", "5") + listOf(hand("h", 0f, 100f)) + row(200f, "4", "Igual", "4")
        val lines = MathNotes.reviewLines(page, patterns)
        assertEquals(listOf(1, 2, 3), lines.map { it.number })
        assertEquals("?", lines[1].text)
        assertEquals(listOf("h"), lines[1].strokeIds)
        assertTrue(lines[0].text, lines[0].text.replace(" ", "").startsWith("2+3=5"))
        assertTrue(lines[0].bounds.bottom < lines[1].bounds.top)
    }

    @Test fun `con lazo solo entran los trazos elegidos y el resaltador nunca`() {
        val first = row(0f, "2", "Igual", "2")
        val page = first + row(100f, "3", "Igual", "3") + InkStroke("m", listOf(InkPoint(0f, 0f), InkPoint(90f, 0f)), brush = Brush.HIGHLIGHTER)
        assertEquals(1, MathNotes.reviewLines(page, patterns, first.map { it.id }.toSet()).size)
        assertTrue(MathNotes.reviewLines(page, patterns).none { "m" in it.strokeIds })
    }

    @Test fun `el motor da por buena una identidad y por mala una cuenta sin letras`() {
        val c = AiNotes.check(listOf(line(1, "2+3=5"), line(2, "2+3=6"), line(3, "2x+3x=5x"), line(4, "?"), line(5, "2x=6"), line(6, "2+3=")))
        assertEquals(
            listOf(AiNotes.Verdict.HOLDS, AiNotes.Verdict.FAILS, AiNotes.Verdict.HOLDS, AiNotes.Verdict.UNKNOWN, AiNotes.Verdict.UNKNOWN, AiNotes.Verdict.UNKNOWN),
            c.map { it.verdict },
        )
        assertEquals("5", c[1].left)
        assertEquals("6", c[1].right)
    }

    @Test fun `una marca de cuenta que el motor contradice queda dudosa y el motor agrega la que falta`() {
        val lines = listOf(line(1, "2+3=5"), line(2, "4·2=9"), line(3, "x+1=2"))
        val ai = AiNotes.place(
            listOf(AiNotes.Mark(1, "CALCULATION", "suma mal"), AiNotes.Mark(3, "CONCEPT", "despeje"), AiNotes.Mark(9, "CALCULATION", "no existe")),
            lines, ids, "r1",
        )
        assertEquals("el renglón 9 no existe", 2, ai.size)
        val out = AiNotes.crossCheck(ai, lines, ids, "r1")
        assertTrue(out.first { it.line == 1 }.disputed)
        assertFalse(out.first { it.line == 3 }.disputed)
        val engine = out.single { it.fromEngine }
        assertEquals(2, engine.line)
        assertTrue(engine.detail, engine.detail.contains("8") && engine.detail.contains("9"))
    }

    @Test fun `una marca se va sola si cambian los trazos de su renglon`() {
        val ink = row(0f, "2", "Más", "3", "Igual", "6")
        val lines = MathNotes.reviewLines(ink, patterns)
        val note = AiNotes.place(listOf(AiNotes.Mark(1, "CALCULATION", "x")), lines, ids, "r").single()
        val ai = PageAi(listOf(note))
        assertEquals(ai, AiNotes.prune(ai, ink))
        assertTrue("con resaltador encima sigue", AiNotes.prune(ai, ink + InkStroke("hl", listOf(InkPoint(0f, 20f), InkPoint(200f, 20f)), brush = Brush.HIGHLIGHTER))!!.notes.isNotEmpty())
        assertTrue("borrar un trazo la quita", AiNotes.prune(ai, ink.drop(1))!!.notes.isEmpty())
        assertTrue("escribir encima la quita", AiNotes.prune(ai, ink + hand("n", 30f, 5f))!!.notes.isEmpty())
        val moved = ink.map { it.transformed(Affine.translate(0f, 50f)) }
        assertTrue("moverlo la quita", AiNotes.prune(ai, moved)!!.notes.isEmpty())
    }

    @Test fun `una revision nueva reemplaza las marcas de sus renglones y guarda el historial`() {
        val a = AiNote("a", 1, "CALCULATION", "vieja", bounds = Bounds(0f, 0f, 1f, 1f), strokeIds = listOf("s1"))
        val b = AiNote("b", 2, "CONCEPT", "otra", bounds = Bounds(0f, 5f, 1f, 6f), strokeIds = listOf("s2"))
        val c = AiNote("c", 1, "NOTATION", "nueva", bounds = Bounds(0f, 0f, 1f, 1f), strokeIds = listOf("s1"))
        var ai: PageAi? = PageAi(listOf(a, b))
        ai = AiNotes.record(ai, AiReviewRecord("r", 1, "m", false, notes = listOf(c), strokeIds = listOf("s1")))
        assertEquals(listOf("b", "c"), ai.notes.map { it.id })
        repeat(40) { ai = AiNotes.record(ai, AiReviewRecord("x$it", 2, "m", true)) }
        assertEquals(AiNotes.HISTORY_LIMIT, ai!!.history.size)
        assertEquals("x39", ai!!.history.last().id)
    }

    @Test fun `sin errores se muestra el tilde mientras la tinta no cambie`() {
        val ink = row(0f, "2", "Igual", "2")
        val rec = AiReviewRecord("r", 1, "m", true, 10.0, area = ink.bounds(), strokeIds = ink.map { it.id })
        val ai = PageAi(history = listOf(rec))
        assertNotNull(ai.verdict(ink))
        assertNull(ai.verdict(ink.drop(1)))
        assertNull(PageAi(history = listOf(rec.copy(correct = false))).verdict(ink))
    }

    @Test fun `las notas se ubican al margen sin pisarse y se tocan con el dedo`() {
        val n1 = AiNote("1", 1, "CALCULATION", "Error de signo al pasar restando", bounds = Bounds(20f, 100f, 300f, 140f))
        val n2 = AiNote("2", 2, "CONCEPT", "Regla mal aplicada", bounds = Bounds(20f, 112f, 300f, 150f))
        val wide = AiNote("3", 3, "MISSING_STEP", "Falta justificar este paso", bounds = Bounds(10f, 400f, 900f, 440f))
        val placed = AiNotes.layout(listOf(n2, wide, n1))
        assertEquals(listOf("1", "2", "3"), placed.map { it.note.id })
        val (p1, p2, p3) = placed
        assertTrue("al margen derecho", p1.label.left > n1.bounds.right)
        assertTrue("no se pisan", p2.label.top >= p1.label.bottom)
        assertTrue("sin lugar al costado va debajo", p3.label.top > wide.bounds.bottom)
        assertTrue(p3.label.right <= Notebook.PAGE_WIDTH)
        assertEquals("2", AiNotes.noteAt(listOf(n1, n2, wide), p2.label.centerX, p2.label.centerY)?.id)
        assertNull(AiNotes.noteAt(listOf(n1, n2, wide), 5f, 5f))
    }

    @Test fun `las marcas viajan en el archivo y una hoja vieja se sigue leyendo`() {
        val note = AiNote("1", 1, "CONCEPT", "breve", "detalle", "corrección", Bounds(1f, 2f, 3f, 4f), listOf("a"), disputed = true)
        val page = Page("p", ai = PageAi(listOf(note), listOf(AiReviewRecord("r", 5, "modelo", false, 6.5, notes = listOf(note)))))
        assertEquals(page, TrazoFormat.decodePage(TrazoFormat.encodePage(page)))
        assertFalse("sin Modo IA no se escribe nada", TrazoFormat.encodePage(Page("q")).contains("\"ai\""))
        assertNull(TrazoFormat.decodePage("""{"id":"v","strokes":[]}""").ai)
    }

    @Test fun `tocar la nota abre su burbuja y la goma borra solo esa marca`() {
        val ink = row(0f, "2", "Más", "3", "Igual", "6")
        val note = AiNotes.place(listOf(AiNotes.Mark(1, "CALCULATION", "La suma da 5")), MathNotes.reviewLines(ink, patterns), ids, "r").single()
        val state = PageState(ink).apply { ai = PageAi(listOf(note)) }
        val c = InkController(state, History(), { patterns }, IdGenerator.sequential("c"))
        val events = mutableListOf<InkEvent>()
        c.addListener { events += it }
        val label = AiNotes.layout(listOf(note)).single().label
        c.penDown(label.centerX, label.centerY, 1f, 0, PointerKind.STYLUS)
        c.penUp(label.centerX, label.centerY, 1f, 50)
        assertEquals(note, events.filterIsInstance<InkEvent.AiNoteTapped>().single().note)
        assertEquals("el toque no deja un punto", ink.size, c.page.strokes.size)
        c.aiVisible = false
        assertNull("con la capa oculta no se toca", c.aiNoteAt(label.centerX, label.centerY))
        c.aiVisible = true
        c.tool = Tool.ERASER
        c.penDown(label.centerX, label.centerY, 1f, 1000, PointerKind.STYLUS)
        c.penUp(label.centerX, label.centerY, 1f, 1050)
        assertTrue(c.page.ai!!.notes.isEmpty())
        assertEquals("la tinta queda", ink, c.page.strokes)
    }
}
