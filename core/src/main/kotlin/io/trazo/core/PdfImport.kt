package io.trazo.core

import io.trazo.core.Notebook.Companion.PAGE_HEIGHT
import io.trazo.core.Notebook.Companion.PAGE_WIDTH

/**
 * Anotar sobre un PDF: cada hoja del PDF, ya convertida en imagen, pasa a ser el fondo fijo ([PageImage.locked])
 * de una hoja del cuaderno, encajada entera y centrada; se escribe encima como en cualquier hoja.
 */
object PdfImport {
    /** Tope de hojas por importación (más que esto no entra cómodo en memoria ni en el disco del teléfono). */
    const val MAX_PAGES = 200

    /** Una hoja del PDF ya guardada como imagen: [asset] y su tamaño en píxeles. */
    data class RenderedPage(val asset: String, val width: Int, val height: Int)

    /** Rectángulo de la hoja donde va el fondo de [width]×[height]: lo más grande posible sin recortar, centrado. */
    fun fit(width: Int, height: Int): Bounds {
        require(width > 0 && height > 0) { "Tamaño inválido: $width×$height" }
        val scale = minOf(PAGE_WIDTH / width, PAGE_HEIGHT / height)
        val w = width * scale
        val h = height * scale
        val left = (PAGE_WIDTH - w) / 2f
        val top = (PAGE_HEIGHT - h) / 2f
        return Bounds(left, top, left + w, top + h)
    }

    /** Hoja con el fondo fijo. */
    fun page(rendered: RenderedPage, ids: IdGenerator = IdGenerator.RANDOM): Page {
        val b = fit(rendered.width, rendered.height)
        val image = PageImage(ids.next(), rendered.asset, b.left, b.top, b.right, b.bottom, locked = true)
        return Page(ids.next(), images = listOf(image))
    }

    /** Cuaderno nuevo, en hoja lisa, con una hoja por página del PDF (como mucho [MAX_PAGES]). */
    fun notebook(
        name: String,
        pages: List<RenderedPage>,
        ids: IdGenerator = IdGenerator.RANDOM,
        now: Long = System.currentTimeMillis(),
    ): Notebook {
        require(pages.isNotEmpty()) { "El PDF no tiene hojas" }
        val sheets = pages.take(MAX_PAGES).map { page(it, ids) }
        return Notebook(ids.next(), name.ifBlank { "PDF" }, sheets, paper = Paper.PLAIN, createdAt = now, updatedAt = now)
    }

    /** Nombre del cuaderno a partir del nombre del archivo: sin «.pdf» ni guiones bajos. */
    fun nameFor(fileName: String?): String =
        fileName.orEmpty().substringAfterLast('/').let { if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it }.replace('_', ' ').trim().ifEmpty { "PDF" }
}
