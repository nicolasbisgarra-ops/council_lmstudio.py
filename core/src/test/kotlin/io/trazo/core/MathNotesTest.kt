package io.trazo.core

import io.trazo.core.math.LineResult
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
        assertTrue(MathNotes.problem(inside, inside.map { it.id }.toSet(), patterns).contains("sin convertir"))
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

    private fun place(name: String, b: Bounds) = InkReplacement.place(signs[name] ?: user[name] ?: patterns.first { it.name == name }, b, ids)

    /** Fracción apilada en [x]: [num] sobre la raya [bar] sobre [den], centrada en la línea de un renglón en [y]. */
    private fun stack(x: Float, y: Float, num: String, den: String, bar: String = "Fracción") =
        place(bar, Bounds(x, y + 17f, x + 40f, y + 23f)) + place(num, Bounds(x + 8f, y - 26f, x + 32f, y + 12f)) +
            place(den, Bounds(x + 8f, y + 28f, x + 32f, y + 66f))

    @Test fun `una fraccion apilada se lee en un solo renglon`() {
        // 1/2 + 1/3 = 5/6
        val page = stack(0f, 0f, "1", "2") + place("Más", Bounds(55f, 5f, 85f, 35f)) + stack(100f, 0f, "1", "3") +
            place("Igual", Bounds(155f, 15f, 185f, 25f))
        assertEquals(1, MathNotes.rows(MathNotes.glyphs(page, byId)).size)
        val out = MathNotes.results(page, patterns)
        assertEquals(listOf("5", "6", "Barra").sorted(), labels(out).flatten().sorted())
        assertEquals(20f, out.filter { it.patternId == "preset-barra" }.bounds().centerY, 4f)
        val e = MathNotes.explain(page, patterns, out.bounds().centerX, out.bounds().centerY)!!
        assertEquals("1/2 + 1/3 = 5/6", e.steps[0])
    }

    @Test fun `un menos con algo arriba y abajo es una barra de fraccion`() {
        val page = stack(0f, 0f, "6", "3", bar = "Menos") + place("Igual", Bounds(55f, 15f, 85f, 25f))
        assertEquals(listOf(listOf("2")), labels(MathNotes.results(page, patterns)))
        // Una fracción con incógnita se despeja: x/2 = 3 → x = 6.
        val solve = stack(0f, 0f, "x", "2") + place("Igual", Bounds(55f, 15f, 85f, 25f)) + place("3", Bounds(100f, 0f, 125f, 40f)) +
            row(150f, "x", "Igual")
        assertEquals(listOf(listOf("6")), labels(MathNotes.results(solve, patterns)))
    }

    @Test fun `la barra inclinada y los dos puntos dividen en linea y el porcentaje divide por cien`() {
        assertEquals(listOf(listOf("2")), labels(MathNotes.results(row(0f, "6", "Barra", "3", "Igual"), patterns)))
        assertEquals(listOf(listOf("4")), labels(MathNotes.results(row(0f, "5", "0", "Porcentaje", "Por", "8", "Igual"), patterns)))
        val colon = patterns.map { p -> if (p.name == "Más") p.copy(name = ":") else p }
        assertEquals(listOf(listOf("3")), labels(MathNotes.results(row(0f, "6", "Más", "2", "Igual"), colon)))
        val r = MathNotes.evaluate(listOf("x = 2", "y = 3", "8:4", "20%", "2x/5y", "2*x/5*y"))
        assertEquals(listOf("2", "1/5", "4/15", "12/5"), r.drop(2).map { (it as LineResult.Value).value.display() })
    }

    @Test fun `lo escrito pegado es un solo termino frente al dividido`() {
        // 2x ÷ 5y con x = 2 e y = 3 es 4/15, no 2·2÷5·3 = 12/5.
        val page = row(0f, "x", "Igual", "2") + row(100f, "y", "Igual", "3") + row(200f, "2", "x", "Dividido", "5", "y", "Igual")
        assertEquals(setOf("4", "1", "5", "Barra"), labels(MathNotes.results(page, patterns)).flatten().toSet())
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

    private fun custom(name: String, vararg pts: Pair<Float, Float>): InkPattern {
        val ink = listOf(InkStroke("$name-o", pts.map { InkPoint(it.first, it.second) }))
        return InkPattern("user-$name", name, listOf(ink), ink)
    }

    private val k = custom("k", 0f to 0f, 0f to 40f, 0f to 20f, 20f to 0f, 0f to 20f, 20f to 40f)
    private val comma = custom("Coma", 4f to 0f, 4f to 6f, 0f to 12f)
    private val withLetters = listOf(k, comma) + patterns
    private val lettersById = withLetters.associateBy { it.id }

    private fun calcLabels(ink: List<InkStroke>) = MathNotes.rows(MathNotes.glyphs(ink, lettersById)).map { r -> r.map { it.label } }

    private fun controller(page: PageState, ps: List<InkPattern> = patterns) = InkController(page, History(), { ps }, IdGenerator.sequential("c"))

    @Test fun `calcular con el lazo solo usa lo seleccionado`() {
        val page = PageState(row(0f, "2", "Más", "3", "Por", "4"))
        val c = controller(page)
        // Solo «3 × 4»: el 2 y el + quedan afuera.
        c.select(page.strokes.filter { s -> s.bounds.left > 60f }.map { it.id })
        assertTrue(c.calculateSelection())
        val results = page.strokes.filter { it.calc?.result == true }
        assertEquals(listOf(listOf("1", "2")), labels(results))
    }

    @Test fun `una cuenta guardada no cambia al moverla ni al cambiarle el tamano`() {
        val page = PageState(row(0f, "4", "Por", "2") + row(0f, "Más", "5").map { it.transformed(Affine.translate(400f, 0f)) })
        val c = controller(page)
        c.select(page.strokes.filter { it.bounds.left < 300f }.map { it.id })
        assertTrue(c.calculateSelection())
        val op = page.strokes.filter { it.calc != null }.map { it.id }.toSet()
        // La cuenta se lleva al lado del «+ 5»: no lo toma ni se recalcula.
        c.select(op.toList())
        c.transformSelection(Affine.translate(150f, 0f))
        c.scaleSelection(1.5f)
        assertEquals(listOf("8"), labels(page.strokes.filter { it.calc?.result == true }).flatten())
        assertTrue(page.strokes.filter { it.id !in op }.all { it.calc == null })
        assertTrue(c.mathResults.isEmpty())
    }

    @Test fun `borrar o agregar un signo en la cuenta la recalcula en el mismo paso deshacible`() {
        val page = PageState(row(0f, "4", "Por", "2"))
        val c = controller(page)
        c.select(page.strokes.map { it.id })
        assertTrue(c.calculateSelection())
        fun result() = labels(page.strokes.filter { it.calc?.result == true }).flatten()
        assertEquals(listOf("8"), result())
        // Se borra el 2 y se escribe un 3 en su lugar.
        val two = page.strokes.first { it.calc?.result == false && byId[it.patternId]?.name == "2" }
        val b = two.bounds
        c.select(page.strokes.filter { it.groupId == two.groupId }.map { it.id }); c.deleteSelection()
        assertTrue(result().isEmpty())
        c.insertPattern((signs["3"] ?: user.getValue("3")), b.centerX, b.centerY, b.height)
        assertEquals(listOf("1", "2"), result())
        // Deshacer el 3 vuelve a la cuenta a medias; deshacer el borrado, al 8.
        c.undo(); assertTrue(result().isEmpty())
        c.undo(); assertEquals(listOf("8"), result())
    }

    @Test fun `borrar el resultado libera la cuenta`() {
        val page = PageState(row(0f, "4", "Por", "2"))
        val c = controller(page)
        c.select(page.strokes.map { it.id })
        assertTrue(c.calculateSelection())
        c.select(page.strokes.filter { it.calc?.result == true }.map { it.id }); c.deleteSelection()
        assertTrue(page.strokes.all { it.calc == null })
        // Ya no es una cuenta guardada: vuelve el resultado automático.
        assertEquals(listOf(listOf("8")), labels(c.mathResults))
    }

    @Test fun `una cuenta con letras deja la letra escrita`() {
        val page = PageState(row(0f, "2").let { two ->
            two + InkReplacement.place(k, Bounds(two.bounds().right + 6f, 10f, two.bounds().right + 26f, 40f), ids)
        }.let { it + row(0f, "Más", "3").map { s -> s.transformed(Affine.translate(it.bounds().right + 12f, 0f)) } }
            .let { it + InkReplacement.place(k, Bounds(it.bounds().right + 6f, 10f, it.bounds().right + 26f, 40f), ids) })
        val c = controller(page, withLetters)
        c.select(page.strokes.map { it.id })
        assertTrue(c.calculateSelection())
        assertEquals(listOf("5", "k"), calcLabels(page.strokes.filter { it.calc?.result == true }).flatten())
    }

    @Test fun `un par por un par se escribe elemento a elemento`() {
        // (3, 2) × (5, k) = (15, 2k)
        var x = 0f
        val ink = mutableListOf<InkStroke>()
        fun put(p: InkPattern, top: Float = 0f, bottom: Float = 40f, w: Float = 22f) {
            ink += InkReplacement.place(p, Bounds(x, top, x + w, bottom), ids).also { x = it.bounds().right + 10f }
        }
        val open = signs["Abre paréntesis"] ?: patterns.first { it.name == "Abre paréntesis" }
        val close = signs["Cierra paréntesis"] ?: patterns.first { it.name == "Cierra paréntesis" }
        put(open, -6f, 46f, 12f); put(signs["3"] ?: user.getValue("3")); put(comma, 32f, 46f, 6f); put(signs["2"] ?: user.getValue("2")); put(close, -6f, 46f, 12f)
        put(signs.getValue("Por"), 12f, 28f, 16f)
        put(open, -6f, 46f, 12f); put(signs["5"] ?: user.getValue("5")); put(comma, 32f, 46f, 6f); put(k, 10f, 40f, 18f); put(close, -6f, 46f, 12f)
        val page = PageState(ink)
        val c = controller(page, withLetters)
        c.select(page.strokes.map { it.id })
        assertTrue(c.calculationProblem(), c.calculateSelection())
        assertEquals(
            listOf("Abre paréntesis", "1", "5", "Coma", "2", "k", "Cierra paréntesis"),
            calcLabels(page.strokes.filter { it.calc?.result == true }).flatten(),
        )
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
