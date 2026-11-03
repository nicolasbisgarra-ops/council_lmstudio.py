package io.trazo.app.ink

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkCanvasClipTest {
    // Simulate Compose sharing a Canvas with a sibling toolbar and no parent clip.
    // A plain View.drawColor used to erase every sentinel pixel around the View.
    @Test fun nativeCanvasCannotEraseToolbarOnEitherSide() {
        for ((width, height) in listOf(64 to 96, 96 to 64)) {
            val view = InkCanvasView(RuntimeEnvironment.getApplication())
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width + 48, height + 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            val canvas = Canvas(bitmap)
            canvas.translate(24f, 24f)
            view.draw(canvas)
            assertNotEquals(Color.MAGENTA, bitmap.getPixel(32, 32))
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (x !in 24 until width + 24 || y !in 24 until height + 24) {
                    assertEquals("Sibling pixel ($x,$y) at ${width}x$height", Color.MAGENTA, bitmap.getPixel(x, y))
                }
            }
            // The View must also restore the caller's clip for siblings drawn afterwards.
            canvas.drawRect(-20f, -20f, -10f, -10f, Paint().apply { color = Color.GREEN })
            assertEquals(Color.GREEN, bitmap.getPixel(8, 8))
            bitmap.recycle()
        }
    }
}
