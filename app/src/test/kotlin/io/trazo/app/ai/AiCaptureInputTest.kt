package io.trazo.app.ai

import android.app.Application
import io.trazo.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiCaptureInputTest {
    @Test fun captureKeepsOriginalCalculationInputsButExcludesGeneratedResult() {
        val source = InkStroke("source", listOf(InkPoint(10f, 10f), InkPoint(60f, 40f)))
        val input = source.copy(calc = CalcTag("calculation", inputs = 1))
        val generated = source.copy(id = "calculated", points = listOf(InkPoint(400f, 400f), InkPoint(900f, 900f)), calc = CalcTag("calculation", result = true))
        assertArrayEquals(AiCapture.png(listOf(source), emptyList(), 512),
            AiCapture.png(listOf(input, generated), emptyList(), 512))
    }
}
