package io.trazo.core

import kotlin.math.roundToInt

import kotlinx.serialization.json.add

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

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

    @Test fun `la exportacion compacta conserva el nombre original sin convertir la expresion`() {
        val number = user.getValue("2")
        val three = user.getValue("3")
        val rawPlus = signs.getValue("Más").copy(id = "raw-plus", name = "Mas", unknown = "+")
        val ink = InkReplacement.place(number, Bounds(0f, 0f, 25f, 40f), ids) +
            InkReplacement.place(rawPlus, Bounds(40f, 0f, 65f, 40f), ids) +
            InkReplacement.place(three, Bounds(80f, 0f, 105f, 40f), ids)
        val raw = MathNotes.reviewLabels(ink, patterns + rawPlus).single()
        val payload = Json.parseToJsonElement(raw.text).jsonObject
        val labels = payload.getValue("labels").jsonArray.map { it.jsonArray[0].jsonPrimitive.content }

        assertEquals(listOf("2", "Mas", "3"), labels)
        val bounds = ink.bounds()
        assertEquals(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { it.roundToInt() }, payload.getValue("box").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(0, payload.getValue("unlabeled").jsonPrimitive.int)
        assertEquals(setOf("box", "labels", "unlabeled"), payload.keys)
        assertTrue(payload.getValue("labels").jsonArray.all { token -> token.jsonArray.size == 5 })
        assertTrue("el texto local conserva su conversion matematica", MathNotes.reviewLines(ink, patterns + rawPlus).single().text.contains("2+3"))
        assertEquals(ink.map { it.id }, raw.strokeIds)
        assertEquals(ink.bounds(), raw.bounds)
        assertTrue("los ids permanecen en los metadatos internos", ink.all { it.id in raw.strokeIds })
        assertTrue("la huella usa la tinta original", raw.fingerprint == AiNotes.fingerprint(raw.strokeIds, ink))
    }

    @Test fun `etiquetas crudas conservan patrones sin significado matematico y trazos desconocidos`() {
        val disabled = signs.getValue("Por").copy(id = "raw-disabled", name = "Mi por", unknown = "")
        val recognized = InkReplacement.place(disabled, Bounds(0f, 100f, 25f, 140f), ids)
        val handwriting = hand("raw-hand", 45f, 100f)
        val generated = listOf(hand("raw-result", 90f, 100f).copy(calc = CalcTag("generated", result = true)))
        val marker = InkStroke("raw-highlight", listOf(InkPoint(130f, 100f), InkPoint(180f, 100f)), brush = Brush.HIGHLIGHTER)
        val all = recognized + handwriting + generated + marker

        val line = MathNotes.reviewLabels(all, patterns + disabled, (recognized + handwriting + generated + marker).map { it.id }.toSet()).single()
        val payload = Json.parseToJsonElement(line.text).jsonObject
        val labels = payload.getValue("labels").jsonArray

        assertEquals(listOf("Mi por"), labels.map { it.jsonArray[0].jsonPrimitive.content })
        assertEquals(1, payload.getValue("unlabeled").jsonPrimitive.int)
        assertEquals(recognized.map { it.id } + "raw-hand", line.strokeIds)
        assertTrue("no se envían UUIDs", (recognized.map { it.id } + "raw-hand").none { it in line.text })
        assertTrue("la caja interna conserva coordenadas exactas", line.bounds == (recognized + handwriting).bounds())
        assertEquals("?", MathNotes.reviewLines(recognized + handwriting, patterns + disabled).single().text)
    }

    @Test fun `el formato compacto reduce 1000 glifos y conserva metadatos internos`() {
        val number = user.getValue("2")
        val known = (0 until 500).map { i ->
            val x = 0.25f + i * 30f
            InkStroke(
                id = UUID.nameUUIDFromBytes("known-$i".toByteArray()).toString(),
                points = listOf(InkPoint(x, 100.25f), InkPoint(x + 24.5f, 140.5f)),
                patternId = number.id,
            )
        }
        val unknown = (0 until 500).map { i ->
            val x = 15_000.25f + i * 30f
            InkStroke(
                id = UUID.nameUUIDFromBytes("unknown-$i".toByteArray()).toString(),
                points = listOf(InkPoint(x, 100.25f), InkPoint(x + 24.5f, 140.5f)),
            )
        }
        val ink = known + unknown
        val line = MathNotes.reviewLabels(ink, patterns).single()
        val compact = Json.parseToJsonElement(line.text).jsonObject
        val byId = (patterns + number).associateBy { it.id }
        val previousFormat = buildJsonArray {
            ink.forEach { stroke ->
                val bounds = stroke.bounds
                val label = stroke.patternId?.let { byId[it]?.name }
                add(buildJsonObject {
                    put("label", label?.let(::JsonPrimitive) ?: JsonNull)
                    put("patternId", stroke.patternId?.let(::JsonPrimitive) ?: JsonNull)
                    put("bounds", buildJsonObject {
                        put("left", bounds.left); put("top", bounds.top)
                        put("right", bounds.right); put("bottom", bounds.bottom)
                    })
                    put("strokeIds", buildJsonArray { add(stroke.id) })
                })
            }
        }.toString()

        assertTrue("old=${previousFormat.length}, new=${line.text.length}", line.text.length < previousFormat.length)
        assertEquals(500, compact.getValue("labels").jsonArray.size)
        assertEquals(500, compact.getValue("unlabeled").jsonPrimitive.int)
        assertEquals(listOf(0, 100, 29_995, 141), compact.getValue("box").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(1, compact.getValue("labels").jsonArray.first().jsonArray[0].jsonPrimitive.content.length)
        assertEquals(ink.map { it.id }, line.strokeIds)
        assertEquals(Bounds(0.25f, 100.25f, 29_994.75f, 140.5f), line.bounds)
        assertEquals(AiNotes.fingerprint(line.strokeIds, ink), line.fingerprint)
        assertTrue("UUIDs no viajan en el JSON", ink.none { it.id in line.text })
        assertTrue(AiNotes.unchanged(line.strokeIds, line.bounds, ink, line.fingerprint))
        val edited = ink.toMutableList().also { it[250] = it[250].transformed(Affine.translate(1f, 0f)) }
        assertTrue("la huella invalida un cambio interno aunque no cambie la caja", line.bounds == edited.bounds())
        assertFalse(AiNotes.unchanged(line.strokeIds, line.bounds, edited, line.fingerprint))
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

    @Test fun `el motor sigue la resolucion paso a paso`() {
        val c = AiNotes.check(listOf(
            line(1, "2x+3=7"), line(2, "2x=4"), line(3, "x=3"),
            line(4, "x^2-5x+6=0"), line(5, "(x-2)(x-3)=0"),
            line(6, "2x+3x"), line(7, "=5x"), line(8, "=6x"),
        ))
        assertEquals(AiNotes.Verdict.UNKNOWN, c[0].step)
        assertEquals("2x+3=7 → 2x=4 conserva la solución", AiNotes.Verdict.HOLDS, c[1].step)
        assertEquals("2x=4 → x=3 la cambia", AiNotes.Verdict.FAILS, c[2].step)
        assertTrue(c[2].stepDetail, "x = 2" in c[2].stepDetail && "x = 3" in c[2].stepDetail)
        assertEquals("factorizar conserva las dos soluciones", AiNotes.Verdict.HOLDS, c[4].step)
        assertEquals(AiNotes.Verdict.HOLDS, c[6].step)
        assertEquals(AiNotes.Verdict.FAILS, c[7].step)
    }

    @Test fun `una marca que el motor tambien encuentra queda comprobada y un paso malo sin marca recibe una del motor`() {
        val lines = listOf(line(1, "2x+3=7"), line(2, "2x=4"), line(3, "x=3"), line(4, "3x=6"), line(5, "x=1"))
        val ai = AiNotes.place(listOf(AiNotes.Mark(3, "CALCULATION", "dividiste mal"), AiNotes.Mark(2, "CALCULATION", "restaste mal")), lines, ids, "r2")
        val out = AiNotes.crossCheck(ai, lines, ids, "r2")
        assertTrue(out.single { it.line == 3 }.confirmed)
        assertTrue("el paso 1 → 2 está bien: la marca queda dudosa", out.single { it.line == 2 }.disputed)
        val engine = out.single { it.fromEngine }
        assertEquals(5, engine.line)
        assertTrue(engine.detail, "x = 2" in engine.detail && "x = 1" in engine.detail)
        assertFalse(AiNotes.correct(true, out))
        assertTrue("solo marcas dudosas: la hoja queda bien", AiNotes.correct(false, out.filter { it.disputed }))
        assertTrue(AiNotes.correct(true, emptyList()))
        assertFalse(AiNotes.correct(false, emptyList()))
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

    @Test fun `la lectura del modelo se pasa a la forma del motor`() {
        assertEquals("2*x-3=7", AiNotes.normalizeReading("2·x − 3 = 7"))
        assertEquals("x^2/4=1", AiNotes.normalizeReading("x² ÷ 4 = 1"))
        assertEquals(null, AiNotes.normalizeReading("   "))
    }
}
