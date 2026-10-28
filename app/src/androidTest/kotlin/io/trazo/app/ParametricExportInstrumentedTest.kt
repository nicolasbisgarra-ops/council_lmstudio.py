package io.trazo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.trazo.app.data.PageExporter
import io.trazo.core.GeoKind
import io.trazo.core.GeoPoint
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.ParametricGeometry
import io.trazo.core.ParametricTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Las formas paramétricas se pintan de verdad al exportar: cada imagen se compara con una
 * página vacía del mismo pintor, así el fondo no se confunde con tinta (propuesta de ChatGPT · 30).
 */
@RunWith(AndroidJUnit4::class)
class ParametricExportInstrumentedTest {
    private val kinds = listOf(GeoKind.CIRCLE, GeoKind.RECTANGLE, GeoKind.TRIANGLE, GeoKind.CUBOID)

    private fun shapePage(kind: GeoKind, suffix: String): Page {
        val shape = ParametricTemplates.create("shape-$suffix", kind, GeoPoint(300f, 420f), 180f)
        return Page("page-$suffix", emptyList(), listOf(shape))
    }

    private fun png(page: Page): Bitmap {
        val bytes = ByteArrayOutputStream().also { PageExporter.writePng(page, Paper.PLAIN, it, scale = 1f) }.toByteArray()
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "PNG inválido" }
    }

    private fun changedPixels(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val left = IntArray(a.width)
        val right = IntArray(b.width)
        var changed = 0
        for (y in 0 until a.height) {
            a.getPixels(left, 0, a.width, 0, y, a.width, 1)
            b.getPixels(right, 0, b.width, 0, y, b.width, 1)
            for (x in left.indices) if (left[x] != right[x]) changed++
        }
        return changed
    }

    private fun pdfRenders(notebook: Notebook): List<Bitmap> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("trazo-parametric-", ".pdf", ctx.cacheDir)
        try {
            FileOutputStream(file).use { PageExporter.writePdf(notebook, it) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val renderer = PdfRenderer(descriptor)
                try {
                    return (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

    @Test fun fourKindsActuallyRenderIntoPng() {
        val blank = png(Page("blank"))
        try {
            for (kind in kinds) {
                val actual = png(shapePage(kind, kind.name))
                try {
                    assertTrue("PNG no contiene $kind", changedPixels(actual, blank) > 40)
                } finally {
                    actual.recycle()
                }
            }
        } finally {
            blank.recycle()
        }
    }

    @Test fun editingDimensionsChangesPngWithoutConvertingToStoredStrokes() {
        val original = ParametricTemplates.create("editable", GeoKind.RECTANGLE, GeoPoint(300f, 420f), 180f)
        val edited = ParametricGeometry.setDimension(ParametricGeometry.setDimension(original, "w", 240f), "h", 95f)
        val editedPage = Page("b", emptyList(), listOf(edited))
        val a = png(Page("a", emptyList(), listOf(original)))
        val b = png(editedPage)
        try {
            assertTrue("la edición no modifica el PNG", changedPixels(a, b) > 40)
            assertTrue("la forma no debe guardarse como trazos", editedPage.strokes.isEmpty())
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    @Test fun pdfRendersShapesOnBothPagesAndKeepsPageCount() {
        val filled = Notebook(
            "filled", "QA",
            listOf(shapePage(GeoKind.CIRCLE, "circle"), shapePage(GeoKind.CUBOID, "cuboid")),
            Paper.PLAIN,
        )
        val empty = Notebook("empty", "QA", listOf(Page("blank-1"), Page("blank-2")), Paper.PLAIN)
        val actual = pdfRenders(filled)
        val baseline = pdfRenders(empty)
        try {
            assertEquals(2, actual.size)
            assertEquals(2, baseline.size)
            for (i in 0..1) {
                assertTrue("PDF página ${i + 1} no contiene forma", changedPixels(actual[i], baseline[i]) > 40)
            }
        } finally {
            (actual + baseline).forEach { it.recycle() }
        }
    }
}
