package io.trazo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.trazo.app.data.PageExporter
import io.trazo.core.DefaultPatterns
import io.trazo.core.Brush
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Exportación para compartir con las APIs reales de Android (PdfDocument, Bitmap). */
@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {
    private fun notebook(): Notebook {
        val c = InkController(ids = IdGenerator.sequential())
        c.insertPattern(DefaultPatterns.integral(), 300f, 300f, 200f)
        c.insertPattern(DefaultPatterns.root(), 600f, 700f, 120f)
        return Notebook("n", "Export", listOf(Page("p1", c.page.strokes), Page("p2")), Paper.GRID)
    }

    @Test fun pdfHasOnePagePerNotebookPage() {
        val bytes = ByteArrayOutputStream().also { PageExporter.writePdf(notebook(), it) }.toByteArray()
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF"))
        assertEquals(2, Regex("/Type\\s*/Page[^s]").findAll(text).count())
    }

    @Test fun pngIsAValidImage() {
        val n = notebook()
        val bytes = ByteArrayOutputStream().also { PageExporter.writePng(n.pages[0], n.paper, it, scale = 1f) }.toByteArray()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(Notebook.PAGE_WIDTH.toInt(), bmp.width)
        assertEquals(Notebook.PAGE_HEIGHT.toInt(), bmp.height)
    }

    @Test fun pdfExpandsOnePageToRenderInkAtAllWorkspaceCorners() {
        val page = Page(
            "workspace",
            strokes = listOf(
                mark("top-left", -Notebook.PAGE_WIDTH, -Notebook.PAGE_HEIGHT),
                mark("top-right", 2f * Notebook.PAGE_WIDTH, -Notebook.PAGE_HEIGHT),
                mark("bottom-left", -Notebook.PAGE_WIDTH, 2f * Notebook.PAGE_HEIGHT),
                mark("bottom-right", 2f * Notebook.PAGE_WIDTH, 2f * Notebook.PAGE_HEIGHT),
            ),
        )
        val rendered = renderPdf(Notebook("workspace", "Workspace", listOf(page), Paper.PLAIN))
        try {
            assertEquals("one logical sheet must stay one PDF page", 1, rendered.size)
            val bitmap = rendered.single()
            val pdfScale = 595.0 / Notebook.PAGE_WIDTH
            val expectedWidth = ceil((3.0 * Notebook.PAGE_WIDTH + 20.0) * pdfScale).toInt()
            val expectedHeight = ceil((3.0 * Notebook.PAGE_HEIGHT + 20.0) * pdfScale).toInt()
            assertEquals(expectedWidth, bitmap.width)
            assertEquals(expectedHeight, bitmap.height)

            val padding = 10.0 * pdfScale
            val right = (3.0 * Notebook.PAGE_WIDTH + 10.0) * pdfScale
            val bottom = (3.0 * Notebook.PAGE_HEIGHT + 10.0) * pdfScale
            assertRedNear(bitmap, padding.roundToInt(), padding.roundToInt())
            assertRedNear(bitmap, right.roundToInt(), padding.roundToInt())
            assertRedNear(bitmap, padding.roundToInt(), bottom.roundToInt())
            assertRedNear(bitmap, right.roundToInt(), bottom.roundToInt())
        } finally {
            rendered.forEach { it.recycle() }
        }
    }

    private fun renderPdf(notebook: Notebook): List<Bitmap> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("trazo-workspace-", ".pdf", context.cacheDir)
        try {
            FileOutputStream(file).use { PageExporter.writePdf(notebook, it) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val renderer = PdfRenderer(descriptor)
                try {
                    return (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { pdfPage ->
                            Bitmap.createBitmap(pdfPage.width, pdfPage.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(Color.WHITE)
                                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun mark(id: String, x: Float, y: Float) = InkStroke(
        id = id,
        points = listOf(InkPoint(x, y)),
        color = Color.RED,
        width = 10f,
        brush = Brush.BALLPOINT,
    )

    private fun assertRedNear(bitmap: Bitmap, x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val px = x + dx
            val py = y + dy
            if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                val color = bitmap.getPixel(px, py)
                if (Color.red(color) > 200 && Color.green(color) < 50 && Color.blue(color) < 50) return
            }
        }
        throw AssertionError("PDF has no red workspace mark near ($x,$y) in ${bitmap.width}x${bitmap.height}")
    }
}
