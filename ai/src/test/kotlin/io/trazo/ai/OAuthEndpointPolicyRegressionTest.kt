package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** QA51: la sesión OAuth solo viaja al servidor oficial; nunca se llega a la red con otra URL. */
class OAuthEndpointPolicyRegressionTest {
    private class Capture : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        override fun send(request: HttpRequest): HttpResponse {
            sent += request
            return HttpResponse(200, """{"models":[{"slug":"test","name":"models/test","supportedGenerationMethods":["generateContent"]}]}""")
        }
    }

    private val fakeSession = OAuthTokens(accessToken = "synthetic-canary-token").encode()

    private fun profile(provider: AiProvider, url: String) =
        AiProfile("qa51", "qa51", provider, baseUrl = url, model = "test", oauthClientId = "test-client")

    private fun assertRejectsBeforeNetwork(provider: AiProvider, url: String) {
        val fake = Capture()
        val p = profile(provider, url)
        try {
            AiClient(fake).listModels(p, fakeSession)
            fail("Debió rechazar $url")
        } catch (e: AiException) {
            assertTrue(e.message!!.startsWith("Por seguridad"))
            assertFalse("canary" in e.message!!)
        }
        assertTrue("No debió salir ningún pedido a $url", fake.sent.isEmpty())
        assertNotNull("El perfil debe avisar el problema: $url", p.problem)
    }

    private fun assertAllowed(provider: AiProvider, url: String) {
        val fake = Capture()
        AiClient(fake).listModels(profile(provider, url), fakeSession)
        assertEquals(1, fake.sent.size)
        assertEquals("Bearer synthetic-canary-token", fake.sent.single().headers["Authorization"])
    }

    @Test fun chatGptRejectsLocalHttp() = assertRejectsBeforeNetwork(AiProvider.CHATGPT, "http://127.0.0.1:8765/backend-api/codex")

    @Test fun chatGptRejectsLookalikeHost() =
        assertRejectsBeforeNetwork(AiProvider.CHATGPT, "https://chatgpt.com.attacker.invalid/backend-api/codex")

    @Test fun geminiGoogleRejectsLocalHttp() = assertRejectsBeforeNetwork(AiProvider.GEMINI_GOOGLE, "http://127.0.0.1:8765/v1beta")

    @Test fun geminiGoogleRejectsLookalikeHost() =
        assertRejectsBeforeNetwork(AiProvider.GEMINI_GOOGLE, "https://generativelanguage.googleapis.com.attacker.invalid/v1beta")

    @Test fun rejectsTrickyVariantsOfTheOfficialUrl() {
        for (url in listOf(
            "https://chatgpt.com./backend-api/codex",
            "https://user@chatgpt.com/backend-api/codex",
            "https://attacker.invalid@chatgpt.com/backend-api/codex",
            "https://chatgpt.com:8443/backend-api/codex",
            "https://chatgpt.com:443/backend-api/codex",
            "https://chatgpt.com/backend-api/codex/../../other",
            "https://chatgpt.com/backend-api/codex%2F..%2Fother",
            "https://chatgpt.com/backend-api/codexfake",
            "https://chatgpt.com/other",
            "HTTP://chatgpt.com/backend-api/codex",
        )) assertRejectsBeforeNetwork(AiProvider.CHATGPT, url)
        assertRejectsBeforeNetwork(AiProvider.GEMINI_GOOGLE, "https://evil.invalid/v1beta")
    }

    @Test fun officialUrlsStillWork() {
        assertAllowed(AiProvider.CHATGPT, "https://chatgpt.com/backend-api/codex")
        assertAllowed(AiProvider.CHATGPT, " https://chatgpt.com/backend-api/codex/ ")
        assertAllowed(AiProvider.GEMINI_GOOGLE, "https://generativelanguage.googleapis.com/v1beta")
        assertNull(profile(AiProvider.CHATGPT, AiProvider.CHATGPT.defaultBaseUrl).problem)
    }

    @Test fun completeAlsoRefusesForeignHost() {
        val fake = Capture()
        try {
            AiClient(fake).complete(profile(AiProvider.CHATGPT, "https://attacker.invalid/backend-api/codex"), fakeSession, "s", "t", null)
            fail("Debió rechazar")
        } catch (_: AiException) {
        }
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun localCompatibleServerIsStillAllowedWithoutKey() {
        val fake = object : HttpTransport {
            val sent = mutableListOf<HttpRequest>()
            override fun send(request: HttpRequest): HttpResponse { sent += request; return HttpResponse(200, """{"data":[{"id":"test"}]}""") }
        }
        AiClient(fake).listModels(AiProfile("qa51", "qa51", AiProvider.OPENAI_COMPATIBLE, baseUrl = "http://127.0.0.1:1234/v1", model = "test"), null)
        val r = fake.sent.single()
        assertEquals("http://127.0.0.1:1234/v1/models", r.url)
        assertNull(r.headers["Authorization"])
    }
}
