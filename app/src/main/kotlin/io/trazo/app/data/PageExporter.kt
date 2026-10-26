package io.trazo.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import io.trazo.app.ink.PaperPainter
import io.trazo.app.ink.StrokePainter
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import java.io.OutputStream

/**
 * Exportación para compartir, nunca formato fuente: PDF vectorial (los trazos quedan como
 * trayectos) y PNG de una página. El cuaderno editable sigue siendo el `.trazo`.
 */
object PageExporter {
    /** A4 en puntos PDF (1/72"). */
    private const val PDF_WIDTH = 595
    private const val PDF_HEIGHT = 842

    fun writePdf(notebook: Notebook, out: OutputStream) {
        val doc = PdfDocument()
        try {
            notebook.pages.forEachIndexed { i, page ->
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, i + 1).create())
                drawPage(pdfPage.canvas, page, notebook.paper, PDF_WIDTH / Notebook.PAGE_WIDTH)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun writePng(page: Page, paper: Paper, out: OutputStream, scale: Float = 2f) {
        val bmp = Bitmap.createBitmap((Notebook.PAGE_WIDTH * scale).toInt(), (Notebook.PAGE_HEIGHT * scale).toInt(), Bitmap.Config.ARGB_8888)
        try {
            drawPage(Canvas(bmp), page, paper, scale)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    private fun drawPage(canvas: Canvas, page: Page, paper: Paper, scale: Float) {
        canvas.save()
        canvas.scale(scale, scale)
        PaperPainter.draw(canvas, paper)
        page.strokes.forEach { StrokePainter.draw(canvas, it) }
        canvas.restore()
    }
}
