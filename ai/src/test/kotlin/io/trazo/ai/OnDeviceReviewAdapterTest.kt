package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** This adapter directly implements the stage-3 OnDeviceCompletion ABI of snapshot v2. */
class OnDeviceReviewAdapterTest {
    private val profile = AiProfile("local-id", "Local", AiProvider.ON_DEVICE, model = "gguf-123")
    private class Capture : OnDeviceCompletion {
        var calls = 0
        var lastImage: ByteArray? = null
        var cancellation = 0
        override fun cancel() { cancellation++ }
        override fun complete(profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int): String = "fallback"
        override fun stream(profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int, onText: (String) -> Unit): String {
            calls++
            lastImage = png
            onText("part")
            return "final"
        }
    }
    @Test fun localAdapterCallsRealOnDeviceInterfaceAndStreamsWithoutHttp() {
        val b = Capture()
        val adapter = OnDeviceReviewAdapter(b, profile, "sys")
        val deltas = mutableListOf<String>()
        assertEquals("final", adapter.complete(ReviewPayload("math", byteArrayOf(4, 2)), deltas::add))
        assertEquals(listOf("part"), deltas)
        assertEquals(1, b.calls)
        assertEquals(2, b.lastImage!!.size)
        adapter.cancel()
        assertEquals(1, b.cancellation)
    }
    @Test fun textOnlyConsentRemovesImageAtTheBoundary() {
        val b = Capture()
        val a = OnDeviceReviewAdapter(b, profile.copy(localTextOnly=true), "sys")
        a.complete(ReviewPayload("math", byteArrayOf(5))) {}
        assertEquals(null, b.lastImage)
        assertEquals(1, b.calls)
    }
    @Test fun textOnlyConsentIsAppliedBeforeVisionCapabilityCheck() {
        val b = Capture()
        val adapter = OnDeviceReviewAdapter(b, profile.copy(localTextOnly = true), "sys")
        val dispatcher = ReviewBackendDispatcher(adapter, adapter, ReviewEngineCapabilities(vision = false, incrementalText = true))
        assertEquals("final", dispatcher.execute(ReviewRoute.RUN_LOCAL, ReviewPayload("math", byteArrayOf(5))))
        assertEquals(null, b.lastImage)
        val strict = OnDeviceReviewAdapter(b, profile, "sys")
        val strictDispatcher = ReviewBackendDispatcher(strict, strict, ReviewEngineCapabilities(vision = false, incrementalText = true))
        try {
            strictDispatcher.execute(ReviewRoute.RUN_LOCAL, ReviewPayload("math", byteArrayOf(5)))
            fail("an image must not reach a text-only model without consent")
        } catch (_: ReviewUnavailable) { }
    }
    @Test fun remoteProfileCannotBeUsedWithLocalRuntime() {
        val b = Capture()
        try { OnDeviceReviewAdapter(b, profile.copy(provider=AiProvider.OPENAI_COMPATIBLE), "sys"); fail("expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, b.calls)
    }
    @Test fun missingModelIsRejectedBeforeLaunchingEngine() {
        val b = Capture()
        try { OnDeviceReviewAdapter(b, profile.copy(model=""), "sys"); fail("expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertFalse(b.calls > 0)
    }
}
