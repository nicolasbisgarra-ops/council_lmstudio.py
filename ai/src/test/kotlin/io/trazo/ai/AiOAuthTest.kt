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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread

class AiOAuthTest {
    private class Fake(vararg replies: HttpResponse) : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        private val queue = ArrayDeque(replies.toList())
        override fun send(request: HttpRequest): HttpResponse { sent += request; return queue.removeFirst() }
    }

    private val chatGpt = AiProfile("id", "n", AiProvider.CHATGPT, model = "gpt-5")
    private val google = AiProfile("id", "n", AiProvider.GEMINI_GOOGLE, model = "gemini-x", oauthClientId = "123.apps", oauthClientSecret = "s", googleProject = "mi-proyecto")

    private fun jwt(payload: String) =
        "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".firma"

    @Test fun pkceChallengeIsTheRfcExample() {
        // RFC 7636, apéndice B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").challenge)
    }

    @Test fun chatGptAuthorizeUrlMatchesCodex() {
        val o = AiOAuth(Fake())
        val c = o.config(chatGpt)
        val url = o.authorizeUrl(c, c.redirectUri(1455), Pkce("v"), "st")
        assertTrue(url.startsWith("https://auth.openai.com/oauth/authorize?"))
        val q = LoopbackReceiver.parseQuery(url.substringAfter('?'))
        assertEquals(AiOAuth.CODEX_CLIENT_ID, q["client_id"])
        assertEquals("http://localhost:1455/auth/callback", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("st", q["state"])
        assertEquals("openid profile email offline_access", q["scope"])
        assertFalse("el verificador no va al navegador", "v&" in url || url.endsWith("=v"))
    }

    @Test fun googleUsesTheUsersClientAndAsksForOfflineAccess() {
        val o = AiOAuth(Fake())
        val c = o.config(google)
        assertEquals(0, c.port)
        val q = LoopbackReceiver.parseQuery(o.authorizeUrl(c, c.redirectUri(5000), Pkce("v"), "st").substringAfter('?'))
        assertEquals("123.apps", q["client_id"])
        assertEquals("http://127.0.0.1:5000/", q["redirect_uri"])
        assertEquals("offline", q["access_type"])
        assertTrue(q["scope"]!!.contains("generative-language"))
    }

    @Test fun exchangeReadsTheChatGptAccountFromTheIdToken() {
        val id = jwt("""{"email":"a@b.c","https://api.openai.com/auth":{"chatgpt_account_id":"acc-1"}}""")
        val fake = Fake(HttpResponse(200, """{"access_token":"at","refresh_token":"rt","id_token":"$id","expires_in":100}"""))
        val o = AiOAuth(fake, clock = { 1_000 })
        val t = o.exchange(o.config(chatGpt), "code1", "http://localhost:1455/auth/callback", Pkce("ver"))
        assertEquals(OAuthTokens("at", "rt", 101_000, "acc-1", "a@b.c"), t)
        val r = fake.sent.single()
        assertEquals("application/x-www-form-urlencoded", r.headers["Content-Type"])
        val form = LoopbackReceiver.parseQuery(r.body!!)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("ver", form["code_verifier"])
        assertNull(form["client_secret"])
    }

    @Test fun refreshKeepsTheOldRefreshTokenWhenGoogleOmitsIt() {
        val fake = Fake(HttpResponse(200, """{"access_token":"nuevo","expires_in":3600}"""))
        var now = 0L
        val o = AiOAuth(fake, clock = { now })
        val old = OAuthTokens("viejo", "rt", expiresAtMs = 30_000)
        val t = o.fresh(google, old)
        assertEquals("nuevo", t.accessToken)
        assertEquals("rt", t.refreshToken)
        assertEquals("s", LoopbackReceiver.parseQuery(fake.sent.single().body!!)["client_secret"])
        now = 0
        assertEquals("no renueva si todavía sirve", t, o.fresh(google, t))
    }

    @Test fun rejectedSignInShowsTheProvidersReason() {
        val o = AiOAuth(Fake(HttpResponse(400, """{"error":"invalid_grant","error_description":"Bad code"}""")))
        try { o.exchange(o.config(google), "x", "r", Pkce("v")); fail() } catch (e: AiException) {
            assertTrue(e.message!!, e.message!!.contains("Bad code"))
        }
    }

    @Test fun tokensRoundTripAndApiKeysAreNotSessions() {
        val t = OAuthTokens("a", "r", 5, "acc")
        assertEquals(t, OAuthTokens.decode(t.encode()))
        assertNull(OAuthTokens.decode("sk-123"))
    }

    @Test fun chatGptSendsCodexHeadersAndReadsTheStream() {
        val sse = """
            event: response.created
            data: {"type":"response.created"}

            data: {"type":"response.output_text.delta","delta":"El resultado "}
            data: {"type":"response.output_text.delta","delta":"es 3"}
            data: {"type":"response.completed","response":{"output":[]}}
        """.trimIndent()
        val fake = Fake(HttpResponse(200, sse))
        val reply = AiClient(fake).complete(chatGpt, OAuthTokens("at", accountId = "acc").encode(), "sys", "hola", byteArrayOf(1))
        assertEquals("El resultado es 3", reply)
        val r = fake.sent.single()
        assertEquals("https://chatgpt.com/backend-api/codex/responses", r.url)
        assertEquals("Bearer at", r.headers["Authorization"])
        assertEquals("acc", r.headers["chatgpt-account-id"])
        assertEquals("codex_cli_rs", r.headers["originator"])
        val body = Json.parseToJsonElement(r.body!!).jsonObject
        assertEquals("sys", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        val content = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun chatGptFallsBackToTheCompletedOutputAndReportsFailures() {
        val done = """data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"3"}]}]}}"""
        assertEquals("3", AiClient(Fake()).streamedText(done))
        try {
            AiClient(Fake()).streamedText("""data: {"type":"response.failed","response":{"error":{"message":"modelo no disponible"}}}"""); fail()
        } catch (e: AiException) { assertTrue(e.message!!.contains("modelo no disponible")) }
    }

    @Test fun signInProfilesNeedASession() {
        try { AiClient(Fake()).complete(chatGpt, null, "", "x", null); fail() } catch (e: AiException) {
            assertTrue(e.message!!.contains("iniciar sesión"))
        }
    }

    @Test fun googleAccountSendsBearerAndQuotaProject() {
        val fake = Fake(HttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"3"}]}}]}"""))
        assertEquals("3", AiClient(fake).complete(google, OAuthTokens("gt").encode(), "", "x", null))
        val r = fake.sent.single()
        assertEquals("Bearer gt", r.headers["Authorization"])
        assertEquals("mi-proyecto", r.headers["x-goog-user-project"])
        assertNull(r.headers["x-goog-api-key"])
    }

    @Test fun googleProfileNeedsAClientId() {
        assertTrue(google.copy(oauthClientId = "").problem!!.contains("ID de cliente"))
        assertNull(chatGpt.problem)
    }

    @Test fun loopbackReceiverReturnsTheCodeAndIgnoresOtherRequests() {
        LoopbackReceiver(0).use { rx ->
            val port = rx.port
            thread {
                fun hit(path: String) = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).run {
                    runCatching { responseCode }.getOrDefault(-1).also { disconnect() }
                }
                hit("/favicon.ico")
                hit("/auth/callback?code=c%2B1&state=s")
            }
            assertEquals(mapOf("code" to "c+1", "state" to "s"), rx.await("/auth/callback", 10_000))
        }
    }

    @Test fun signInChecksTheState() {
        val o = AiOAuth(Fake())
        val p = google
        try {
            o.signIn(p, open = { url ->
                val redirect = LoopbackReceiver.parseQuery(url.substringAfter('?'))["redirect_uri"]!!
                thread { runCatching { (URL("${redirect}?code=x&state=otro").openConnection() as HttpURLConnection).responseCode } }
            }, timeoutMs = 10_000)
            fail()
        } catch (e: AiException) { assertTrue(e.message!!.contains("no coincide")) }
    }
}
