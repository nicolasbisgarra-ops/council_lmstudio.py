package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTutorTest {
    private class Fake(private val reply: String) : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        override fun send(request: HttpRequest): HttpResponse {
            sent += request
            return HttpResponse(200, "data: {\"choices\":[{\"delta\":{\"content\":${kotlinx.serialization.json.JsonPrimitive(reply)}}}]}\n\ndata: [DONE]\n\n")
        }
    }

    private val profile = AiProfile("id", "n", AiProvider.OPENAI, model = "m")

    @Test fun `lee el primer error con su renglon`() {
        val v = AiTutorPrompt.verdict("Paso 1\n\$\$x=2\$\$\n**ERROR (renglón 2):** el signo cambia al pasar de miembro.", lineCount = 3)!!
        assertFalse(v.correct)
        assertEquals(2, v.line)
        assertEquals("el signo cambia al pasar de miembro.", v.summary)
        assertEquals(4, AiTutorPrompt.verdict("Error en el renglon 4: falta un paréntesis")!!.line)
        // Un renglón que no existe no se señala.
        assertNull(AiTutorPrompt.verdict("ERROR (renglón 9): x", lineCount = 3)!!.line)
    }

    @Test fun `lee el veredicto correcto o ninguno`() {
        val v = AiTutorPrompt.verdict("Todo bien.\nCORRECTO: la resolución es válida.")!!
        assertTrue(v.correct)
        assertEquals("la resolución es válida.", v.summary)
        assertNull(AiTutorPrompt.verdict("Hola, ¿en qué te ayudo?"))
        assertEquals("Paso 1", AiTutorPrompt.withoutVerdict("Paso 1\nERROR (renglón 1): mal"))
    }

    @Test fun `la imagen va en cada pedido que la trae y la conversacion viaja como transcripcion`() {
        val first = Fake("Paso\nCORRECTO: bien")
        AiTutor(AiClient(first)).ask(profile, "k", listOf("a", ""), byteArrayOf(1, 2, 3), emptyList(), null)
        val body1 = first.sent.single().body!!
        assertTrue(body1.contains("image"))
        assertTrue(body1.contains("2: ?"))

        val next = Fake("Claro")
        val history = listOf(TutorTurn(TutorRole.TUTOR, "Paso\nCORRECTO: bien"))
        val text = AiTutor(AiClient(next)).ask(profile, "k", listOf("a"), byteArrayOf(1, 2, 3), history, "Explicá más")
        assertEquals("Claro", text)
        val body2 = next.sent.single().body!!
        assertTrue(body2.contains("image"))
        val bare = Fake("Ok")
        AiTutor(AiClient(bare)).ask(profile, "k", emptyList(), null, history, "¿Y?")
        val body3 = bare.sent.single().body!!
        assertFalse(body3.contains("image_url"))
        assertTrue(body3.contains("No hay renglones manuscritos"))
        assertTrue(body2.contains("[Tutor]"))
        assertTrue(body2.contains("Explicá más"))
    }

    @Test fun `la transcripcion se recorta`() {
        val long = List(40) { TutorTurn(if (it % 2 == 0) TutorRole.STUDENT else TutorRole.TUTOR, "m$it") }
        val t = AiTutorPrompt.userText(listOf("x"), long, "q")
        assertTrue(t.contains("Se omitieron 24"))
        assertFalse(t.contains("\nm0\n"))
        assertTrue(t.contains("m39"))
    }
}
