package io.trazo.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun anthropicAndOpenAiUsageSnapshotsAreMergedRatherThanAdded() {
        val anthropic = Fake(HttpResponse(200, sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1,"cache_creation_input_tokens":3,"cache_read_input_tokens":2}}}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}""",
            """{"type":"message_delta","usage":{"input_tokens":10,"output_tokens":6,"cache_creation_input_tokens":3,"cache_read_input_tokens":2}}""",
        )))
        val anthropicSnapshots = mutableListOf<AiTokenUsage>()
        AiClient(anthropic).stream(profile(AiProvider.ANTHROPIC), "k", "s", "t", null,
            onUsage = { anthropicSnapshots += it }) {}
        assertEquals(AiTokenUsage(inputTokens = 15, outputTokens = 6, cachedInputTokens = 5), anthropicSnapshots.last())
        assertTrue(anthropicSnapshots.all { it.outputTokens != 7 })

        val openAi = Fake(HttpResponse(200, sse(
            """{"choices":[{"delta":{"content":"ok"}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":13,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":5,"image_tokens":8},"completion_tokens_details":{"reasoning_tokens":2}}}""",
            """{"choices":[],"usage":{"prompt_tokens":13,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":5,"image_tokens":8},"completion_tokens_details":{"reasoning_tokens":2}}}""",
            "[DONE]",
        )))
        var openAiUsage: AiTokenUsage? = null
        assertEquals("ok", AiClient(openAi).stream(profile(AiProvider.OPENAI), "k", "s", "t", byteArrayOf(1),
            onUsage = { openAiUsage = it }) {})
        assertEquals(AiTokenUsage(inputTokens = 13, outputTokens = 4, reasoningTokens = 2, cachedInputTokens = 5, imageInputTokens = 8), openAiUsage)
        val requestBody = kotlinx.serialization.json.Json.parseToJsonElement(openAi.sent.single().body!!).jsonObject
        assertEquals("true", requestBody["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content)
    }

    @Test fun includeUsageIsOnlySentToTheFirstPartyOpenAiAdapter() {
        for (provider in listOf(AiProvider.OPENAI_COMPATIBLE, AiProvider.CHATGPT)) {
            val response = if (provider == AiProvider.CHATGPT) sse(
                """{"type":"response.output_text.delta","delta":"ok"}""",
                """{"type":"response.completed","response":{"usage":{"input_tokens":2,"output_tokens":1,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}},"output":[]}}""",
            ) else sse("""{"choices":[{"delta":{"content":"ok"}}]}""")
            val fake = Fake(HttpResponse(200, response))
            val key = if (provider == AiProvider.CHATGPT) OAuthTokens("token").encode() else null
            var usage: AiTokenUsage? = null
            AiClient(fake).stream(profile(provider), key, "s", "t", null, onUsage = { usage = it }) {}
            val body = kotlinx.serialization.json.Json.parseToJsonElement(fake.sent.single().body!!).jsonObject
            assertFalse("$provider no acepta include_usage de OpenAI", body.containsKey("stream_options"))
            if (provider == AiProvider.CHATGPT) {
                assertEquals(AiTokenUsage(inputTokens = 2, outputTokens = 1, reasoningTokens = 0, cachedInputTokens = 0), usage)
            } else {
                assertNull(usage)
            }
        }
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
            HttpResponse(200, sse(
                """{"choices":[{"delta":{"content":"no sé hacer JSON"}}]}""",
                """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":2,"image_tokens":7},"completion_tokens_details":{"reasoning_tokens":3}}}""",
            )),
            HttpResponse(200, """{"choices":[{"message":{"content":"{\"correcto\": true, \"puntaje\": 10, \"marcas\": []}"}}],"usage":{"prompt_tokens":4,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":1},"completion_tokens_details":{"reasoning_tokens":1}}}"""),
        )
        val savedBeforeMandatoryGuide = profile(AiProvider.OPENAI).copy(instructions = "Perfil guardado de una versión anterior.")
        val r = AiReviewer(AiClient(fake)).review(savedBeforeMandatoryGuide, "k", listOf("1=1"), null)
        assertTrue(r.correct)
        assertEquals(10.0, r.score!!, 1e-9)
        assertEquals(AiTokenUsage(inputTokens = 16, outputTokens = 7, reasoningTokens = 4, cachedInputTokens = 3), r.usage)
        assertEquals(2, fake.sent.size)
        assertTrue(fake.sent[0].body!!.contains("Guía obligatoria para revisar una hoja manuscrita"))
        assertTrue(fake.sent[0].body!!.contains("Perfil guardado de una versión anterior."))
        assertTrue(fake.sent[0].body!!.contains("Etiquetas crudas reconocidas originalmente por Trazo"))
        assertTrue(fake.sent[1].body!!.contains("exclusivamente para reparar el formato"))
        assertTrue(fake.sent[1].body!!.contains("no vuelva a revisar ni resolver el ejercicio"))
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

    @Test fun discussionSendsRawLineLabelsAndTheMandatoryGuide() {
        val fake = Fake(HttpResponse(200, """{"choices":[{"message":{"content":"{\"mantener\":true,\"respuesta\":\"Sigo revisando\"}"}}]}"""))
        val reviewer = AiReviewer(AiClient(fake))
        val rawLine = """{"labels":[{"text":"x","box":[12,4,28,19]}]}"""

        reviewer.discuss(
            profile(AiProvider.OPENAI).copy(instructions = "Instrucción guardada antigua."),
            "k",
            AiMark(1, MarkKind.CALCULATION, "Revise el signo"),
            rawLine,
            "El signo se ve positivo en mi hoja.",
            null,
        )

        val body = fake.sent.single().body!!
        assertTrue(body.contains("Guía obligatoria para revisar una hoja manuscrita"))
        assertTrue(body.contains("Etiquetas crudas originales del renglón 1"))
        assertTrue(body.contains("box"))
        assertTrue(body.contains("mantener"))
    }
}
