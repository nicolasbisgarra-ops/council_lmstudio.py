package io.trazo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.trazo.app.data.PageExporter
import io.trazo.core.DefaultPatterns
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

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
}
