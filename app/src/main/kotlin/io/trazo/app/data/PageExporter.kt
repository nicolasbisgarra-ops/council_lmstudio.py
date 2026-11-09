package io.trazo.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import io.trazo.app.ai.AiMarkPainter
import io.trazo.app.image.ImagePainter
import io.trazo.app.ink.PaperColors
import io.trazo.app.ink.PaperPainter
import io.trazo.app.ink.StrokePainter
import io.trazo.core.AiNotes
import io.trazo.core.Bounds
import io.trazo.core.Brush
import io.trazo.core.HandIn
import io.trazo.core.InkStroke
import io.trazo.core.LazyNotebook
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.bounds
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Exportación para compartir, nunca formato fuente: PDF vectorial (los trazos quedan como
 * trayectos) y PNG. Cada hoja incluye la tinta que quedó fuera de sus límites.
 */
object PageExporter {
    /** A4 en puntos PDF (1/72"). */
    private const val PDF_WIDTH = 595

    /** Límite de memoria para una imagen raster exportada. */
    private const val MAX_RASTER_PIXELS = 16_000_000L
    private const val MAX_RASTER_DIMENSION = 8_192

    /** @param results tinta de los resultados de las cuentas de cada página (derivada, no está en la página). */
    /** @param clean PDF para entregar ([HandIn]): hoja blanca, sin marcas IA ni hojas vacías, con pie. */
    fun writePdf(
        notebook: Notebook,
        out: OutputStream,
        results: (Page) -> List<InkStroke> = { emptyList() },
        includeAi: Boolean = false,
        clean: Boolean = false,
    ) = writePdf(notebook.pages.size, notebook.pages::get, notebook.paper, out, results, includeAi, clean, notebook.name)

    /** Decodifica las páginas de a una mientras escribe: un cuaderno largo no se carga entero. */
    fun writePdf(
        notebook: LazyNotebook,
        out: OutputStream,
        results: (Page) -> List<InkStroke> = { emptyList() },
        includeAi: Boolean = false,
        clean: Boolean = false,
    ) = writePdf(notebook.pageCount, notebook::page, notebook.paper, out, results, includeAi, clean, notebook.name)

    private fun writePdf(
        count: Int,
        pageAt: (Int) -> Page,
        paper: Paper,
        out: OutputStream,
        results: (Page) -> List<InkStroke>,
        includeAi: Boolean,
        clean: Boolean,
        name: String,
    ) {
        val withAi = includeAi && !clean
        val indices = if (clean) HandIn.pagesToInclude(count, pageAt, results) else (0 until count).toList()
        val doc = PdfDocument()
        try {
            indices.forEachIndexed { n, i ->
                val page = pageAt(i)
                val mathResults = results(page)
                val bounds = exportBounds(page, mathResults, withAi)
                // La hoja mantiene su tamaño lógico A4. Cuando hay tinta fuera del papel, el
                // medio PDF crece para representarla completa sin escalar ni recortar la hoja.
                val scale = PDF_WIDTH.toDouble() / Notebook.PAGE_WIDTH
                val width = checkedDimension(ceil(bounds.width.toDouble() * scale), "PDF width")
                val height = checkedDimension(ceil(bounds.height.toDouble() * scale), "PDF height")
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(width, height, n + 1).create())
                drawPage(pdfPage.canvas, page, if (clean) Paper.PLAIN else paper, bounds, scale.toFloat(), mathResults, withAi)
                if (clean) drawFooter(pdfPage.canvas, HandIn.footer(name, n + 1, indices.size), width.toFloat(), height.toFloat())
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun writePng(
        page: Page,
        paper: Paper,
        out: OutputStream,
        scale: Float = 2f,
        results: List<InkStroke> = emptyList(),
        includeAi: Boolean = false,
    ) {
        require(scale.isFinite() && scale > 0f) { "scale must be finite and greater than zero" }
        val bounds = exportBounds(page, results, includeAi)
        val raster = rasterSize(bounds, scale.toDouble())
        val bmp = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
        try {
            // Fuera de la hoja se ve el escritorio. Los PNG compartidos conservan el fondo blanco
            // que tenían antes de incluir tinta exterior.
            bmp.eraseColor(Color.WHITE)
            drawPage(Canvas(bmp), page, paper, bounds, raster.scale.toFloat(), results, includeAi)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    /** Miniatura que mantiene toda la tinta visible, también cuando sale de la hoja. */
    fun thumbnail(
        page: Page,
        paper: Paper,
        width: Int,
        colors: PaperColors = PaperColors.LIGHT,
        results: List<InkStroke> = emptyList(),
        includeAi: Boolean = false,
    ): Bitmap {
        require(width > 0) { "width must be greater than zero" }
        val bounds = exportBounds(page, results, includeAi)
        // `width` is the bitmap width contract used by thumbnail consumers and their caches.
        val scale = width.toDouble() / bounds.width
        val bitmapWidth = width
        val bitmapHeight = checkedDimension(ceil(bounds.height.toDouble() * scale), "thumbnail height")
        val bmp = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(colors.desk)
            val canvas = Canvas(bmp)
            drawPage(canvas, page, paper, bounds, scale.toFloat(), results, includeAi, colors)
            return bmp
        } catch (t: Throwable) {
            bmp.recycle()
            throw t
        }
    }

    /** Imagen de unos trazos sueltos (lo copiado con el lazo), sobre blanco y con un margen. */
    fun writeStrokesPng(strokes: List<InkStroke>, out: OutputStream, maxSide: Int = 1600) {
        val b = strokes.bounds()
        // Los límites son del eje del trazo: se suma medio grosor para que el borde no quede cortado.
        val margin = 16f + (strokes.maxOfOrNull { it.width } ?: 0f) / 2f
        val w = b.width + margin * 2
        val h = b.height + margin * 2
        val scale = (maxSide / maxOf(w, h)).coerceAtMost(3f)
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            canvas.translate(margin - b.left, margin - b.top)
            StrokePainter.drawAll(canvas, strokes)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Une la hoja central con la tinta visible y deja sitio al grosor real de cada trazo.
     * PaperPainter sigue pintando únicamente el rectángulo central del papel.
     */
    private fun exportBounds(page: Page, results: List<InkStroke>, includeAi: Boolean): Bounds {
        var bounds = Bounds(0f, 0f, Notebook.PAGE_WIDTH, Notebook.PAGE_HEIGHT)

        fun include(candidate: Bounds) {
            require(candidate.isFinite()) { "export bounds must be finite" }
            bounds = bounds.union(candidate)
        }

        fun includeStrokes(strokes: List<InkStroke>) {
            for (stroke in strokes) {
                require(stroke.width.isFinite() && stroke.width >= 0f) { "stroke width must be finite and non-negative" }
                val padding = when (stroke.brush) {
                    // These layer bounds are used by StrokePainter while drawing the stroke.
                    Brush.PENCIL -> stroke.width * 2f
                    else -> stroke.width
                }
                require(padding.isFinite()) { "stroke padding must be finite" }
                include(stroke.bounds.inflate(padding))
            }
        }

        page.images.forEach { include(it.bounds) }
        includeStrokes(page.allInk)
        includeStrokes(results)
        if (includeAi) includeAiBounds(page)?.let(::include)
        require(bounds.isFinite() && bounds.width.isFinite() && bounds.height.isFinite()) { "export bounds must be finite" }
        require(bounds.width > 0f && bounds.height > 0f) { "export bounds must have positive dimensions" }
        return bounds
    }

    private fun includeAiBounds(page: Page): Bounds? {
        val ai = page.ai ?: return null
        var combined: Bounds? = null
        fun include(candidate: Bounds) {
            require(candidate.isFinite()) { "AI export bounds must be finite" }
            combined = combined?.union(candidate) ?: candidate
        }

        val placements = AiNotes.layout(ai.notes)
        for (placed in placements) {
            include(placed.ring.inflate(2.2f))
            include(placed.label.inflate(2.2f))
        }
        val verdict = ai.verdict(page.strokes)
        val area = verdict?.area
        if (area != null) {
            require(area.isFinite()) { "AI verdict bounds must be finite" }
            val scoreText = verdict.score?.let { "${AiMarkPainter.formatScore(it)}/10" }
            // tick() reaches x+28; a score starts at x+32 and uses a 17-unit type size.
            val right = area.right + 42f + (scoreText?.length ?: 0) * 17f
            include(Bounds(area.right + 8f, area.top - 4f, right, area.top + 32f))
        }
        return combined
    }

    private data class RasterSize(val width: Int, val height: Int, val scale: Double)

    private fun rasterSize(bounds: Bounds, requestedScale: Double): RasterSize {
        require(requestedScale.isFinite() && requestedScale > 0.0) { "scale must be finite and greater than zero" }
        val docWidth = bounds.width.toDouble()
        val docHeight = bounds.height.toDouble()
        require(docWidth.isFinite() && docHeight.isFinite() && docWidth > 0.0 && docHeight > 0.0) {
            "export bounds must have finite positive dimensions"
        }
        var scale = minOf(
            requestedScale,
            MAX_RASTER_DIMENSION.toDouble() / docWidth,
            MAX_RASTER_DIMENSION.toDouble() / docHeight,
            sqrt(MAX_RASTER_PIXELS.toDouble() / (docWidth * docHeight)),
        )
        while (true) {
            val width = checkedDimension(ceil(docWidth * scale), "PNG width")
            val height = checkedDimension(ceil(docHeight * scale), "PNG height")
            val pixelCount = width.toLong() * height.toLong()
            if (width <= MAX_RASTER_DIMENSION && height <= MAX_RASTER_DIMENSION && pixelCount <= MAX_RASTER_PIXELS) {
                return RasterSize(width, height, scale)
            }
            // Ceil can add a pixel past a continuous limit. Correct from the actual integer size,
            // then back off slightly so floating point rounding cannot leave it over the cap.
            var correction = 1.0
            if (width > MAX_RASTER_DIMENSION) correction = minOf(correction, MAX_RASTER_DIMENSION.toDouble() / width)
            if (height > MAX_RASTER_DIMENSION) correction = minOf(correction, MAX_RASTER_DIMENSION.toDouble() / height)
            if (pixelCount > MAX_RASTER_PIXELS) correction = minOf(correction, sqrt(MAX_RASTER_PIXELS.toDouble() / pixelCount))
            scale *= correction * 0.999999
            require(scale > 0.0 && scale.isFinite()) { "unable to fit export within raster limits" }
        }
    }

    private fun checkedDimension(value: Double, name: String): Int {
        require(value.isFinite() && value >= 1.0 && value <= Int.MAX_VALUE.toDouble()) { "$name is outside supported dimensions" }
        return value.toInt()
    }

    private fun Bounds.isFinite() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    /** Pie gris centrado al borde inferior, en puntos PDF (fuera de la escala de la hoja). */
    private fun drawFooter(canvas: Canvas, text: String, width: Float, height: Float) {
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, width / 2f, height - 14f, paint)
    }

    /** Con [includeAi], las marcas del Modo IA van encima, como se ven en la hoja (46). */
    private fun drawPage(
        canvas: Canvas,
        page: Page,
        paper: Paper,
        bounds: Bounds,
        scale: Float,
        results: List<InkStroke>,
        includeAi: Boolean,
        colors: PaperColors = PaperColors.LIGHT,
    ) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)
        PaperPainter.draw(canvas, paper, colors = colors)
        if (page.images.isNotEmpty()) ImagePainter.draw(canvas, page.images, colors.dark)
        StrokePainter.drawAll(canvas, page.allInk, colors.dark)
        StrokePainter.drawInkOnly(canvas, results, colors.dark)
        page.ai?.takeIf { includeAi }?.let { AiMarkPainter.draw(canvas, it.notes, it.verdict(page.strokes)) }
        canvas.restore()
    }
}
