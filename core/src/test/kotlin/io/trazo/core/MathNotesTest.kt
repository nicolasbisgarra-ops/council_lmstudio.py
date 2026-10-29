package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuentas en la hoja: «=» al final del renglón → resultado con los patrones, en verde. */
class MathNotesTest {
    private val patterns = PresetPatterns.all()
    private val byId = patterns.associateBy { it.id }
    private val signs = PresetPatterns.signs().associateBy { it.name }
    private val user = PresetPatterns.bundled().associateBy { it.name }
    private val ids = IdGenerator.sequential("t")

    /** Escribe los signos [names] en un renglón a la altura [y], de 40 de alto. */
    private fun row(y: Float, vararg names: String): List<InkStroke> {
        var x = 0f
        return names.flatMap { name ->
            val p = signs[name] ?: user.getValue(name)
            val flat = p.sizing == OutputSizing.MATCH_WIDTH
            val anchor = if (flat) Bounds(x, y + 15f, x + 30f, y + 25f) else Bounds(x, y, x + 25f, y + 40f)
            InkReplacement.place(p, anchor, ids).also { x = it.bounds().right + 12f }
        }
    }

    private fun labels(ink: List<InkStroke>) = MathNotes.rows(MathNotes.glyphs(ink, byId)).map { r -> r.map { it.label } }

    @Test fun `una cuenta que termina en igual muestra el resultado a la derecha y en verde`() {
        val page = row(0f, "2", "Más", "3", "Igual")
        val out = MathNotes.results(page, patterns)
        assertEquals(listOf(listOf("5")), labels(out))
        assertTrue(out.all { it.color == MathNotes.DEFAULT_RESULT_COLOR })
        assertTrue(out.bounds().left > page.bounds().right)
        assertEquals(page.filter { it.patternId == "preset-igual" }.bounds().centerY, out.bounds().centerY, 6f)
    }

    @Test fun `una division que no es entera se muestra como fraccion`() {
        val out = MathNotes.results(row(0f, "1", "Dividido", "2", "Igual"), patterns)
        assertEquals(setOf("1", "2", "Barra"), labels(out).flatten().toSet())
    }

    @Test fun `un resultado negativo lleva el signo menos`() {
        val out = MathNotes.results(row(0f, "2", "Menos", "5", "Igual"), patterns)
        assertEquals(listOf("Menos", "3"), labels(out).flatten())
    }

    @Test fun `usa las variables definidas en otro renglon`() {
        val page = row(0f, "x", "Igual", "3") + row(100f, "x", "Más", "1", "Igual")
        val out = MathNotes.results(page, patterns)
        assertEquals(listOf(listOf("4")), labels(out))
        assertTrue(out.bounds().top > 90f)
    }

    @Test fun `cada hoja es aislada y no usa las variables de otra`() {
        val first = PageState(row(0f, "x", "Igual", "3") + row(100f, "x", "Más", "1", "Igual"))
        val second = PageState(row(0f, "x", "Más", "1", "Igual"))
        val c = InkController(first, History(), { patterns }, IdGenerator.sequential("c"))
        assertEquals(listOf(listOf("4")), labels(c.mathResults))
        c.attach(second, History())
        assertTrue(c.mathResults.isEmpty())
        c.attach(first, History())
        assertEquals(listOf(listOf("4")), labels(c.mathResults))
    }

    @Test fun `los nombres sin tilde ni mayusculas tambien se leen`() {
        val renamed = patterns.map { p -> when (p.name) { "Más" -> p.copy(name = "mas"); "Igual" -> p.copy(name = "IGUAL ") ; else -> p } }
        val out = MathNotes.results(row(0f, "2", "Más", "3", "Igual"), renamed)
        assertEquals(listOf(listOf("5")), labels(out))
    }

    @Test fun `si no puede calcular explica por que`() {
        val hand = InkStroke("h", listOf(InkPoint(0f, 0f), InkPoint(10f, 30f)))
        assertTrue(MathNotes.problem(listOf(hand), setOf("h"), patterns).contains("escritura a mano"))
        val page = row(0f, "2", "Más", "3", "Igual")
        val odd = patterns.map { p -> if (p.name == "Más") p.copy(name = "suma rara") else p }
        val msg = MathNotes.problem(page, page.map { it.id }.toSet(), odd)
        assertTrue(msg, msg.contains("«suma rara»"))
        val inside = page + InkStroke("h", listOf(InkPoint(30f, 10f), InkPoint(34f, 30f)))
        assertTrue(MathNotes.problem(inside, page.map { it.id }.toSet(), patterns).contains("sin convertir"))
    }

    @Test fun `dos cuentas a la misma altura separadas se calculan cada una`() {
        val left = row(0f, "2", "x", "3")
        val shift = 400f - left.bounds().left
        val right = row(0f, "5", "Más", "3", "Igual").map { it.transformed(Affine.translate(shift, 0f)) }
        val out = MathNotes.results(left + right, patterns)
        assertEquals(listOf(listOf("8")), labels(out))
        assertTrue(out.bounds().left > right.bounds().right)
        // Escritura a mano del otro lado del renglón tampoco la bloquea.
        val hand = InkStroke("h", listOf(InkPoint(0f, 0f), InkPoint(60f, 40f)))
        assertEquals(listOf(listOf("8")), labels(MathNotes.results(listOf(hand) + right, patterns)))
    }

    @Test fun `despeja la incognita de una ecuacion de otro renglon`() {
        val page = row(0f, "2", "x", "Igual", "5") + row(100f, "x", "Igual")
        val out = MathNotes.results(page, patterns)
        assertEquals(setOf("5", "2", "Barra"), labels(out).flatten().toSet())
        assertTrue(out.bounds().top > 90f)
        // Encadenado: y depende de x, que sale de la ecuación.
        val chain = row(0f, "2", "x", "Igual", "6") + row(100f, "y", "Igual", "x", "Más", "1") + row(200f, "y", "Igual")
        assertEquals(listOf(listOf("4")), labels(MathNotes.results(chain, patterns)))
        // No lineal: no inventa un resultado.
        val square = row(0f, "x", "Por", "x", "Igual", "4") + row(100f, "x", "Igual")
        assertTrue(MathNotes.results(square, patterns).isEmpty())
    }

    @Test fun `el patron elegido como incognita vale con su letra aunque se llame distinto`() {
        val renamed = patterns.map { p -> if (p.name == "x") p.copy(name = "Mi equis rara", unknown = "x") else p }
        val page = row(0f, "2", "x", "Igual", "5") + row(100f, "x", "Igual")
        assertEquals(setOf("5", "2", "Barra"), labels(MathNotes.results(page, renamed)).flatten().toSet())
        // «No usar en cuentas»: estorba como la escritura a mano.
        val ignored = patterns.map { p -> if (p.name == "x") p.copy(unknown = "") else p }
        assertTrue(MathNotes.results(row(0f, "2", "Más", "3", "Igual", "x"), ignored).isEmpty())
        assertEquals(listOf(listOf("5")), labels(MathNotes.results(row(0f, "2", "Más", "3", "Igual"), ignored)))
    }

    @Test fun `tocar el resultado explica la cuenta y de donde sale cada variable`() {
        val page = row(0f, "2", "x", "Igual", "5") + row(100f, "y", "Igual", "x", "Más", "1") + row(200f, "y", "Igual")
        val shown = MathNotes.results(page, patterns)
        val e = MathNotes.explain(page, patterns, shown.bounds().centerX, shown.bounds().centerY)!!
        assertEquals("y = 7/2", e.steps[0])
        assertTrue(e.steps.toString(), e.steps.contains("≈ 3,5"))
        assertTrue(e.steps.toString(), e.steps.any { it.startsWith("y = 7/2") && it.contains("«y = x + 1»") })
        assertTrue(e.steps.toString(), e.steps.any { it.startsWith("x = 5/2") && it.contains("despejada de «2x = 5»") })
        // Lejos del resultado no hay explicación; con el lazo vale todo el renglón.
        assertEquals(null, MathNotes.explain(page, patterns, -500f, -500f))
        val first = page.filter { it.bounds.top < 50f }.bounds()
        assertEquals(null, MathNotes.explain(page, patterns, first.left + 2f, first.centerY))
        assertTrue(MathNotes.explain(page, patterns, first.left + 2f, first.centerY, wholeLine = true)!!.steps[0].startsWith("2x = 5"))
    }

    @Test fun `un toque de la pluma sobre el resultado lo explica sin dejar tinta`() {
        val page = PageState(row(0f, "5", "Más", "5", "Igual"))
        val c = InkController(page, History(), { patterns }, IdGenerator.sequential("c"))
        val events = mutableListOf<InkEvent>()
        c.addListener { events += it }
        val at = c.mathResults.bounds()
        val before = page.strokes.size
        c.penDown(at.centerX, at.centerY, 0.5f, 0L, PointerKind.STYLUS)
        c.penUp(at.centerX, at.centerY, 0.5f, 80L)
        assertEquals(before, page.strokes.size)
        assertEquals("5 + 5 = 10", (events.single() as InkEvent.MathExplained).explanation.steps[0])
    }

    @Test fun `no tapa lo que se escribio despues del igual`() {
        assertTrue(MathNotes.results(row(0f, "2", "Más", "3", "Igual", "5"), patterns).isEmpty())
        val page = row(0f, "2", "Más", "3", "Igual")
        val hand = InkStroke("h", listOf(InkPoint(page.bounds().right + 20f, 10f), InkPoint(page.bounds().right + 30f, 30f)))
        assertTrue(MathNotes.results(page + hand, patterns).isEmpty())
    }

    @Test fun `la escritura sin reconocer dentro de la cuenta la deja sin resultado`() {
        val page = row(0f, "2", "Más", "3", "Igual")
        val hand = InkStroke("h", listOf(InkPoint(30f, 10f), InkPoint(34f, 30f)))
        assertTrue(MathNotes.results(page + hand, patterns).isEmpty())
    }

    @Test fun `calcular la seleccion agrega el igual y el resultado en un paso deshacible`() {
        val page = PageState(row(0f, "4", "Por", "2"))
        val c = InkController(page, History(), { patterns }, IdGenerator.sequential("c"))
        c.select(page.strokes.map { it.id })
        assertTrue(c.calculateSelection())
        assertEquals(listOf(listOf("4", "Por", "2", "Igual", "8")), labels(page.strokes))
        assertTrue(c.mathResults.isEmpty())
        c.undo()
        assertEquals(listOf(listOf("4", "Por", "2")), labels(page.strokes))
    }

    @Test fun `el controlador recalcula al cambiar la pagina y se puede apagar`() {
        val page = PageState(row(0f, "6", "Más", "1", "Igual"))
        val c = InkController(page, History(), { patterns }, IdGenerator.sequential("c"))
        assertEquals(listOf(listOf("7")), labels(c.mathResults))
        c.mathEnabled = false
        assertTrue(c.mathResults.isEmpty())
        c.mathEnabled = true
        c.selectAll(); c.deleteSelection()
        assertTrue(c.mathResults.isEmpty())
    }
}
