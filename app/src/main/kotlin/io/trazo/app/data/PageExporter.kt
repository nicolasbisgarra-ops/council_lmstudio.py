package io.trazo.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import io.trazo.app.ai.AiMarkPainter
import io.trazo.app.ink.PaperColors
import io.trazo.app.ink.PaperPainter
import io.trazo.app.ink.StrokePainter
import io.trazo.core.InkStroke
import io.trazo.core.LazyNotebook
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.bounds
import java.io.OutputStream

/**
 * Exportación para compartir, nunca formato fuente: PDF vectorial (los trazos quedan como
 * trayectos) y PNG de una página. El cuaderno editable sigue siendo el `.trazo`.
 */
object PageExporter {
    /** A4 en puntos PDF (1/72"). */
    private const val PDF_WIDTH = 595
    private const val PDF_HEIGHT = 842

    /** @param results tinta de los resultados de las cuentas de cada página (derivada, no está en la página). */
    fun writePdf(notebook: Notebook, out: OutputStream, results: (Page) -> List<InkStroke> = { emptyList() }, includeAi: Boolean = false) =
        writePdf(notebook.pages.size, notebook.pages::get, notebook.paper, out, results, includeAi)

    /** Decodifica las páginas de a una mientras escribe: un cuaderno largo no se carga entero. */
    fun writePdf(notebook: LazyNotebook, out: OutputStream, results: (Page) -> List<InkStroke> = { emptyList() }, includeAi: Boolean = false) =
        writePdf(notebook.pageCount, notebook::page, notebook.paper, out, results, includeAi)

    private fun writePdf(count: Int, pageAt: (Int) -> Page, paper: Paper, out: OutputStream, results: (Page) -> List<InkStroke>, includeAi: Boolean) {
        val doc = PdfDocument()
        try {
            for (i in 0 until count) {
                val page = pageAt(i)
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, i + 1).create())
                drawPage(pdfPage.canvas, page, paper, PDF_WIDTH / Notebook.PAGE_WIDTH, results(page), includeAi)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun writePng(page: Page, paper: Paper, out: OutputStream, scale: Float = 2f, results: List<InkStroke> = emptyList(), includeAi: Boolean = false) {
        val bmp = Bitmap.createBitmap((Notebook.PAGE_WIDTH * scale).toInt(), (Notebook.PAGE_HEIGHT * scale).toInt(), Bitmap.Config.ARGB_8888)
        try {
            drawPage(Canvas(bmp), page, paper, scale, results, includeAi)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    /** Miniatura de [page] de [width] px de ancho (panel de páginas). */
    fun thumbnail(page: Page, paper: Paper, width: Int, colors: PaperColors = PaperColors.LIGHT): Bitmap {
        val scale = width / Notebook.PAGE_WIDTH
        val bmp = Bitmap.createBitmap(width, (Notebook.PAGE_HEIGHT * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        PaperPainter.draw(canvas, paper, colors = colors)
        StrokePainter.drawAll(canvas, page.allInk)
        return bmp
    }

    /** Imagen de unos trazos sueltos (lo copiado con el lazo), sobre blanco y con un margen. */
    fun writeStrokesPng(strokes: List<InkStroke>, out: OutputStream, maxSide: Int = 1600) {
        val b = strokes.bounds()
        val margin = 16f
        val w = b.width + margin * 2
        val h = b.height + margin * 2
        val scale = (maxSide / maxOf(w, h)).coerceAtMost(3f)
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(margin - b.left, margin - b.top)
        StrokePainter.drawAll(canvas, strokes)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    /** Con [includeAi], las marcas del Modo IA van encima, como se ven en la hoja (46). */
    private fun drawPage(canvas: Canvas, page: Page, paper: Paper, scale: Float, results: List<InkStroke>, includeAi: Boolean) {
        canvas.save()
        canvas.scale(scale, scale)
        PaperPainter.draw(canvas, paper)
        StrokePainter.drawAll(canvas, page.allInk)
        StrokePainter.drawInkOnly(canvas, results)
        page.ai?.takeIf { includeAi }?.let { AiMarkPainter.draw(canvas, it.notes, it.verdict(page.strokes)) }
        canvas.restore()
    }
}
