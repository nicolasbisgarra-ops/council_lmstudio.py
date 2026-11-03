package io.trazo.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    @Test fun anthropicCatalogPreservesPublishedEffortLevelsWithoutInventingAny() {
        val fake = Fake(HttpResponse(200, """{"data":[
            {"id":"claude-a","display_name":"Claude A","supported_reasoning_levels":[
              {"effort":"low","description":"Lower latency"},
              {"effort":"high","description":"More thorough"}],"default_reasoning_level":"high"},
            {"id":"claude-b","display_name":"Claude B"}]}"""))
        val models = AiClient(fake).listModels(profile(AiProvider.ANTHROPIC), "k")
        val a = models.single { it.id == "claude-a" }
        assertEquals(listOf(AiReasoningEffortOption("low", "Lower latency"), AiReasoningEffortOption("high", "More thorough")), a.reasoningEfforts)
        assertEquals("high", a.defaultReasoningEffort)
        assertTrue(a.reasoningEffortManualSupported)
        assertTrue(models.single { it.id == "claude-b" }.reasoningEfforts.isEmpty())
    }

    @Test fun geminiKeepsOnlyModelsThatGenerateContent() {
        val fake = Fake(HttpResponse(200, """{"models":[
            {"name":"models/gemini-x","displayName":"Gemini X","supportedGenerationMethods":["generateContent"]},
            {"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""))
        val models = AiClient(fake).listModels(profile(AiProvider.GEMINI), "k")
        assertEquals(listOf(AiModelInfo("gemini-x", "Gemini X", reasoningEffortCatalogKnown = true)), models)
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
        assertFalse(body.containsKey("output_config"))
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

    @Test fun openAiReportsOnlyProviderUsageAndSeparatesReportedImageTokens() {
        val fake = Fake(HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}],"usage":{
            "prompt_tokens":31,"completion_tokens":9,
            "prompt_tokens_details":{"cached_tokens":7,"image_tokens":18},
            "completion_tokens_details":{"reasoning_tokens":3}}}"""))
        var usage: AiTokenUsage? = null
        AiClient(fake).complete(profile(AiProvider.OPENAI), "k", "system", "Lee esta imagen", byteArrayOf(1), onUsage = { usage = it })

        assertEquals(AiTokenUsage(31, 9, 3, 7, 18), usage)
        val body = Json.parseToJsonElement(fake.sent.single().body!!).jsonObject
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("Lee esta imagen", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))

        val noUsage = Fake(HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}"""))
        var unknown: AiTokenUsage? = null
        AiClient(noUsage).complete(profile(AiProvider.OPENAI), "k", "", "question", byteArrayOf(1), onUsage = { unknown = it })
        assertNull("el cliente no estima los tokens de imagen ausentes", unknown)
    }

    @Test fun anthropicAndGeminiUsageFollowTheirPublishedFields() {
        val anthropic = Fake(HttpResponse(200, """{"content":[{"type":"text","text":"ok"}],"usage":{
            "input_tokens":7,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":4,
            "output_tokens_details":{"thinking_tokens":1}}}"""))
        var anthropicUsage: AiTokenUsage? = null
        AiClient(anthropic).complete(profile(AiProvider.ANTHROPIC), "k", "", "question", null, onUsage = { anthropicUsage = it })
        assertEquals(AiTokenUsage(inputTokens = 12, outputTokens = 4, reasoningTokens = 1, cachedInputTokens = 5), anthropicUsage)

        val gemini = Fake(HttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],"usageMetadata":{
            "promptTokenCount":20,"candidatesTokenCount":8,"thoughtsTokenCount":5,"cachedContentTokenCount":4,
            "promptTokensDetails":[{"modality":"TEXT","tokenCount":11},{"modality":"IMAGE","tokenCount":9}]}}"""))
        var geminiUsage: AiTokenUsage? = null
        AiClient(gemini).complete(profile(AiProvider.GEMINI), "k", "", "question", byteArrayOf(1), onUsage = { geminiUsage = it })
        assertEquals(AiTokenUsage(inputTokens = 20, outputTokens = 13, reasoningTokens = 5, cachedInputTokens = 4, imageInputTokens = 9), geminiUsage)
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
        assertNull(profile(AiProvider.ON_DEVICE).problem)
        assertNotNull(profile(AiProvider.ON_DEVICE, "").problem)
        assertNull(profile(AiProvider.GEMINI).problem)
        assertTrue(profile(AiProvider.OPENAI).copy(baseUrl = "api.openai.com").problem!!.contains("http"))
    }

    @Test fun profilesRoundTripAndToleratesGarbage() {
        val list = listOf(profile(AiProvider.ANTHROPIC), profile(AiProvider.GEMINI).copy(id = "2", rigor = Rigor.STRICT, temperature = 0.5))
        assertEquals(list, AiProfile.decodeList(AiProfile.encodeList(list)))
        assertEquals(emptyList<AiProfile>(), AiProfile.decodeList("{no"))
        assertEquals(emptyList<AiProfile>(), AiProfile.decodeList(null))
    }

    @Test fun oldProfileJsonDefaultsToAutomaticAndNewEffortStateRoundTrips() {
        val oldJson = """[{"id":"old","name":"Old","provider":"openai","model":"gpt-model"}]"""
        val old = AiProfile.decodeList(oldJson).single()
        assertEquals(AiReasoningEffortMode.AUTO, old.reasoningEffortMode)
        assertNull(old.reasoningEffort)
        assertTrue(old.availableReasoningEfforts.isEmpty())

        val configured = old.copy(
            reasoningEffortMode = AiReasoningEffortMode.MANUAL,
            reasoningEffort = "high",
            availableReasoningEfforts = listOf(AiReasoningEffortOption("high", "More reasoning")),
            defaultReasoningEffort = "medium",
            reasoningEffortCatalogKnown = true,
            reasoningEffortManualSupported = true,
        )
        assertEquals(configured, AiProfile.decodeList(AiProfile.encodeList(listOf(configured))).single())
    }

    @Test fun codexCatalogReadsObjectLevelsAndReasoningLevelAliases() {
        val fake = Fake(HttpResponse(200, """{"models":[
            {"slug":"codex-a","display_name":"Codex A","supported_reasoning_levels":[
              {"effort":"low","description":"Short reasoning"},
              {"effort":"medium","description":"Balanced"}],"default_reasoning_level":"medium"},
            {"slug":"codex-b","supported_reasoning_efforts":["minimal","high"],"default_reasoning_effort":"minimal"}]}"""))
        val models = AiClient(fake).listModels(profile(AiProvider.CHATGPT), OAuthTokens("token").encode())
        assertEquals(listOf("codex-a", "codex-b"), models.map { it.id })
        assertEquals(
            listOf(AiReasoningEffortOption("low", "Short reasoning"), AiReasoningEffortOption("medium", "Balanced")),
            models[0].reasoningEfforts,
        )
        assertEquals("medium", models[0].defaultReasoningEffort)
        assertTrue(models[0].reasoningEffortManualSupported)
        assertEquals(listOf(AiReasoningEffortOption("minimal"), AiReasoningEffortOption("high")), models[1].reasoningEfforts)
        assertEquals("minimal", models[1].defaultReasoningEffort)
    }

    @Test fun openRouterCatalogReadsNestedLevelsAndParameterCapability() {
        val fake = Fake(HttpResponse(200, """{"data":[
            {"id":"router/a","reasoning":{"supported_efforts":["low","high"],"default_effort":"high"}},
            {"id":"router/b","supported_parameters":["reasoning_effort"]}]}"""))
        val models = AiClient(fake).listModels(profile(AiProvider.OPENAI_COMPATIBLE), null)
        val a = models.single { it.id == "router/a" }
        assertEquals(listOf(AiReasoningEffortOption("low"), AiReasoningEffortOption("high")), a.reasoningEfforts)
        assertEquals("high", a.defaultReasoningEffort)
        val b = models.single { it.id == "router/b" }
        assertTrue(b.reasoningEfforts.isEmpty())
        assertTrue(b.reasoningEffortManualSupported)
    }

    @Test fun manualEffortIsMappedPerProviderAndAutomaticOmitsIt() {
        val anthropicBase = profile(AiProvider.ANTHROPIC)
        val autoAnthropic = AiClient(Fake()).anthropicBody(anthropicBase, "system", "text", null, 512)
        assertFalse(autoAnthropic.containsKey("output_config"))
        val manualAnthropic = AiClient(Fake()).anthropicBody(
            anthropicBase.copy(reasoningEffortMode = AiReasoningEffortMode.MANUAL, reasoningEffort = "xhigh"),
            "system", "text", null, 512,
        )
        assertEquals("xhigh", manualAnthropic["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val base = profile(AiProvider.OPENAI)
        val autoOpenAi = AiClient(Fake()).openAiBody(base, "system", "text", null, 512)
        assertFalse(autoOpenAi.containsKey("reasoning_effort"))
        val manualOpenAi = AiClient(Fake()).openAiBody(
            base.copy(reasoningEffortMode = AiReasoningEffortMode.MANUAL, reasoningEffort = "xhigh"),
            "system", "text", null, 512,
        )
        assertEquals("xhigh", manualOpenAi["reasoning_effort"]!!.jsonPrimitive.content)

        val chatBase = profile(AiProvider.CHATGPT)
        assertFalse(AiClient(Fake()).chatGptBody(chatBase, "system", "text", null).containsKey("reasoning"))
        val manualChat = AiClient(Fake()).chatGptBody(
            chatBase.copy(reasoningEffortMode = AiReasoningEffortMode.MANUAL, reasoningEffort = "high"),
            "system", "text", null,
        )
        assertEquals("high", manualChat["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val geminiBase = profile(AiProvider.GEMINI)
        val manualGemini = AiClient(Fake()).geminiBody(
            geminiBase.copy(reasoningEffortMode = AiReasoningEffortMode.MANUAL, reasoningEffort = "HIGH"),
            "system", "text", null, 512,
        )
        assertEquals(
            "HIGH",
            manualGemini["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
    }

    @Test fun manualEffortMustBeKnownAndCurrentForTheProviderAndModel() {
        val oldProfile = profile(AiProvider.OPENAI_COMPATIBLE).copy(
            reasoningEffortMode = AiReasoningEffortMode.MANUAL,
            reasoningEffort = "high",
        )
        assertTrue(oldProfile.problem!!.contains("no informó"))

        val stale = oldProfile.copy(
            reasoningEffortManualSupported = true,
            availableReasoningEfforts = listOf(AiReasoningEffortOption("low")),
        )
        assertTrue(stale.problem!!.contains("ya no aparece"))

        val unsupported = profile(AiProvider.ANTHROPIC).copy(
            reasoningEffortMode = AiReasoningEffortMode.MANUAL,
            reasoningEffort = "high",
            reasoningEffortManualSupported = true,
        )
        assertNull(unsupported.problem)
        val onDevice = profile(AiProvider.ON_DEVICE).copy(
            reasoningEffortMode = AiReasoningEffortMode.MANUAL,
            reasoningEffort = "high",
        )
        assertFalse(onDevice.canSelectReasoningEffort)
    }

    @Test fun visionProbeIsAPng() {
        val png = AiClient.VISION_PROBE_PNG
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png, 1, 3))
    }
}
