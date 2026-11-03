package io.trazo.app.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.trazo.core.Brush
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceExportTest {
    @Test fun pngKeepsInkOnBothSidesOfThePage() {
        val page = Page(
            "outside",
            strokes = listOf(
                dot("left", -80f, 240f, width = 14f),
                dot("right", Notebook.PAGE_WIDTH + 80f, 420f, width = 14f),
            ),
        )

        val bitmap = png(page, scale = 1f)
        try {
            // Bounds include fourteen units of padding around the two strokes.
            assertEquals(Notebook.PAGE_WIDTH.toInt() + 188, bitmap.width)
            assertRedNear(bitmap, x = 14, y = 240)
            assertRedNear(bitmap, x = bitmap.width - 14, y = 420)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun normalPageKeepsItsExistingDefaultPngSize() {
        val bitmap = png(Page("normal"))
        try {
            assertEquals(2_000, bitmap.width)
            assertEquals(2_828, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun thumbnailKeepsRequestedWidthAndShowsInkOutsideBothEdges() {
        val page = Page(
            "thumbnail-outside",
            strokes = listOf(
                dot("left", -80f, 240f, width = 14f),
                dot("right", Notebook.PAGE_WIDTH + 80f, 420f, width = 14f),
            ),
        )
        val requestedWidth = 200
        val bitmap = PageExporter.thumbnail(page, Paper.PLAIN, requestedWidth)
        try {
            assertEquals(requestedWidth, bitmap.width)
            val scale = requestedWidth.toDouble() / (Notebook.PAGE_WIDTH + 188.0)
            assertRedNear(bitmap, (14.0 * scale).roundToInt(), (240.0 * scale).roundToInt())
            assertRedNear(bitmap, (1_174.0 * scale).roundToInt(), (420.0 * scale).roundToInt())
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun threeByThreeWorkspaceIsScaledToRasterLimitsWithoutCropping() {
        val page = Page(
            "workspace",
            strokes = listOf(
                dot("top-left", -Notebook.PAGE_WIDTH, -Notebook.PAGE_HEIGHT),
                dot("top-right", 2f * Notebook.PAGE_WIDTH, -Notebook.PAGE_HEIGHT),
                dot("bottom-left", -Notebook.PAGE_WIDTH, 2f * Notebook.PAGE_HEIGHT),
                dot("bottom-right", 2f * Notebook.PAGE_WIDTH, 2f * Notebook.PAGE_HEIGHT),
            ),
        )

        // The old requested scale of 2 would use about 51 MP for this 3x3 workspace.
        val bitmap = png(page)
        try {
            assertTrue("width ${bitmap.width} exceeds the raster limit", bitmap.width <= 8_192)
            assertTrue("height ${bitmap.height} exceeds the raster limit", bitmap.height <= 8_192)
            assertTrue("${bitmap.width}x${bitmap.height} exceeds 16 MP", bitmap.width.toLong() * bitmap.height <= 16_000_000L)

            val scaleX = bitmap.width.toDouble() / (3.0 * Notebook.PAGE_WIDTH + 20.0)
            val scaleY = bitmap.height.toDouble() / (3.0 * Notebook.PAGE_HEIGHT + 20.0)
            assertRedNear(bitmap, (10.0 * scaleX).roundToInt(), (10.0 * scaleY).roundToInt())
            assertRedNear(bitmap, (3.0 * Notebook.PAGE_WIDTH * scaleX + 10.0 * scaleX).roundToInt(), (10.0 * scaleY).roundToInt())
            assertRedNear(bitmap, (10.0 * scaleX).roundToInt(), (3.0 * Notebook.PAGE_HEIGHT * scaleY + 10.0 * scaleY).roundToInt())
            assertRedNear(
                bitmap,
                (3.0 * Notebook.PAGE_WIDTH * scaleX + 10.0 * scaleX).roundToInt(),
                (3.0 * Notebook.PAGE_HEIGHT * scaleY + 10.0 * scaleY).roundToInt(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun png(page: Page, scale: Float = 2f): Bitmap {
        val bytes = ByteArrayOutputStream().also {
            PageExporter.writePng(page, Paper.PLAIN, it, scale = scale)
        }.toByteArray()
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun dot(id: String, x: Float, y: Float, width: Float = 10f) = InkStroke(
        id = id,
        points = listOf(InkPoint(x, y)),
        color = Color.RED,
        width = width,
        brush = Brush.BALLPOINT,
    )

    private fun assertRedNear(bitmap: Bitmap, x: Int, y: Int) {
        for (dy in -3..3) for (dx in -3..3) {
            val px = x + dx
            val py = y + dy
            if (px in 0 until bitmap.width && py in 0 until bitmap.height && bitmap.getPixel(px, py) == Color.RED) return
        }
        throw AssertionError("No red ink near ($x,$y) in ${bitmap.width}x${bitmap.height}")
    }
}
