package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/** Auditoría M13, M14, M15 y A17 (módulo ai). */
class AuditAiTest {
    private class Fake(private val reply: HttpResponse) : HttpTransport {
        override fun send(request: HttpRequest) = reply
    }

    @Test fun `M13 gemini thought parts are not part of the answer`() {
        val fake = Fake(HttpResponse(200, """{"candidates":[{"content":{"parts":[
            {"text":"Pienso que 1+2 es…","thought":true},{"text":"3"}]}}]}"""))
        val reply = AiClient(fake).complete(AiProfile("id", "n", AiProvider.GEMINI, model = "m"), "k", "", "q", null)
        assertEquals("3", reply)
    }

    @Test fun `M14 free text with a huge line number does not crash`() {
        val r = AiReviewParser.parseFreeText("Renglón 99999999999: signo mal\nRenglón 2: falta el +C")
        assertEquals(listOf(2), r.marks.map { it.line })
    }

    @Test fun `M14 json followed by more braces in prose is still read`() {
        val r = AiReviewParser.parseJson(
            """Acá va: {"correcto": false, "marcas": [{"renglon": 3, "tipo": "error", "breve": "signo"}]} y el conjunto {1, 2}.""",
        )
        assertNotNull(r)
        assertEquals(listOf(3), r!!.marks.map { it.line })
    }

    @Test fun `M14 prose with braces before the json`() {
        val r = AiReviewParser.parseJson("""Sea A = {x}. {"correcto": true, "marcas": []}""")
        assertNotNull(r)
        assertTrue(r!!.correct)
    }

    @Test fun `M15 correct only counts as the verdict line`() {
        assertNull(AiTutorPrompt.verdict("Correcto el planteo, pero revisá el último paso."))
        assertNull(AiTutorPrompt.verdict("Correctos los dos primeros pasos."))
        assertTrue(AiTutorPrompt.verdict("CORRECTO")!!.correct)
        assertTrue(AiTutorPrompt.verdict("**CORRECTO:** bien")!!.correct)
        assertEquals("bien", AiTutorPrompt.verdict("**CORRECTO:** bien")!!.summary)
        val e = AiTutorPrompt.verdict("Correcto el planteo.\nERROR (renglón 2): signo", lineCount = 3)!!
        assertFalse(e.correct)
    }

    @Test fun `A17 loopback ignores broken connections and foreign state`() {
        LoopbackReceiver(0).use { rx ->
            val port = rx.port
            thread {
                fun hit(path: String) = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).run {
                    runCatching { responseCode }.getOrDefault(-1).also { disconnect() }
                }
                Socket("127.0.0.1", port).close() // conexión que se corta sin pedir nada
                hit("/auth/callback?code=x&state=%ZZ") // escape inválido
                hit("/auth/callback?code=forged&state=otro")
                hit("/auth/callback?code=ok&state=s")
            }
            assertEquals(mapOf("code" to "ok", "state" to "s"), rx.await("/auth/callback", 10_000, expectedState = "s"))
        }
    }
}
