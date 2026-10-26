package io.trazo.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.Notebook
import io.trazo.core.Paper
import io.trazo.core.PointerKind
import kotlin.math.max
import kotlin.math.min

/** Zoom/desplazamiento de la hoja; vive en el ViewModel para sobrevivir a rotaciones. */
class Viewport {
    var scale = 0f
    var tx = 0f
    var ty = 0f
    val isSet get() = scale > 0f
}

/**
 * Superficie de tinta nativa (sin WebView). Traduce `MotionEvent` al [InkController]:
 *
 *  - Lápiz (`TOOL_TYPE_STYLUS`) escribe; su extremo goma (`TOOL_TYPE_ERASER`) o el botón borra.
 *  - Se consumen las muestras históricas de cada MOVE (S Pen reporta más rápido que el refresco).
 *  - Dedo: desplaza y hace zoom (dos dedos). Mientras el lápiz está apoyado o cerca, el dedo/palma
 *    se ignora. `ACTION_CANCEL` y `FLAG_CANCELED` descartan el trazo y nunca convierten.
 *  - La pausa de 1 s se comprueba con ticks en cada frame (`postOnAnimation`), nunca con sleep.
 *  - Tinta seca en un bitmap caché; tinta húmeda e indicador encima.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode {
        /** Hoja de cuaderno: dedo navega, lápiz escribe. */
        NOTEBOOK,
        /** Lienzo pequeño del editor de patrones: encaja fijo y el dedo también dibuja. */
        PAD,
    }

    private val density = resources.displayMetrics.density

    var mode: Mode = Mode.NOTEBOOK
        set(value) { field = value; fitIfNeeded(force = true) }

    var controller: InkController? = null
        set(value) {
            if (field === value) return
            field?.removeListener(listener)
            field = value
            value?.addListener(listener)
            invalidateContent()
        }

    var paper: Paper = Paper.LINES
        set(value) { if (field != value) { field = value; invalidateContent() } }

    var fingerDraws = false
    var showHoldIndicator = true
    /** Vibración breve al reemplazar un gesto por su tinta. */
    var hapticsEnabled = true
    var darkPaper = false
        set(value) { if (field != value) { field = value; invalidateContent() } }

    var pageWidth = Notebook.PAGE_WIDTH
        set(value) { field = value; fitIfNeeded(force = true) }
    var pageHeight = Notebook.PAGE_HEIGHT
        set(value) { field = value; fitIfNeeded(force = true) }

    var viewport: Viewport = Viewport()
        set(value) { field = value; fitIfNeeded(force = !value.isSet) ; applyViewport() }

    /** Px de pantalla por unidad de documento (zoom actual). */
    val pxPerUnit: Float get() = viewport.scale.takeIf { it > 0f } ?: 1f

    private val docToScreen = Matrix()
    private val screenToDoc = Matrix()
    private val tmp = FloatArray(2)

    // Estado de entrada
    private var drawingPointerId = INVALID
    private var drawingKind = PointerKind.STYLUS
    private var stylusNearUntil = 0L
    private var navigating = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    // Render
    private var cache: Bitmap? = null
    private var cacheValid = false
    private var cacheVersion = -1L
    private val cacheMatrixValues = FloatArray(9)
    private val currentMatrixValues = FloatArray(9)
    private val deskColor get() = if (darkPaper) Color.rgb(0x14, 0x16, 0x15) else Color.rgb(0xE6, 0xE3, 0xDA)
    private val accent = Color.rgb(0x3F, 0x7D, 0x6B)
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3f * density; color = accent
    }
    private val holdTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = Color.argb(60, 0x3F, 0x7D, 0x6B)
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = accent
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val shadowPaint = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private val lassoPath = Path()
    private val arcRect = RectF()

    private val listener: (InkEvent) -> Unit = { e ->
        when (e) {
            InkEvent.PageChanged -> invalidateContent()
            is InkEvent.Converted -> {
                if (hapticsEnabled) {
                    performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                        else HapticFeedbackConstants.VIRTUAL_KEY,
                    )
                }
                invalidate()
            }
            else -> invalidate()
        }
    }

    /** Contacto de dedo más ancho que esto (en px) se trata como palma. */
    private val palmTouchMajorPx = 20f * resources.displayMetrics.xdpi / 25.4f

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller ?: return
            c.tick(SystemClock.uptimeMillis())
            invalidate()
            if (c.isContactActive) postOnAnimation(this)
        }
    }

    init {
        isFocusable = true
        tag = TAG
        contentDescription = "Hoja de tinta"
    }

    fun invalidateContent() {
        cacheValid = false
        invalidate()
    }

    // ------------------------------------------------------------------ coordenadas

    fun screenToDocument(x: Float, y: Float): Pair<Float, Float> {
        tmp[0] = x; tmp[1] = y; screenToDoc.mapPoints(tmp); return tmp[0] to tmp[1]
    }

    fun documentToScreen(x: Float, y: Float): Pair<Float, Float> {
        tmp[0] = x; tmp[1] = y; docToScreen.mapPoints(tmp); return tmp[0] to tmp[1]
    }

    private fun fitScale(): Float =
        if (width == 0 || height == 0) 0f
        else if (mode == Mode.PAD) min(width / pageWidth, height / pageHeight)
        else width / pageWidth

    /** Vuelve a encajar la hoja al ancho de la vista (botón "Ajustar"). */
    fun fitToPage() = fitIfNeeded(force = true)

    private fun fitIfNeeded(force: Boolean) {
        if (width == 0 || height == 0) return
        if (force || !viewport.isSet || mode == Mode.PAD) {
            val s = fitScale()
            viewport.scale = s
            viewport.tx = (width - pageWidth * s) / 2f
            viewport.ty = if (mode == Mode.PAD) (height - pageHeight * s) / 2f else 0f
        }
        applyViewport()
    }

    private fun applyViewport() {
        docToScreen.reset()
        docToScreen.postScale(viewport.scale.takeIf { it > 0f } ?: 1f, viewport.scale.takeIf { it > 0f } ?: 1f)
        docToScreen.postTranslate(viewport.tx, viewport.ty)
        docToScreen.invert(screenToDoc)
        invalidate()
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (mode == Mode.PAD) return
        val fit = fitScale()
        val newScale = (viewport.scale * factor).coerceIn(fit * 0.5f, fit * 8f)
        val f = newScale / viewport.scale
        viewport.tx = fx - (fx - viewport.tx) * f
        viewport.ty = fy - (fy - viewport.ty) * f
        viewport.scale = newScale
        clampPan()
        applyViewport()
    }

    private fun panBy(dx: Float, dy: Float) {
        if (mode == Mode.PAD) return
        viewport.tx += dx
        viewport.ty += dy
        clampPan()
        applyViewport()
    }

    /** Deja siempre visible al menos un tercio de la hoja. */
    private fun clampPan() {
        val w = pageWidth * viewport.scale
        val h = pageHeight * viewport.scale
        viewport.tx = viewport.tx.coerceIn(-w + width / 3f, width - width / 3f)
        viewport.ty = viewport.ty.coerceIn(-h + height / 3f, height - height / 3f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache?.recycle(); cache = null
        fitIfNeeded(force = mode == Mode.PAD || !viewport.isSet)
        if (mode == Mode.NOTEBOOK && viewport.isSet) clampPan()
        invalidateContent()
    }

    override fun onDetachedFromWindow() {
        // Rotación / cierre a mitad de un trazo: cancelar, nunca convertir.
        if (drawingPointerId != INVALID) { controller?.cancel(); drawingPointerId = INVALID }
        removeCallbacks(ticker)
        cache?.recycle(); cache = null
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ entrada

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isStylus(event.getToolType(0))) stylusNearUntil = SystemClock.uptimeMillis() + PALM_WINDOW_MS
        return super.onHoverEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = controller ?: return false
        val action = event.actionMasked
        val index = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val tool = event.getToolType(index)
                val kind = kindOf(event, index)
                val canDraw = isStylus(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE ||
                    (tool == MotionEvent.TOOL_TYPE_FINGER && (fingerDraws || mode == Mode.PAD))
                if (isStylus(tool)) {
                    stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                    if (navigating) navigating = false
                }
                // Palma: dedo mientras el lápiz está apoyado o cerca, o un contacto demasiado ancho.
                val palmBlocked = tool == MotionEvent.TOOL_TYPE_FINGER &&
                    (drawingKind != PointerKind.FINGER && drawingPointerId != INVALID ||
                        event.eventTime < stylusNearUntil ||
                        event.getTouchMajor(index) > palmTouchMajorPx)
                if (canDraw && drawingPointerId == INVALID && !navigating && !palmBlocked) {
                    if (action == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requestUnbufferedDispatch(event)
                    }
                    drawingPointerId = event.getPointerId(index)
                    drawingKind = kind
                    val (x, y) = screenToDocument(event.getX(index), event.getY(index))
                    c.penDown(x, y, event.getPressure(index), event.eventTime, kind, pxPerUnit)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(ticker)
                    postOnAnimation(ticker)
                } else if (tool == MotionEvent.TOOL_TYPE_FINGER && !palmBlocked && drawingPointerId == INVALID) {
                    navigating = true
                    navigate(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawingPointerId != INVALID) {
                    val i = event.findPointerIndex(drawingPointerId)
                    if (i >= 0) {
                        for (h in 0 until event.historySize) {
                            val (x, y) = screenToDocument(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                            c.penMove(x, y, event.getHistoricalPressure(i, h), event.getHistoricalEventTime(h))
                        }
                        val (x, y) = screenToDocument(event.getX(i), event.getY(i))
                        c.penMove(x, y, event.getPressure(i), event.eventTime)
                        if (drawingKind != PointerKind.FINGER) stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                    }
                } else if (navigating) {
                    navigate(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (drawingPointerId != INVALID && event.getPointerId(index) == drawingPointerId) {
                    if (isCanceled(event)) {
                        c.cancel()
                    } else {
                        val (x, y) = screenToDocument(event.getX(index), event.getY(index))
                        c.penUp(x, y, event.getPressure(index), event.eventTime)
                    }
                    drawingPointerId = INVALID
                    if (drawingKind != PointerKind.FINGER) stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                } else if (navigating) {
                    navigate(event)
                }
                if (action == MotionEvent.ACTION_UP) navigating = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawingPointerId != INVALID) c.cancel()
                drawingPointerId = INVALID
                navigating = false
            }
        }
        invalidate()
        return true
    }

    private fun navigate(event: MotionEvent) {
        if (mode == Mode.PAD) return
        scaleDetector.onTouchEvent(event)
        val action = event.actionMasked
        val skip = if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) event.actionIndex else -1
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip || event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            sx += event.getX(i); sy += event.getY(i); n++
        }
        if (n == 0) return
        val fx = sx / n
        val fy = sy / n
        if (action == MotionEvent.ACTION_MOVE) panBy(fx - lastFocusX, fy - lastFocusY)
        lastFocusX = fx; lastFocusY = fy
    }

    private fun kindOf(event: MotionEvent, index: Int): PointerKind = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.STYLUS_ERASER
        MotionEvent.TOOL_TYPE_STYLUS ->
            if ((event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) PointerKind.STYLUS_ERASER else PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.FINGER
    }

    private fun isStylus(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    /** `FLAG_CANCELED` (API 33): el sistema marcó el contacto como accidental (palma). */
    private fun isCanceled(event: MotionEvent) =
        Build.VERSION.SDK_INT >= 33 && (event.flags and FLAG_CANCELED) != 0

    // ------------------------------------------------------------------ dibujo

    override fun onDraw(canvas: Canvas) {
        val c = controller
        canvas.drawColor(deskColor)
        drawDryLayer(canvas)
        if (c == null) return

        canvas.save()
        canvas.concat(docToScreen)
        c.inProgress?.let { StrokePainter.draw(canvas, it) }
        val lasso = c.lassoPath
        if (lasso.size > 1) {
            lassoPath.rewind()
            lassoPath.moveTo(lasso[0].x, lasso[0].y)
            for (i in 1 until lasso.size) lassoPath.lineTo(lasso[i].x, lasso[i].y)
            lassoPaint.strokeWidth = 1.5f * density / pxPerUnit
            canvas.drawPath(lassoPath, lassoPaint)
        }
        c.selectionBounds?.let { b ->
            lassoPaint.strokeWidth = 1.5f * density / pxPerUnit
            canvas.drawRect(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f, lassoPaint)
            canvas.drawCircle(b.right, b.bottom, 9f * density / pxPerUnit, handlePaint)
        }
        canvas.restore()

        val anchor = c.holdAnchor
        val progress = c.dwellProgress(SystemClock.uptimeMillis())
        if (showHoldIndicator && anchor != null && progress > 0.15f) {
            // Indicador discreto: anillo que se completa al llegar a la pausa configurada.
            val (sx, sy) = documentToScreen(anchor.x, anchor.y)
            val r = 18f * density
            arcRect.set(sx - r, sy - r, sx + r, sy + r)
            canvas.drawArc(arcRect, 0f, 360f, false, holdTrackPaint)
            canvas.drawArc(arcRect, -90f, 360f * progress, false, holdPaint)
        }
    }

    private fun drawDryLayer(canvas: Canvas) {
        val c = controller
        docToScreen.getValues(currentMatrixValues)
        val version = c?.page?.version ?: -1L
        var bmp = cache
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            if (width <= 0 || height <= 0) return
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cache = bmp
            cacheValid = false
        }
        if (!cacheValid || version != cacheVersion || !currentMatrixValues.contentEquals(cacheMatrixValues)) {
            val cc = Canvas(bmp)
            cc.drawColor(deskColor)
            cc.save()
            cc.concat(docToScreen)
            if (mode == Mode.NOTEBOOK) cc.drawRect(4f, 6f, pageWidth + 4f, pageHeight + 6f, shadowPaint)
            PaperPainter.draw(cc, paper, pageWidth, pageHeight, darkPaper)
            val inkOverride = if (darkPaper) Color.rgb(0xE8, 0xEE, 0xEA) else null
            c?.page?.strokes?.forEach { s ->
                StrokePainter.draw(cc, s, if (inkOverride != null && s.color == io.trazo.core.DEFAULT_INK_COLOR) inkOverride else null)
            }
            cc.restore()
            System.arraycopy(currentMatrixValues, 0, cacheMatrixValues, 0, 9)
            cacheVersion = version
            cacheValid = true
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    companion object {
        const val TAG = "trazo_ink_canvas"
        private const val INVALID = -1
        private const val PALM_WINDOW_MS = 500L
        private const val FLAG_CANCELED = 0x20
    }
}
