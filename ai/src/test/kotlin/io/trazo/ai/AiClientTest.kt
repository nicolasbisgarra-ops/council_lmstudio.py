package io.trazo.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiClientTest {
    private class Fake(vararg replies: HttpResponse) : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        private val queue = ArrayDeque(replies.toList())
        override fun send(request: HttpRequest): HttpResponse { sent += request; return queue.removeFirst() }
    }

    private fun profile(p: AiProvider, model: String = "m") = AiProfile("id", "n", p, model = model)

    @Test fun anthropicListsModelsWithKeyHeaders() {
        val fake = Fake(HttpResponse(200, """{"data":[{"id":"b-model","display_name":"Beta"},{"id":"a-model","display_name":"Alfa"}]}"""))
        val models = AiClient(fake).listModels(profile(AiProvider.ANTHROPIC), " sk-1 ")
        assertEquals(listOf("a-model", "b-model"), models.map { it.id })
        val r = fake.sent.single()
        assertEquals("GET", r.method)
        assertTrue(r.url.startsWith("https://api.anthropic.com/v1/models"))
        assertEquals("sk-1", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertFalse("la clave no va en la URL", "sk-1" in r.url)
    }

    @Test fun geminiKeepsOnlyModelsThatGenerateContent() {
        val fake = Fake(HttpResponse(200, """{"models":[
            {"name":"models/gemini-x","displayName":"Gemini X","supportedGenerationMethods":["generateContent"]},
            {"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""))
        val models = AiClient(fake).listModels(profile(AiProvider.GEMINI), "k")
        assertEquals(listOf(AiModelInfo("gemini-x", "Gemini X")), models)
        assertEquals("k", fake.sent.single().headers["x-goog-api-key"])
    }

    @Test fun compatibleServerWithoutKeyAndWithoutPathGetsV1() {
        val fake = Fake(HttpResponse(200, """{"data":[{"id":"qwen2.5-vl"}]}"""))
        val p = profile(AiProvider.OPENAI_COMPATIBLE).copy(baseUrl = "http://192.168.1.5:1234/")
        AiClient(fake).listModels(p, null)
        assertEquals("http://192.168.1.5:1234/v1/models", fake.sent.single().url)
        assertNull(fake.sent.single().headers["Authorization"])
    }

    @Test fun endpointKeepsAnExplicitPath() {
        val p = profile(AiProvider.OPENAI_COMPATIBLE).copy(baseUrl = "https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api/v1", p.endpoint)
    }

    @Test fun missingKeyIsReportedBeforeSending() {
        val fake = Fake()
        try { AiClient(fake).listModels(profile(AiProvider.ANTHROPIC), "  "); fail() } catch (e: AiException) {
            assertTrue(e.message!!.contains("clave"))
        }
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun httpErrorsAreExplainedInSpanishWithTheProviderMessage() {
        val fake = Fake(HttpResponse(401, """{"error":{"type":"auth","message":"invalid x-api-key"}}"""))
        try { AiClient(fake).listModels(profile(AiProvider.ANTHROPIC), "k"); fail() } catch (e: AiException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!, e.message!!.startsWith("La clave no es válida"))
            assertTrue(e.message!!.contains("invalid x-api-key"))
        }
    }

    @Test fun anthropicMessageCarriesImageAndSystem() {
        val fake = Fake(HttpResponse(200, """{"content":[{"type":"text","text":"3"}]}"""))
        val report = AiClient(fake, clock = sequenceOf(1000L, 1450L).iterator()::next).testConnection(profile(AiProvider.ANTHROPIC, "claude-x"), "k")
        assertTrue(report.readsImages)
        assertEquals(450L, report.latencyMs)
        val r = fake.sent.single()
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        val body = Json.parseToJsonElement(r.body!!).jsonObject
        assertEquals("claude-x", body["model"]!!.jsonPrimitive.content)
        assertNull("sin temperatura por omisión", body["temperature"])
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[0].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
    }

    @Test fun openAiUsesNewTokenLimitAndCompatibleTheOldOne() {
        val reply = """{"choices":[{"message":{"content":"El resultado es 3."}}]}"""
        val fake = Fake(HttpResponse(200, reply), HttpResponse(200, reply))
        val client = AiClient(fake)
        client.complete(profile(AiProvider.OPENAI).copy(temperature = 0.2), "k", "s", "t", byteArrayOf(1))
        client.complete(profile(AiProvider.OPENAI_COMPATIBLE), null, "s", "t", byteArrayOf(1))
        val (openai, compat) = fake.sent.map { Json.parseToJsonElement(it.body!!).jsonObject }
        assertTrue(openai.containsKey("max_completion_tokens"))
        assertEquals(0.2, openai["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(compat.containsKey("max_tokens"))
        assertEquals("Bearer k", fake.sent[0].headers["Authorization"])
        val image = openai["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertTrue(image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
    }

    @Test fun geminiPostsToTheModelAndJoinsParts() {
        val fake = Fake(HttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"1+2 = "},{"text":"3"}]}}]}"""))
        val report = AiClient(fake).testConnection(profile(AiProvider.GEMINI, "models/gemini-x"), "k")
        assertEquals("1+2 = 3", report.reply)
        assertTrue(report.readsImages)
        assertTrue(fake.sent.single().url.endsWith("/models/gemini-x:generateContent"))
    }

    @Test fun aModelThatCannotSeeIsReported() {
        val fake = Fake(HttpResponse(200, """{"choices":[{"message":{"content":"No puedo ver imágenes; 13"}}]}"""))
        assertFalse(AiClient(fake).testConnection(profile(AiProvider.OPENAI_COMPATIBLE), null).readsImages)
    }

    @Test fun profileProblems() {
        assertEquals("Falta elegir el modelo.", profile(AiProvider.OPENAI, "").problem)
        assertTrue(profile(AiProvider.ON_DEVICE).problem!!.contains("próxima"))
        assertNull(profile(AiProvider.GEMINI).problem)
        assertTrue(profile(AiProvider.OPENAI).copy(baseUrl = "api.openai.com").problem!!.contains("http"))
    }

    @Test fun profilesRoundTripAndToleratesGarbage() {
        val list = listOf(profile(AiProvider.ANTHROPIC), profile(AiProvider.GEMINI).copy(id = "2", rigor = Rigor.STRICT, temperature = 0.5))
        assertEquals(list, AiProfile.decodeList(AiProfile.encodeList(list)))
        assertEquals(emptyList<AiProfile>(), AiProfile.decodeList("{no"))
        assertEquals(emptyList<AiProfile>(), AiProfile.decodeList(null))
    }

    @Test fun visionProbeIsAPng() {
        val png = AiClient.VISION_PROBE_PNG
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png, 1, 3))
    }
}
