package io.trazo.ai

import org.junit.Assert.*
import org.junit.Test

class ChatGptCatalogRegressionTest {
    private val profile = AiProfile("catalog", "ChatGPT", AiProvider.CHATGPT)
    private val credential = OAuthTokens("private-access", accountId = "account").encode()
    private class Server(private val body: String) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun send(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(200, body)
        }
    }
    @Test fun jsonCatalogKeepsServerOrderAndSessionScope() {
        val server = Server("""{"models":[{"slug":"preferred","display_name":"Zulu","visibility":"list"},{"slug":"second","display_name":"Alpha","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"preferred"},{"slug":" "},null]}""")
        val models = AiClient(server).listModels(profile, credential)
        assertEquals(listOf("preferred", "second"), models.map { it.id })
        assertEquals(listOf("Zulu", "Alpha"), models.map { it.displayName })
        val request = server.requests.single()
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("Bearer private-access", request.headers["Authorization"])
        assertEquals("account", request.headers["chatgpt-account-id"])
        assertEquals("https://chatgpt.com/backend-api/codex/models?client_version=${AiClient.CODEX_CLIENT_VERSION}", request.url)
        assertFalse(request.url.contains("private-access"))
        assertNotEquals("0.50.0", AiClient.CODEX_CLIENT_VERSION)
    }
    @Test fun compatibleDataCatalogUsesReturnedIdsRatherThanInventingModels() {
        val server = Server("""{"data":[{"id":"account-model","display_name":"Account model"}]}""")
        assertEquals(
            listOf(AiModelInfo("account-model", "Account model", reasoningEffortCatalogKnown = true)),
            AiClient(server).listModels(profile, credential),
        )
    }
    @Test fun emptyCatalogExplainsThatSignInDoesNotProveModelAccess() {
        val server = Server("""{"models":[]}""")
        try {
            AiClient(server).listModels(profile, credential)
            fail("An empty catalog must not fabricate model access")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("inició sesión"))
            assertTrue(e.message!!.contains("no devolvió modelos"))
            assertFalse(e.message!!.contains("private-access"))
        }
        assertEquals(1, server.requests.size)
    }
}
