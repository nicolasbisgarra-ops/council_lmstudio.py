package io.trazo.core.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebStateTest {
    private val review = SharedReview(
        open = true,
        title = "Hoja <1>",
        pageKey = "p1:7",
        messages = listOf(
            SharedMessage(fromStudent = true, text = "¿Está bien?\n<script>alert('x')</script>", image = "foto.jpg"),
            SharedMessage(fromStudent = false, text = "Revisá **el signo** de \$-x\$", line = 2, correct = false, verdict = "Falta un \$-\$"),
            SharedMessage(fromStudent = false, text = "falló", error = true),
        ),
        notes = listOf(SharedNote(3, "signo", "<b>mal</b>", "detalle 'x'", "poné \$+\$")),
    )
    private val builder = WebStateBuilder()

    @Test fun versionFollowsWhatIsShown() {
        val v = builder.version(review)
        assertEquals(v, builder.version(review.copy()))
        assertEquals(24, v.length)
        val changes = listOf(
            review.copy(title = "otra"), review.copy(pageKey = "p1:8"), review.copy(busy = true), review.copy(streaming = "Pen"),
            review.copy(streaming = ""), review.copy(hint = "x"), review.copy(open = false), review.copy(notes = emptyList()),
            review.copy(messages = review.messages.dropLast(1)),
            review.copy(messages = review.messages.map { if (it.line == 2) it.copy(verdict = "otro") else it }),
            review.copy(messages = review.messages.map { if (it.line == 2) it.copy(correct = true) else it }),
            review.copy(messages = review.messages.map { it.copy(image = null) }),
            review.copy(quick = listOf("¿Por qué?")),
        )
        assertEquals(changes.size + 1, (changes.map { builder.version(it) } + v).toSet().size)
        // Los campos no se pueden confundir entre sí.
        assertNotEquals(builder.version(review.copy(title = "ab", hint = "c")), builder.version(review.copy(title = "a", hint = "bc")))
    }

    @Test fun stateIsSafeHtml() {
        val s = builder.state(review)
        assertEquals(builder.version(review), s.v)
        assertTrue(s.open)
        assertEquals("Hoja <1>", s.title)
        assertEquals(16, s.page!!.length)
        assertEquals(3, s.messages.size)
        val (me, tutor, err) = s.messages
        assertTrue(me.me)
        assertEquals("<p>¿Está bien?<br>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</p>", me.html)
        assertEquals("foto.jpg", me.image)
        assertFalse(tutor.me)
        assertEquals("<p>Revisá <strong>el signo</strong> de <span class=\"tex\">-x</span></p>", tutor.html)
        assertEquals(2, tutor.line)
        assertEquals(false, tutor.correct)
        assertEquals("Falta un <span class=\"tex\">-</span>", tutor.verdict)
        assertTrue(err.error)
        assertNull(err.verdict)
        assertEquals(listOf(WebNote(3, "signo", "&lt;b&gt;mal&lt;/b&gt;", "detalle &#39;x&#39;", "poné <span class=\"tex\">+</span>")), s.notes)
        // Mismo mensaje, mismo id; si cambia el texto o el lugar, otro.
        assertEquals(s.messages.map { it.id }, builder.state(review.copy(busy = true)).messages.map { it.id })
        val edited = builder.state(review.copy(messages = review.messages.map { if (it.error) it.copy(text = "otra") else it }))
        assertEquals(s.messages[1].id, edited.messages[1].id)
        assertNotEquals(s.messages[2].id, edited.messages[2].id)
        assertTrue(s.messages.all { it.id.startsWith("${s.messages.indexOf(it)}-") })
        assertEquals(listOf("<b>¿Por qué?</b>"), builder.state(review.copy(quick = listOf("<b>¿Por qué?</b>"))).quick)
    }

    @Test fun streamingAndClosed() {
        val busy = builder.state(review.copy(busy = true, streaming = "Vamos **bien**"))
        assertTrue(busy.busy)
        assertEquals("<p>Vamos <strong>bien</strong></p>", busy.streaming)
        assertNull(builder.state(review.copy(busy = true, streaming = "")).streaming)
        val closed = builder.state(SharedReview(open = false, hint = "Abrí una hoja", messages = review.messages))
        assertFalse(closed.open)
        assertEquals("Abrí una hoja", closed.hint)
        assertTrue(closed.messages.isEmpty())
        assertNull(closed.page)
    }

    @Test fun jsonRoundTrip() {
        val s = builder.state(review)
        val json = builder.json(s)
        assertFalse(json.contains("<script"))
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(s.v, o["v"]!!.jsonPrimitive.content)
        assertEquals(3, o["messages"]!!.jsonArray.size)
        assertEquals(s, WebStateBuilder.JSON.decodeFromString(WebState.serializer(), json))
        // Un chat vacío viaja como lista vacía, no como campo ausente (el navegador recorre las listas).
        val empty = Json.parseToJsonElement(builder.json(builder.state(SharedReview(open = true, title = "t")))).jsonObject
        assertEquals(0, empty["messages"]!!.jsonArray.size)
        assertEquals(0, empty["notes"]!!.jsonArray.size)
        assertEquals(0, empty["quick"]!!.jsonArray.size)
        assertEquals("false", empty["busy"]!!.jsonPrimitive.content)
        assertFalse(empty.containsKey("streaming"))
        assertFalse(empty.containsKey("page"))
        val same = Json.parseToJsonElement(WebStateBuilder.same("abc")).jsonObject
        assertEquals("abc", same["v"]!!.jsonPrimitive.content)
        assertEquals("true", same["same"]!!.jsonPrimitive.content)
    }
}
