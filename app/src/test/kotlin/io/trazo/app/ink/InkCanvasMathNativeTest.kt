package io.trazo.app.ink

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.trazo.core.Brush
import io.trazo.core.Bounds
import io.trazo.core.DefaultPatterns
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.InkPattern
import io.trazo.core.Paper
import io.trazo.core.PointerKind
import io.trazo.core.PresetPatterns
import io.trazo.core.ShapeKind
import io.trazo.core.Tool
import io.trazo.core.TrazoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkCanvasMathNativeTest {
    private fun realUserPatterns(): List<InkPattern> {
        val path = System.getProperty("trazo.userPatterns")
        assumeTrue("set -Dtrazo.userPatterns to enable real user-pattern coverage", path != null)
        return File(requireNotNull(path)).inputStream().use { TrazoFormat.decodePatterns(it) }
    }

    @Test fun `equal sign math completion and preset conversion render on native canvas`() {
        val patterns = PresetPatterns.all()
        val controller = InkController(patterns = { patterns }, ids = IdGenerator.sequential("native"))
        val events = mutableListOf<InkEvent>()
        controller.addListener { events += it }
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 1000)
        val target = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)

        fun draw() {
            target.eraseColor(Color.MAGENTA)
            view.draw(Canvas(target))
            assertTrue("drawing must paint the View bounds", target.getPixel(1, 1) != Color.MAGENTA)
        }

        fun insert(name: String, x: Float, y: Float) {
            val preset = PresetPatterns.signs().single { it.name == name }
            assertTrue("preset $name should insert", controller.insertPattern(preset, x, y, height = 40f).isNotEmpty())
            draw()
        }

        try {
            // A lone '=' must remain a valid page state without producing an answer.
            insert("Igual", 140f, 180f)
            assertTrue(controller.mathResults.isEmpty())

            // This is the same vector-preset path used by the pattern picker; the final '='
            // derives a green '5' without mutating the page's stored ink.
            insert("2", 120f, 360f)
            insert("Más", 180f, 360f)
            insert("3", 240f, 360f)
            insert("Igual", 300f, 360f)
            val result = controller.mathResults
            assertFalse("2+3= should produce derived result ink", result.isEmpty())
            assertTrue(result.all { it.patternId == "preset-5" })
            draw()

            // Draw a normal stroke, replace it with the built-in integral preset, then render
            // again. This exercises PageChanged invalidation after a preset conversion.
            controller.penDown(520f, 620f, 0.7f, 10_000L, PointerKind.STYLUS)
            controller.penMove(545f, 660f, 0.7f, 10_020L)
            controller.penUp(570f, 620f, 0.7f, 10_040L)
            draw()
            val source = controller.page.strokes.single { it.patternId == null }
            val converted = controller.convertWith(DefaultPatterns.integral(), listOf(source.id), nowMs = 10_050L)

            assertFalse("conversion using a built-in preset should replace the drawn stroke", converted.isNullOrEmpty())
            assertTrue(events.any { it is InkEvent.Converted && it.pattern.id == DefaultPatterns.INTEGRAL })
            assertTrue(controller.page.strokes.any { it.patternId == DefaultPatterns.INTEGRAL })
            assertEquals(setOf("preset-5"), controller.mathResults.map { it.patternId }.toSet())
            draw()
        } finally {
            target.recycle()
        }
    }

    @Test fun `native canvas draws all brushes selection eraser and a shape`() {
        val controller = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential("brush"))
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 1000)
        val target = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
        fun draw() {
            target.eraseColor(Color.MAGENTA)
            view.draw(Canvas(target))
            assertTrue("drawing must paint the View bounds", target.getPixel(1, 1) != Color.MAGENTA)
        }

        try {
            Brush.values().forEachIndexed { index, brush ->
                controller.tool = Tool.PEN
                controller.brush = brush
                val y = 120f + index * 65f
                controller.penDown(100f, y, 0.55f, 20_000L + index * 100L, PointerKind.STYLUS)
                controller.penMove(140f, y + 20f, 0.9f, 20_020L + index * 100L)
                controller.penUp(180f, y, 0.7f, 20_040L + index * 100L)
                assertEquals(brush, controller.page.strokes.last().brush)
                draw()
            }

            controller.select(listOf(controller.page.strokes.first().id))
            assertTrue(controller.selectionBounds != null)
            draw()

            controller.tool = Tool.SHAPE
            controller.brush = Brush.PEN
            controller.shape = ShapeKind.RECTANGLE
            controller.penDown(360f, 250f, 0.7f, 21_000L, PointerKind.STYLUS)
            controller.penMove(430f, 320f, 0.7f, 21_020L)
            draw() // wet shape preview
            controller.penUp(430f, 320f, 0.7f, 21_040L)
            draw() // committed vector strokes

            controller.tool = Tool.ERASER
            controller.penDown(140f, 140f, 0.7f, 22_000L, PointerKind.STYLUS)
            draw() // live eraser indicator
            controller.penUp(140f, 140f, 0.7f, 22_020L)
            draw() // page/cache after erasing
        } finally {
            target.recycle()
        }
    }

    @Test fun `live highlighter overlay survives zero-size layout and same-size restoration`() {
        val controller = InkController(patterns = { emptyList() }, ids = IdGenerator.sequential("resize"))
        controller.brush = Brush.HIGHLIGHTER
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 1000)
        controller.penDown(120f, 160f, 0.7f, 30_000L, PointerKind.STYLUS)
        controller.penMove(200f, 160f, 0.7f, 30_020L)

        val fullSize = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
        val zeroSize = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(fullSize)) // creates and paints the live-ink overlay bitmap
            view.layout(0, 0, 0, 0)
            view.draw(Canvas(zeroSize))
            view.layout(0, 0, 720, 1000)
            fullSize.eraseColor(Color.MAGENTA)
            view.draw(Canvas(fullSize)) // must allocate a live overlay for the restored size
            assertTrue("restored View must paint the native Canvas", fullSize.getPixel(1, 1) != Color.MAGENTA)
        } finally {
            fullSize.recycle()
            zeroSize.recycle()
        }
    }

    @Test fun `highlighter overlay drops cached math when autocompletion is disabled`() {
        val patterns = PresetPatterns.all()
        val controller = InkController(patterns = { patterns }, ids = IdGenerator.sequential("math-cache"))
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 1000)
        val target = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
        var before: Bitmap? = null
        try {
            fun insert(name: String, x: Float) {
                val preset = PresetPatterns.signs().single { it.name == name }
                controller.insertPattern(preset, x, 360f, height = 40f)
            }
            insert("2", 120f)
            insert("Más", 180f)
            insert("3", 240f)
            insert("Igual", 300f)
            val resultBounds = controller.mathResults.let { ink ->
                assertFalse(ink.isEmpty())
                ink.map { it.bounds }.reduce { a, b -> a.union(b) }
            }

            controller.brush = Brush.HIGHLIGHTER
            controller.penDown(resultBounds.left - 12f, resultBounds.centerY, 0.7f, 40_000L, PointerKind.STYLUS)
            controller.penMove(resultBounds.right + 12f, resultBounds.centerY, 0.7f, 40_020L)
            target.eraseColor(Color.MAGENTA)
            view.draw(Canvas(target))
            val baseline = target.copy(Bitmap.Config.ARGB_8888, false)
            before = baseline

            // mathEnabled clears the derived strokes without changing page.version. The overlay
            // still has to notice that cacheMath changed and remove its old copy of the answer.
            controller.mathEnabled = false
            target.eraseColor(Color.MAGENTA)
            view.draw(Canvas(target))

            val topLeft = view.documentToScreen(resultBounds.left - 8f, resultBounds.top - 8f)
            val bottomRight = view.documentToScreen(resultBounds.right + 8f, resultBounds.bottom + 8f)
            val left = minOf(topLeft.first, bottomRight.first).toInt().coerceIn(0, target.width - 1)
            val right = maxOf(topLeft.first, bottomRight.first).toInt().coerceIn(left, target.width - 1)
            val top = minOf(topLeft.second, bottomRight.second).toInt().coerceIn(0, target.height - 1)
            val bottom = maxOf(topLeft.second, bottomRight.second).toInt().coerceIn(top, target.height - 1)
            var changedPixels = 0
            for (y in top..bottom) for (x in left..right) {
                if (baseline.getPixel(x, y) != target.getPixel(x, y)) changedPixels++
            }
            assertTrue("math ink in the live overlay should be removed", changedPixels > 0)
        } finally {
            before?.recycle()
            target.recycle()
        }
    }

    @Test fun `PAD to NOTEBOOK redraws sheet border when the transform stays the same`() {
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            paper = Paper.PLAIN
            paperColors = PaperColors.BLACK
            mode = InkCanvasView.Mode.PAD
        }
        // The page is exactly 2× the View in both dimensions, so PAD and NOTEBOOK fit to the
        // same 0.5 scale and translation. This isolates cache invalidation from matrix changes.
        view.layout(0, 0, 500, 707)
        val beforeTransform = view.documentToScreen(0f, 707f)
        val target = Bitmap.createBitmap(500, 707, Bitmap.Config.ARGB_8888)
        try {
            target.eraseColor(Color.MAGENTA)
            view.draw(Canvas(target))
            val padEdge = (0..4).map { target.getPixel(it, 350) }

            view.mode = InkCanvasView.Mode.NOTEBOOK
            assertEquals(beforeTransform, view.documentToScreen(0f, 707f))
            target.eraseColor(Color.MAGENTA)
            view.draw(Canvas(target))
            val notebookEdge = (0..4).map { target.getPixel(it, 350) }

            assertTrue(
                "switching to NOTEBOOK must repaint the sheet border despite the unchanged matrix: " +
                    "PAD=$padEdge NOTEBOOK=$notebookEdge",
                notebookEdge.indices.any { notebookEdge[it] != padEdge[it] },
            )
        } finally {
            target.recycle()
        }
    }

    @Test fun `every enabled and disabled real pattern output draws on a native canvas`() {
        val patterns = realUserPatterns()
        assertTrue("expected the real user library, got ${patterns.size} patterns", patterns.size > 100)
        val columns = 8
        val cell = 128
        val rows = (patterns.size + columns - 1) / columns
        val bitmap = Bitmap.createBitmap(columns * cell, rows * cell, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            var rendered = 0
            patterns.forEachIndexed { index, pattern ->
                if (pattern.outputInk.isEmpty()) return@forEachIndexed
                val bounds = pattern.outputInk.map { it.bounds }.reduce(Bounds::union)
                val scale = minOf(1f, 88f / maxOf(bounds.width, bounds.height, 1f))
                val save = canvas.save()
                try {
                    canvas.translate((index % columns + 0.5f) * cell, (index / columns + 0.5f) * cell)
                    canvas.scale(scale, scale)
                    canvas.translate(-bounds.centerX, -bounds.centerY)
                    StrokePainter.drawAll(canvas, pattern.outputInk)
                    rendered++
                } catch (failure: Exception) {
                    throw AssertionError("native drawing failed for ${pattern.id} (${pattern.name})", failure)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
            assertEquals("every pattern with output ink should be rendered", patterns.count { it.outputInk.isNotEmpty() }, rendered)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("native Canvas should contain pixels from the vector outputs", pixels.any { it != Color.WHITE })
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun `real enabled pattern library renders equals and calculates two plus three`() {
        val enabled = realUserPatterns().filter { it.enabled }
        val controller = InkController(patterns = { enabled }, ids = IdGenerator.sequential("real-math"))
        val view = InkCanvasView(RuntimeEnvironment.getApplication()).apply {
            this.controller = controller
            paper = Paper.PLAIN
            hapticsEnabled = false
        }
        view.layout(0, 0, 720, 1000)
        val bitmap = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)

        fun insert(id: String, x: Float, y: Float) {
            val pattern = enabled.single { it.id == id }
            assertTrue("real preset $id should insert", controller.insertPattern(pattern, x, y, 40f).isNotEmpty())
            bitmap.eraseColor(Color.MAGENTA)
            view.draw(Canvas(bitmap))
            assertTrue(bitmap.getPixel(1, 1) != Color.MAGENTA)
        }

        try {
            insert("preset-igual", 150f, 180f)
            assertTrue("an isolated equal sign must not autocomplete", controller.mathResults.isEmpty())

            insert("preset-2", 120f, 360f)
            insert("preset-mas", 180f, 360f)
            insert("preset-3", 240f, 360f)
            insert("preset-igual", 300f, 360f)
            assertFalse("the real enabled library should autocomplete 2+3=", controller.mathResults.isEmpty())
            assertTrue(controller.mathResults.all { it.patternId == "preset-5" })
            bitmap.eraseColor(Color.MAGENTA)
            view.draw(Canvas(bitmap))
            assertTrue(bitmap.getPixel(1, 1) != Color.MAGENTA)
        } finally {
            bitmap.recycle()
        }
    }
}
