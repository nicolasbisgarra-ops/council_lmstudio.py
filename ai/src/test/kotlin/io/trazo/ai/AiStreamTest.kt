package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Etapa 2: respuesta en streaming, marcas que llegan de a una, rigor y «Discutir». */
class AiStreamTest {
    private class Fake(vararg replies: HttpResponse) : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        private val queue = ArrayDeque(replies.toList())
        override fun send(request: HttpRequest): HttpResponse { sent += request; return queue.removeFirst() }
    }

    private fun profile(p: AiProvider) = AiProfile("id", "n", p, model = "m")

    private fun sse(vararg events: String) = events.joinToString("\n\n") { "data: $it" } + "\n\n"

    @Test fun anthropicJoinsTextDeltas() {
        val fake = Fake(HttpResponse(200, sse(
            """{"type":"message_start"}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hola "}}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"mundo"}}""",
        )))
        val parts = mutableListOf<String>()
        val text = AiClient(fake).stream(profile(AiProvider.ANTHROPIC), "k", "s", "t", null) { parts += it }
        assertEquals("Hola mundo", text)
        assertEquals(listOf("Hola ", "mundo"), parts)
        assertTrue(fake.sent.single().body!!.contains("\"stream\":true"))
    }

    @Test fun openAiAndGeminiStreams() {
        val o = Fake(HttpResponse(200, sse("""{"choices":[{"delta":{"content":"a"}}]}""", """{"choices":[{"delta":{}}]}""", """{"choices":[{"delta":{"content":"b"}}]}""", "[DONE]")))
        assertEquals("ab", AiClient(o).stream(profile(AiProvider.OPENAI), "k", "s", "t", null) {})
        val g = Fake(HttpResponse(200, sse("""{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""", """{"candidates":[{"content":{"parts":[{"text":"y"}]}}]}""")))
        assertEquals("xy", AiClient(g).stream(profile(AiProvider.GEMINI), "k", "s", "t", null) {})
        assertTrue(g.sent.single().url.endsWith(":streamGenerateContent?alt=sse"))
    }

    @Test fun aServerThatIgnoresStreamingIsReadWhole() {
        val fake = Fake(HttpResponse(200, """{"choices":[{"message":{"content":"todo junto"}}]}"""))
        val parts = mutableListOf<String>()
        assertEquals("todo junto", AiClient(fake).stream(profile(AiProvider.OPENAI_COMPATIBLE), null, "s", "t", null) { parts += it })
        assertEquals(listOf("todo junto"), parts)
    }

    @Test fun anErrorEventIsReported() {
        val fake = Fake(HttpResponse(200, sse("""{"type":"error","error":{"message":"overloaded"}}""")))
        try { AiClient(fake).stream(profile(AiProvider.ANTHROPIC), "k", "s", "t", null) {}; fail() } catch (e: AiException) {
            assertTrue(e.message!!, e.message!!.contains("overloaded"))
        }
    }

    @Test fun marksArriveOneByOneEvenWhenSplitAcrossChunks() {
        val got = mutableListOf<AiMark>()
        val s = StreamingMarks { got += it }
        val whole = """{"correcto": false, "marcas": [{"renglon": 2, "tipo": "cuenta", "breve": "da {8}, no \"9\""}, {"renglon": 4, "tipo": "paso", "breve": "falta"}], "puntaje": 6}"""
        whole.chunked(7).forEachIndexed { i, c ->
            s.feed(c)
            if (i * 7 < whole.indexOf("}, {")) assertTrue(got.isEmpty())
        }
        assertEquals(listOf(2, 4), got.map { it.line })
        assertEquals("da {8}, no \"9\"", got[0].short)
        assertEquals(MarkKind.MISSING_STEP, got[1].kind)
    }

    @Test fun notationIsDroppedUnlessStrict() {
        val reply = """{"correcto": false, "puntaje": 9, "marcas": [{"renglon": 1, "tipo": "notacion", "breve": "falta paréntesis"}]}"""
        fun run(rigor: Rigor): Pair<AiReview, List<AiMark>> {
            val fake = Fake(HttpResponse(200, sse("""{"choices":[{"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(reply)}}}]}""")))
            val streamed = mutableListOf<AiMark>()
            val r = AiReviewer(AiClient(fake)).review(profile(AiProvider.OPENAI).copy(rigor = rigor), "k", listOf("2+2=4"), null) { streamed += it }
            return r to streamed
        }
        val (normal, s1) = run(Rigor.NORMAL)
        assertTrue(normal.correct)
        assertTrue(normal.marks.isEmpty() && s1.isEmpty())
        val (strict, s2) = run(Rigor.STRICT)
        assertFalse(strict.correct)
        assertEquals(1, strict.marks.size)
        assertEquals(1, s2.size)
    }

    @Test fun brokenJsonIsRepairedOnce() {
        val fake = Fake(
            HttpResponse(200, sse("""{"choices":[{"delta":{"content":"no sé hacer JSON"}}]}""")),
            HttpResponse(200, """{"choices":[{"message":{"content":"{\"correcto\": true, \"puntaje\": 10, \"marcas\": []}"}}]}"""),
        )
        val r = AiReviewer(AiClient(fake)).review(profile(AiProvider.OPENAI), "k", listOf("1=1"), null)
        assertTrue(r.correct)
        assertEquals(10.0, r.score!!, 1e-9)
        assertEquals(2, fake.sent.size)
    }

    @Test fun discussionKeepsWithdrawsOrReplacesTheMark() {
        val r = AiReviewer(AiClient(Fake()))
        assertFalse(r.parseDiscussion("""{"mantener": false, "respuesta": "Tiene razón."}""").keep)
        val kept = r.parseDiscussion("```json\n{\"mantener\": true, \"respuesta\": \"Sigue mal.\", \"marca\": {\"renglon\": 3, \"tipo\": \"concepto\", \"breve\": \"otra\"}}\n```")
        assertTrue(kept.keep)
        assertEquals("otra", kept.mark!!.short)
        val free = r.parseDiscussion("Mantengo la marca porque 2·4 = 8.")
        assertTrue(free.keep)
        assertNull(free.mark)
        assertEquals("Mantengo la marca porque 2·4 = 8.", free.answer)
    }
}
