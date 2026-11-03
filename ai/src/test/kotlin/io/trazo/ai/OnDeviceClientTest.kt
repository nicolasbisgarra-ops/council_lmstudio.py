package io.trazo.ai

import org.junit.Assert.*
import org.junit.Test

class OnDeviceClientTest {
    private val profile = AiProfile("local", "Local", AiProvider.ON_DEVICE, model = "imported-id")
    private val noNetwork = object : HttpTransport {
        override fun send(request: HttpRequest): HttpResponse = error("Local inference attempted HTTP")
    }

    @Test fun localProfileNeedsModelButNoUrlOrCredentials() {
        assertNull(profile.problem)
        assertNotNull(profile.copy(model = "").problem)
    }

    @Test fun localCompletionKeepsImageAndReturnsEngineReply() {
        val image = byteArrayOf(1, 2, 3)
        val engine = object : OnDeviceCompletion {
            override fun complete(profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int): String {
                assertArrayEquals(image, png)
                assertEquals("question", text)
                assertEquals(17, maxTokens)
                return "local reply"
            }
        }
        assertEquals("local reply", AiClient(noNetwork, local = engine).complete(profile, null, "system", "question", image, 17))
    }

    @Test fun explicitTextOnlyModeDoesNotPassImageAndExplainsLimitation() {
        val engine = object : OnDeviceCompletion {
            override fun complete(profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int): String {
                assertNull(png)
                assertTrue(system.contains("No recibe ninguna imagen"))
                return "answer"
            }
        }
        val deltas = mutableListOf<String>()
        assertEquals("answer", AiClient(noNetwork, local = engine).stream(profile.copy(localTextOnly = true), null, "system", "question", byteArrayOf(1), onText = deltas::add))
        assertEquals(listOf("answer"), deltas)
    }

    @Test fun missingEngineFailsClearlyWithoutHttp() {
        try {
            AiClient(noNetwork).complete(profile, null, "system", "question", null)
            fail("Expected missing runtime error")
        } catch (e: AiException) {
            assertTrue(e.message.orEmpty().contains("motor local"))
        }
    }

    @Test fun localProfileRoundTripPreservesTextOnlyConsent() {
        val expected = profile.copy(localTextOnly = true)
        assertEquals(expected, AiProfile.decodeList(AiProfile.encodeList(listOf(expected))).single())
    }
}
