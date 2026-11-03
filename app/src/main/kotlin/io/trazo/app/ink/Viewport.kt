package io.trazo.app.ink

import io.trazo.core.Notebook
import kotlin.math.min

/** Bounds of the drawable document. The notebook page remains at [0, pageWidth] × [0, pageHeight]. */
data class CanvasBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    fun clampX(x: Float) = x.coerceIn(left, right)
    fun clampY(y: Float) = y.coerceIn(top, bottom)

    companion object {
        /** One page of writing space on every side of the original page. */
        fun aroundPage(pageWidth: Float, pageHeight: Float) =
            CanvasBounds(-pageWidth, -pageHeight, pageWidth * 2f, pageHeight * 2f)
    }
}

/** Session view transform. Remembering the last size also covers Android View recreation. */
class Viewport {
    var scale = 0f
    var tx = 0f
    var ty = 0f
    private var canvasWidth = 0
    private var canvasHeight = 0
    val isSet get() = scale.isFinite() && scale > 0f

    fun rememberSize(width: Int, height: Int) {
        if (width > 0 && height > 0) { canvasWidth = width; canvasHeight = height }
    }

    /** Minimum zoom at which the full 3×3 writing area fits in the view. */
    fun workspaceFit(width: Int, height: Int, bounds: CanvasBounds): Float =
        if (width <= 0 || height <= 0 || bounds.width <= 0f || bounds.height <= 0f) 0f
        else min(width / bounds.width, height / bounds.height)

    /** Preserve page-relative zoom and reading position through rotation; the first layout fits the central page. */
    fun resizeNotebook(
        width: Int,
        height: Int,
        pageWidth: Float,
        pageHeight: Float = Notebook.PAGE_HEIGHT,
        initialPageScale: Float = width / pageWidth,
    ) {
        if (width <= 0 || height <= 0 || !pageWidth.isFinite() || pageWidth <= 0f ||
            !pageHeight.isFinite() || pageHeight <= 0f
        ) return
        val bounds = CanvasBounds.aroundPage(pageWidth, pageHeight)
        val minScale = workspaceFit(width, height, bounds)
        val pageFit = width / pageWidth
        val hadPreviousSize = canvasWidth > 0 && canvasHeight > 0
        if (!isSet || !hadPreviousSize) {
            scale = initialPageScale
            tx = 0f
            ty = 0f
        } else if (width != canvasWidth || height != canvasHeight) {
            // Preserve the 0.2.4 zoom percentage relative to the central page's width fit.
            val oldPageFit = canvasWidth / pageWidth
            val zoom = if (oldPageFit > 0f) scale / oldPageFit else 1f
            val center = (canvasWidth / 2f - tx) / scale
            val top = -ty / scale
            scale = (pageFit * zoom).coerceIn(minScale, pageFit * 8f)
            tx = width / 2f - center * scale
            ty = -top * scale
        }
        if (!scale.isFinite() || scale <= 0f) scale = initialPageScale
        scale = scale.coerceIn(minScale, pageFit * 8f)
        if (!tx.isFinite()) tx = 0f
        if (!ty.isFinite()) ty = 0f
        rememberSize(width, height)
        // The first layout and "Fit page" keep the original sheet at (0, 0). Once the user has
        // navigated, rotations clamp the restored viewport to the finite workspace.
        if (hadPreviousSize) clampToCanvas(width, height, bounds)
    }

    /** Keep the complete view inside the writing area, centering it when the area is smaller. */
    fun clampToCanvas(width: Int, height: Int, bounds: CanvasBounds) {
        if (width <= 0 || height <= 0 || !isSet || bounds.width <= 0f || bounds.height <= 0f) return
        tx = clampAxis(tx, width, bounds.left, bounds.right)
        ty = clampAxis(ty, height, bounds.top, bounds.bottom)
    }

    private fun clampAxis(translation: Float, viewportExtent: Int, start: Float, end: Float): Float {
        val drawnExtent = (end - start) * scale
        if (drawnExtent <= viewportExtent) return (viewportExtent - (start + end) * scale) / 2f
        return translation.coerceIn(viewportExtent - end * scale, -start * scale)
    }
}
