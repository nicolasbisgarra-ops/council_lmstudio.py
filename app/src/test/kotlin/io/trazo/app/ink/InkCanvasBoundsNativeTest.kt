package io.trazo.app.ink

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkCanvasBoundsNativeTest {
    private fun stylusEvent(action: Int, time: Long, x: Float, y: Float): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.8f
            size = 0.02f
        })
        return MotionEvent.obtain(
            time - 10, time, action, 1, properties, coordinates,
            0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_STYLUS, 0,
        )
    }

    private fun fingerEvent(action: Int, time: Long, x: Float, y: Float, downTime: Long = time - 10): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 0.02f
        })
        return MotionEvent.obtain(
            downTime, time, action, 1, properties, coordinates,
            0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun twoFingerEvent(
        action: Int,
        downTime: Long,
        time: Long,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 0.02f },
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 0.02f },
        )
        return MotionEvent.obtain(
            downTime, time, action, 2, properties, coordinates,
            0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun send(view: InkCanvasView, event: MotionEvent) {
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test fun `stylus can draw in negative workspace and native view renders it`() {
        val controller = InkController(patterns = { emptyList() }, ids = IdGenerator.sequential("bounds"))
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 900)
        val bitmap = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        var before: Bitmap? = null
        try {
            // Pan the notebook right and down, revealing document coordinates above and left of page zero.
            send(view, fingerEvent(MotionEvent.ACTION_DOWN, 1_000L, 100f, 100f))
            send(view, fingerEvent(MotionEvent.ACTION_MOVE, 1_020L, 400f, 400f))
            send(view, fingerEvent(MotionEvent.ACTION_UP, 1_040L, 400f, 400f))

            bitmap.eraseColor(Color.MAGENTA)
            view.draw(Canvas(bitmap))
            before = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            val start = view.documentToScreen(-250f, -250f)
            val end = view.documentToScreen(-150f, -200f)
            assertTrue("negative-workspace start should be visible: $start", start.first >= 0f && start.first < view.width && start.second >= 0f && start.second < view.height)
            assertTrue("negative-workspace end should be visible: $end", end.first >= 0f && end.first < view.width && end.second >= 0f && end.second < view.height)
            send(view, stylusEvent(MotionEvent.ACTION_DOWN, 2_000L, start.first, start.second))
            send(view, stylusEvent(MotionEvent.ACTION_MOVE, 2_020L, end.first, end.second))
            send(view, stylusEvent(MotionEvent.ACTION_UP, 2_040L, end.first, end.second))

            val stroke = controller.page.strokes.single()
            assertTrue("negative x and y coordinates should be stored", stroke.points.any { it.x < 0f && it.y < 0f })

            bitmap.eraseColor(Color.MAGENTA)
            view.draw(Canvas(bitmap))
            var changed = 0
            var changedAnywhere = 0
            val left = start.first.toInt().coerceIn(0, bitmap.width - 1)
            val right = end.first.toInt().coerceIn(left, bitmap.width - 1)
            val top = start.second.toInt().coerceIn(0, bitmap.height - 1)
            val bottom = end.second.toInt().coerceIn(top, bitmap.height - 1)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (before!!.getPixel(x, y) != bitmap.getPixel(x, y)) {
                    changedAnywhere++
                    if (x in left..right && y in top..bottom) changed++
                }
            }
            val screenPoints = stroke.points.map { view.documentToScreen(it.x, it.y) }
            val details = "changed=$changed/$changedAnywhere region=[$left,$top..$right,$bottom] " +
                "start=$start end=$end screenStroke=$screenPoints docPoints=${stroke.points} " +
                "strokeBounds=${stroke.bounds} scale=${view.viewport.scale} tx=${view.viewport.tx} ty=${view.viewport.ty}"
            assertTrue("the native Canvas should render the negative-coordinate stroke; $details", changed > 0)
        } finally {
            before?.recycle()
            bitmap.recycle()
        }
    }

    @Test fun `new stylus contacts are clamped to the workspace bounds`() {
        val controller = InkController(patterns = { emptyList() }, ids = IdGenerator.sequential("clamped"))
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 900)
        val bounds = CanvasBounds.aroundPage(view.pageWidth, view.pageHeight)
        // Model a stale or externally restored transform whose visible pixels lie beyond the canvas.
        view.viewport = Viewport().apply {
            scale = 1f
            tx = 100_000f
            ty = 100_000f
        }
        send(view, stylusEvent(MotionEvent.ACTION_DOWN, 3_000L, 360f, 450f))
        send(view, stylusEvent(MotionEvent.ACTION_UP, 3_020L, 360f, 450f))

        val point = controller.page.strokes.single().points.first()
        assertTrue("x should clamp at the outer left edge", point.x == bounds.left)
        assertTrue("y should clamp at the outer top edge", point.y == bounds.top)
    }

    @Test fun `zooming in from page fit never reduces the scale`() {
        val view = InkCanvasView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1467, 709)
        val pageFit = view.pxPerUnit

        view.zoomStep(1.1f)

        assertTrue("zoom-in from the initial page fit must increase scale", view.pxPerUnit > pageFit)
        assertTrue("maximum zoom remains relative to the central page fit", view.pxPerUnit <= pageFit * 8f)
    }

    @Test fun `pending page reset refits before stylus mapping pinch and draw`() {
        val controller = InkController(patterns = { emptyList() }, ids = IdGenerator.sequential("pending-page"))
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 900)

        // A prior page was zoomed; goToPage mutates this shared Viewport to zero while
        // AndroidView.update for the new page is still pending.
        view.zoomStep(2f)
        val staleCoordinates = view.screenToDocument(150f, 200f)
        val fitScale = 720f / view.pageWidth
        val expectedX = 150f / fitScale
        val expectedY = 200f / fitScale
        assertTrue(kotlin.math.abs(staleCoordinates.first - expectedX) > 10f)
        view.viewport.scale = 0f

        send(view, stylusEvent(MotionEvent.ACTION_DOWN, 1_000L, 150f, 200f))
        send(view, stylusEvent(MotionEvent.ACTION_UP, 1_020L, 150f, 200f))
        val firstPoint = controller.page.strokes.single().points.first()
        assertEquals(expectedX.toDouble(), firstPoint.x.toDouble(), 0.05)
        assertEquals(expectedY.toDouble(), firstPoint.y.toDouble(), 0.05)
        assertTrue(view.viewport.scale.isFinite())
        assertTrue(view.viewport.tx.isFinite() && view.viewport.ty.isFinite())

        // The real two-finger path must also begin from a fitted transform instead of dividing
        // its new scale by zero in ScaleGestureDetector.onScale.
        view.zoomStep(2f)
        view.viewport.scale = 0f
        send(view, fingerEvent(MotionEvent.ACTION_DOWN, 3_000L, 220f, 400f, downTime = 3_000L))
        send(view, twoFingerEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            downTime = 3_000L, time = 3_010L, x0 = 220f, y0 = 400f, x1 = 420f, y1 = 400f,
        ))
        send(view, twoFingerEvent(
            MotionEvent.ACTION_MOVE,
            downTime = 3_000L, time = 3_020L, x0 = 200f, y0 = 400f, x1 = 440f, y1 = 400f,
        ))
        send(view, twoFingerEvent(
            MotionEvent.ACTION_MOVE,
            downTime = 3_000L, time = 3_030L, x0 = 180f, y0 = 400f, x1 = 460f, y1 = 400f,
        ))
        assertTrue(
            "two-finger expansion should zoom from the refitted scale=${fitScale}; actual=${view.viewport.scale}",
            view.viewport.scale.isFinite() && view.viewport.scale > fitScale,
        )
        assertTrue(view.viewport.tx.isFinite() && view.viewport.ty.isFinite())
        val mapped = view.screenToDocument(180f, 400f)
        assertTrue(mapped.first.isFinite() && mapped.second.isFinite())
        val mappedToScreen = view.documentToScreen(mapped.first, mapped.second)
        assertTrue(mappedToScreen.first.isFinite() && mappedToScreen.second.isFinite())

        // A frame may be requested before Compose supplies its refreshed viewport too.
        view.viewport.scale = 0f
        val bitmap = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            assertTrue(view.viewport.scale.isFinite() && view.viewport.scale > 0f)
            assertTrue(view.viewport.tx.isFinite() && view.viewport.ty.isFinite())
            val drawnMapping = view.screenToDocument(150f, 200f)
            assertTrue(drawnMapping.first.isFinite() && drawnMapping.second.isFinite())
            val drawnPoint = view.documentToScreen(drawnMapping.first, drawnMapping.second)
            assertTrue(drawnPoint.first.isFinite() && drawnPoint.second.isFinite())
        } finally {
            bitmap.recycle()
        }
    }
}
