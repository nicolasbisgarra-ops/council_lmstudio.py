package io.trazo.app.ink

import io.trazo.app.image.ImagePainter
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
import android.view.ViewConfiguration
import io.trazo.app.ai.AiMarkPainter
import io.trazo.core.Brush
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Paper
import io.trazo.core.PointerKind
import io.trazo.core.Tool
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Superficie de tinta nativa (sin WebView). Traduce `MotionEvent` al [InkController]:
 *
 *  - Lápiz (`TOOL_TYPE_STYLUS`) escribe; su extremo goma (`TOOL_TYPE_ERASER`) borra y el botón
 *    lateral usa la herramienta que se configuró (goma por defecto), también a mitad de un trazo.
 *    Los Galaxy antiguos mandan el botón como acciones propias (211–213), que se traducen igual.
 *  - Se consumen las muestras históricas de cada MOVE (S Pen reporta más rápido que el refresco).
 *  - Dedo: desplaza y hace zoom (dos dedos); un toque con dos dedos deshace; con tres, o un dedo
 *    apoyado y otro que toca dos veces, prende o apaga la detección. Mientras el lápiz está apoyado o cerca, el dedo/palma se ignora.
 *    `ACTION_CANCEL` y `FLAG_CANCELED` descartan el trazo y nunca convierten.
 *  - Con la herramienta mano ([Tool.PAN]) el lápiz y el ratón también desplazan la hoja.
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
        set(value) {
            if (field != value) {
                field = value
                fitIfNeeded(force = true)
                invalidateContent()
            }
        }

    /** El controlador vive más que la vista (ViewModel): solo se escucha mientras está en pantalla. */
    var controller: InkController? = null
        set(value) {
            if (field === value) return
            field?.removeListener(listener)
            field = value
            if (isAttachedToWindow) value?.addListener(listener)
            invalidateContent()
        }

    var paper: Paper = Paper.LINES
        set(value) { if (field != value) { field = value; invalidateContent() } }

    var fingerDraws = false
    var showHoldIndicator = true
    /** Vibración breve al reemplazar un gesto por su tinta. */
    var hapticsEnabled = true
    /** Toque rápido con dos dedos (sin desplazar ni hacer zoom): por defecto, deshacer. */
    var onTwoFingerTap: (() -> Unit)? = null
    /** Toque rápido con tres dedos: prende o apaga la detección automática de signos y letras. */
    var onThreeFingerTap: (() -> Unit)? = null
    /** Un dedo apoyado y otro que toca dos veces seguidas: prende o apaga la detección de gestos. */
    var onHoldSecondDoubleTap: (() -> Unit)? = null
    /** Dos dedos apoyados y un tercero que toca dos veces seguidas. */
    var onHoldDoubleTap: (() -> Unit)? = null
    /**
     * Clics del botón lateral con el lápiz en el aire: cuántos seguidos (1 a 3) y dónde, en
     * coordenadas de la vista. Se avisa [PEN_CLICK_WAIT_MS] después del último, para distinguir 1, 2 y 3.
     */
    var onPenButtonClicks: ((count: Int, x: Float, y: Float) -> Unit)? = null
    /** Colores de la hoja y del escritorio; en hoja oscura las tintas oscuras se ven claras. */
    var paperColors: PaperColors = PaperColors.LIGHT
        set(value) {
            if (field != value) {
                field = value
                shadowPaint.color = value.shadow
                invalidateContent()
            }
        }
    /** Color de acento del tema: anillo de pausa, lazo y selección. */
    var accentColor: Int = DEFAULT_ACCENT
        set(value) {
            if (field != value) {
                field = value
                applyAccent()
                invalidate()
            }
        }
    /**
     * Lazo apoyado y quieto un momento sobre un lugar vacío: se ofrece pegar ahí. Recibe el punto en
     * coordenadas de la vista y de documento.
     */
    var onLassoLongPress: ((x: Float, y: Float, docX: Float, docY: Float) -> Unit)? = null
    private var holdDownX = 0f
    private var holdDownY = 0f
    private var lassoHoldPending = false
    private val lassoHold = Runnable {
        lassoHoldPending = false
        val c = controller ?: return@Runnable
        if (drawingPointerId == INVALID) return@Runnable
        // El contacto deja de ser un lazo: lo que siga hasta levantar no dibuja nada.
        c.cancel()
        drawingPointerId = INVALID
        removeCallbacks(ticker)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val (dx, dy) = boundedScreenInkPoint(holdDownX, holdDownY)
        onLassoLongPress?.invoke(holdDownX, holdDownY, dx, dy)
        invalidate()
    }

    private fun cancelLassoHold() {
        if (lassoHoldPending) { removeCallbacks(lassoHold); lassoHoldPending = false }
    }

    /** Cambió el zoom o el desplazamiento (para mostrar el porcentaje). */
    var onViewportChanged: (() -> Unit)? = null

    var pageWidth = Notebook.PAGE_WIDTH
        set(value) { field = value; fitIfNeeded(force = true) }
    var pageHeight = Notebook.PAGE_HEIGHT
        set(value) { field = value; fitIfNeeded(force = true) }

    var viewport: Viewport = Viewport()
        set(value) { field = value; fitIfNeeded(force = !value.isSet) ; applyViewport() }

    /** Px de pantalla por unidad de documento (zoom actual). */
    val pxPerUnit: Float get() = viewport.scale.takeIf { it > 0f } ?: 1f

    /** Zoom respecto de la hoja encajada al ancho (100 %). */
    val zoomPercent: Int
        get() {
            val fit = fitScale()
            return if (fit <= 0f || !viewport.isSet) 100 else (viewport.scale / fit * 100f).roundToInt()
        }

    private val docToScreen = Matrix()
    private val screenToDoc = Matrix()
    private val tmp = FloatArray(2)

    // Estado de entrada
    private var drawingPointerId = INVALID
    private var drawingKind = PointerKind.STYLUS
    /** El contacto en curso desplaza la hoja (herramienta mano). */
    private var panLastX = 0f
    private var panLastY = 0f
    private var stylusNearUntil = 0L
    /** Lápiz cerca de la hoja con una herramienta de borrar: se muestra el contorno de la goma. */
    private var hoverEraser = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var navigating = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var tapStartTime = 0L
    /** Botón lateral visto apretado, y si se apretó con el lápiz en el aire (no al borrar apoyado). */
    private var penButtonDown = false
    private var penButtonInAir = false
    private var penClicks = 0
    private var penClickX = 0f
    private var penClickY = 0f
    private val penClickDone = Runnable {
        val n = penClicks
        penClicks = 0
        if (n > 0) onPenButtonClicks?.invoke(minOf(n, 3), penClickX, penClickY)
    }
    private var tapMaxPointers = 0
    private var tapTravel = 0f
    private var tapScaled = false
    // Toques de un tercer dedo con dos apoyados.
    private var secondDownTime = 0L
    private var lastSecondTap = 0L
    private var thirdDownTime = 0L
    private var lastThirdTap = 0L
    private var holdTapUsed = false
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            tapScaled = true
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    // Render
    private var cache: Bitmap? = null
    private var cacheValid = false
    private var cacheVersion = -1L
    private var cacheMath: List<InkStroke> = emptyList()
    private val cacheMatrixValues = FloatArray(9)
    private val currentMatrixValues = FloatArray(9)
    // Solo la tinta de la página, transparente: va encima de un resaltador en vivo para que no la tape.
    private var inkOverlay: Bitmap? = null
    private var inkOverlayVersion = -1L
    private val inkOverlayMatrixValues = FloatArray(9)
    private var inkOverlayMath: List<InkStroke> = emptyList()
    private val deskColor get() = paperColors.desk
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3f * density
    }
    private val holdTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Halos de la integral o la raíz recién puesta: zona translúcida para escribir extremos o radicando. */
    private val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloRect = RectF()
    private val selectionFill = Paint().apply { style = Paint.Style.FILL }
    /** Contorno de la goma: claro y oscuro a la vez para verse sobre cualquier tinta. */
    private val eraserOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.argb(150, 255, 255, 255)
    }
    private val eraserInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = Color.argb(200, 60, 60, 60)
    }
    private val shadowPaint = Paint().apply { color = PaperColors.LIGHT.shadow }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val workspaceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }
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

    /** Acomoda lo escrito en un halo de extremo cuando pasa la pausa sin seguir escribiendo en él. */
    private val haloSettle = Runnable {
        val c = controller ?: return@Runnable
        if (c.haloTick(SystemClock.uptimeMillis())) invalidateContent() else if (!c.isContactActive) scheduleHaloSettle()
        invalidate()
    }

    private fun scheduleHaloSettle() {
        removeCallbacks(haloSettle)
        val due = controller?.haloDeadline ?: return
        postDelayed(haloSettle, max(0L, due - SystemClock.uptimeMillis()) + 16L)
    }

    init {
        isFocusable = true
        tag = TAG
        contentDescription = "Hoja de tinta"
        applyAccent()
    }

    private fun applyAccent() {
        holdPaint.color = accentColor
        holdTrackPaint.color = withAlpha(accentColor, 60)
        lassoPaint.color = accentColor
        handlePaint.color = accentColor
        selectionFill.color = withAlpha(accentColor, 24)
        haloFill.color = withAlpha(accentColor, 26)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)

    fun invalidateContent() {
        cacheValid = false
        dryNodeValid = false
        inkOverlayVersion = -1L
        invalidate()
    }

    // ------------------------------------------------------------------ coordenadas

    fun screenToDocument(x: Float, y: Float): Pair<Float, Float> {
        tmp[0] = x; tmp[1] = y; screenToDoc.mapPoints(tmp); return tmp[0] to tmp[1]
    }

    fun documentToScreen(x: Float, y: Float): Pair<Float, Float> {
        tmp[0] = x; tmp[1] = y; docToScreen.mapPoints(tmp); return tmp[0] to tmp[1]
    }

    /** 100% remains the central page fitted to the view width, as in 0.2.4. */
    private fun fitScale(): Float =
        if (width == 0 || height == 0) 0f
        else if (mode == Mode.PAD) min(width / pageWidth, height / pageHeight)
        else width / pageWidth

    /** Minimum notebook zoom that keeps the entire three-page workspace available. */
    private fun minNotebookScale(): Float = viewport.workspaceFit(width, height, canvasBounds())

    /** The original page fit used by initial layout and the "Fit page" command. */
    private fun fitPageScale(): Float = if (width <= 0 || pageWidth <= 0f) 0f else width / pageWidth

    private fun canvasBounds() = CanvasBounds.aroundPage(pageWidth, pageHeight)

    /** Stylus samples may use the full 3×3 workspace, but never create ink outside it. */
    private fun boundedInkPoint(x: Float, y: Float): Pair<Float, Float> {
        if (mode == Mode.PAD) return x to y
        val bounds = canvasBounds()
        return bounds.clampX(x) to bounds.clampY(y)
    }

    private fun boundedScreenInkPoint(x: Float, y: Float): Pair<Float, Float> =
        screenToDocument(x, y).let { boundedInkPoint(it.first, it.second) }

    /** Vuelve a encajar la hoja al ancho de la vista (botón "Ajustar"). */
    fun fitToPage() = fitIfNeeded(force = true)

    private fun fitIfNeeded(force: Boolean) {
        if (width == 0 || height == 0) return
        if (force || !viewport.isSet || mode == Mode.PAD) {
            val s = if (mode == Mode.PAD) fitScale() else fitPageScale()
            viewport.scale = s
            viewport.tx = (width - pageWidth * s) / 2f
            viewport.ty = if (mode == Mode.PAD) (height - pageHeight * s) / 2f else 0f
        }
        if (mode == Mode.NOTEBOOK) viewport.rememberSize(width, height)
        applyViewport()
    }

    private fun applyViewport() {
        docToScreen.reset()
        docToScreen.postScale(viewport.scale.takeIf { it > 0f } ?: 1f, viewport.scale.takeIf { it > 0f } ?: 1f)
        docToScreen.postTranslate(viewport.tx, viewport.ty)
        docToScreen.invert(screenToDoc)
        invalidate()
        // Solo avisa si cambió de verdad: reasignar la misma vista no debe redibujar la interfaz en bucle.
        val now = Triple(viewport.scale, viewport.tx, viewport.ty)
        if (now == notifiedViewport) return
        notifiedViewport = now
        onViewportChanged?.invoke()
    }

    private var notifiedViewport: Triple<Float, Float, Float>? = null

    /** Acerca o aleja desde el centro de la vista (botones de zoom). */
    fun zoomStep(factor: Float) {
        if (width == 0 || height == 0) return
        if (!viewport.isSet) fitIfNeeded(force = true)
        zoomBy(factor, width / 2f, height / 2f)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (mode == Mode.PAD) return
        val fit = fitScale()
        val newScale = (viewport.scale * factor).coerceIn(minNotebookScale(), fit * 8f)
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

    /** Keep panning within the 3×3 writing area. PAD keeps its existing one-page bounds. */
    private fun clampPan() {
        val w = pageWidth * viewport.scale
        val h = pageHeight * viewport.scale
        if (mode == Mode.NOTEBOOK) {
            viewport.clampToCanvas(width, height, canvasBounds())
        } else {
            viewport.tx = viewport.tx.coerceIn(-w + width / 3f, width - width / 3f)
            viewport.ty = viewport.ty.coerceIn(-h + height / 3f, height - height / 3f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache?.recycle(); cache = null
        inkOverlay?.recycle(); inkOverlay = null; inkOverlayVersion = -1L
        if (mode == Mode.NOTEBOOK) {
            viewport.resizeNotebook(w, h, pageWidth, pageHeight, fitPageScale())
            applyViewport()
        } else {
            fitIfNeeded(force = true)
        }
        invalidateContent()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller?.let { it.removeListener(listener); it.addListener(listener) }
        invalidateContent()
    }

    override fun onDetachedFromWindow() {
        // Rotación / cierre a mitad de un trazo: cancelar, nunca convertir.
        cancelContact()
        controller?.removeListener(listener)
        cache?.recycle(); cache = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { dryNode?.discardDisplayList(); dryNodeValid = false }
        inkOverlay?.recycle(); inkOverlay = null; inkOverlayVersion = -1L
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelContact()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) cancelContact()
    }

    /**
     * Interrupción (pausa, pérdida de foco, ventana oculta): se descarta el trazo en curso y la pausa
     * deja de contar, así nunca se convierte un gesto interrumpido.
     */
    fun cancelContact() {
        if (drawingPointerId != INVALID) {
            controller?.cancel()
            drawingPointerId = INVALID
            invalidate()
        }
        removeCallbacks(ticker)
        cancelLassoHold()
        resetPenButton()
    }

    /** Descarta clics del botón pendientes y el estado apretado: una interrupción nunca dispara la ruleta. */
    private fun resetPenButton() {
        removeCallbacks(penClickDone)
        penClicks = 0
        penButtonDown = false
        penButtonInAir = false
    }

    // ------------------------------------------------------------------ entrada

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isStylus(event.getToolType(0))) {
            stylusNearUntil = SystemClock.uptimeMillis() + PALM_WINDOW_MS
            trackPenButton(event)
            val c = controller
            val erasing = c != null && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT &&
                c.toolFor(kindOf(event, 0)).let { it == Tool.ERASER || it == Tool.STROKE_ERASER }
            if (erasing || hoverEraser) {
                hoverEraser = erasing
                hoverX = event.x; hoverY = event.y
                invalidate()
            }
        }
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Algunos equipos mandan el botón en el aire como BUTTON_PRESS/RELEASE y no en el HOVER_MOVE.
        if (isStylus(event.getToolType(0)) && event.actionMasked in BUTTON_ACTIONS) {
            trackPenButton(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Cuenta un clic cuando el botón se suelta con el lápiz en el aire y se había apretado en el aire. */
    private fun trackPenButton(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            // El lápiz salió del alcance: un botón soltado fuera no es un clic.
            penButtonDown = false
            penButtonInAir = false
            return
        }
        val pressed = (event.buttonState and STYLUS_BUTTONS) != 0
        if (pressed && !penButtonDown) {
            penButtonDown = true
            penButtonInAir = drawingPointerId == INVALID
            // Un clic más en curso: la cuenta espera a que se suelte.
            removeCallbacks(penClickDone)
        } else if (!pressed && penButtonDown) {
            penButtonDown = false
            if (penButtonInAir && drawingPointerId == INVALID) {
                penClicks++
                penClickX = event.x
                penClickY = event.y
                removeCallbacks(penClickDone)
                postDelayed(penClickDone, PEN_CLICK_WAIT_MS)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = controller ?: return false
        // EditorViewModel can reset this shared mutable viewport on page navigation before
        // AndroidView receives its next Compose update. Refit before mapping any input.
        if (!viewport.isSet) fitIfNeeded(force = true)
        // Galaxy antiguos: el botón lateral apretado llega como acciones propias de Samsung.
        val spenButton = event.actionMasked in SPEN_ACTION_DOWN..SPEN_ACTION_MOVE
        val action = when (event.actionMasked) {
            SPEN_ACTION_DOWN -> MotionEvent.ACTION_DOWN
            SPEN_ACTION_UP -> MotionEvent.ACTION_UP
            SPEN_ACTION_MOVE -> MotionEvent.ACTION_MOVE
            else -> event.actionMasked
        }
        val index = event.actionIndex
        if (hoverEraser) { hoverEraser = false; invalidate() }
        if (isStylus(event.getToolType(index)) &&
            (spenButton || (event.buttonState and STYLUS_BUTTONS) != 0)
        ) {
            // Botón apretado con el lápiz apoyado (goma): al soltarlo luego en el aire no cuenta como clic.
            penButtonDown = true
            penButtonInAir = false
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Un DOWN inicia siempre un gesto nuevo: se descarta cualquier contacto que quedó colgado.
                if (action == MotionEvent.ACTION_DOWN) { cancelContact(); navigating = false }
                val tool = event.getToolType(index)
                val kind = kindOf(event, index, spenButton)
                val canDraw = isStylus(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE ||
                    (tool == MotionEvent.TOOL_TYPE_FINGER && (fingerDraws || mode == Mode.PAD))
                if (isStylus(tool)) {
                    stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                    // Apoyar el lápiz corta la cuenta de clics: el botón pasa a ser la goma.
                    penButtonInAir = false
                    penClicks = 0
                    removeCallbacks(penClickDone)
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
                    panLastX = event.getX(index); panLastY = event.getY(index)
                    val (x, y) = boundedScreenInkPoint(event.getX(index), event.getY(index))
                    c.penDown(x, y, event.getPressure(index), event.eventTime, kind, pxPerUnit)
                    cancelLassoHold()
                    val onSelection = c.selectionBounds?.let { c.handleBounds(it) }?.contains(x, y) == true
                    if (onLassoLongPress != null && c.contactTool == Tool.LASSO && !onSelection) {
                        holdDownX = event.getX(index); holdDownY = event.getY(index)
                        lassoHoldPending = true
                        postDelayed(lassoHold, LASSO_HOLD_MS)
                    }
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
                    if (i >= 0 && drawingKind != PointerKind.FINGER) {
                        // Botón lateral apretado o soltado sin levantar el lápiz.
                        val kind = kindOf(event, i, spenButton)
                        if (kind != drawingKind) {
                            val (x, y) = boundedScreenInkPoint(event.getX(i), event.getY(i))
                            c.changeKind(kind, x, y, event.getPressure(i), event.eventTime)
                            drawingKind = kind
                        }
                    }
                    if (lassoHoldPending && i >= 0 && hypot(event.getX(i) - holdDownX, event.getY(i) - holdDownY) > tapSlop) cancelLassoHold()
                    if (i >= 0 && c.contactTool == Tool.PAN) {
                        panBy(event.getX(i) - panLastX, event.getY(i) - panLastY)
                        panLastX = event.getX(i); panLastY = event.getY(i)
                        if (drawingKind != PointerKind.FINGER) stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                    } else if (i >= 0) {
                        for (h in 0 until event.historySize) {
                            val (x, y) = boundedScreenInkPoint(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                            c.penMove(x, y, event.getHistoricalPressure(i, h), event.getHistoricalEventTime(h))
                        }
                        val (x, y) = boundedScreenInkPoint(event.getX(i), event.getY(i))
                        c.penMove(x, y, event.getPressure(i), event.eventTime)
                        if (drawingKind != PointerKind.FINGER) stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                    }
                } else if (navigating) {
                    navigate(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (drawingPointerId != INVALID && event.getPointerId(index) == drawingPointerId) {
                    cancelLassoHold()
                    if (isCanceled(event)) {
                        c.cancel()
                    } else {
                        val (x, y) = boundedScreenInkPoint(event.getX(index), event.getY(index))
                        c.penUp(x, y, event.getPressure(index), event.eventTime)
                        scheduleHaloSettle()
                    }
                    drawingPointerId = INVALID
                    if (drawingKind != PointerKind.FINGER) stylusNearUntil = event.eventTime + PALM_WINDOW_MS
                } else if (navigating) {
                    navigate(event)
                }
                if (action == MotionEvent.ACTION_UP) navigating = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLassoHold()
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
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                tapStartTime = event.eventTime; tapMaxPointers = 1; tapTravel = 0f; tapScaled = false
                holdTapUsed = false; lastThirdTap = 0L; lastSecondTap = 0L
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapMaxPointers = max(tapMaxPointers, event.pointerCount)
                if (event.pointerCount == 2) secondDownTime = event.eventTime
                if (event.pointerCount == 3) thirdDownTime = event.eventTime
            }
            // Un dedo apoyado y el segundo toca: el primer toque solo se anota (sigue valiendo el toque con dos
            // dedos para deshacer); el segundo seguido dispara el gesto y ya no se deshace al soltar.
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount == 2 && event.actionIndex != 0 && !tapScaled &&
                event.eventTime - secondDownTime < MULTI_FINGER_TAP_MS && tapMaxPointers == 2
            ) {
                if (lastSecondTap != 0L && event.eventTime - lastSecondTap < DOUBLE_TAP_MS) {
                    lastSecondTap = 0L
                    holdTapUsed = true
                    onHoldSecondDoubleTap?.invoke()
                } else {
                    lastSecondTap = event.eventTime
                }
            } else if (event.pointerCount == 3 && event.eventTime - thirdDownTime < MULTI_FINGER_TAP_MS) {
                // Un toque del tercer dedo; el segundo seguido dispara el gesto.
                holdTapUsed = true
                if (lastThirdTap != 0L && event.eventTime - lastThirdTap < DOUBLE_TAP_MS) {
                    lastThirdTap = 0L
                    onHoldDoubleTap?.invoke()
                } else {
                    lastThirdTap = event.eventTime
                }
            }
            MotionEvent.ACTION_UP -> if (!holdTapUsed && !tapScaled && tapTravel < tapSlop * 2 && event.eventTime - tapStartTime < MULTI_FINGER_TAP_MS) {
                when (tapMaxPointers) {
                    // Un toque con el dedo sobre una cuenta muestra qué se calculó y cómo.
                    1 -> { val (x, y) = screenToDocument(event.x, event.y); controller?.tapAt(x, y, wholeLine = true) }
                    2 -> onTwoFingerTap?.invoke()
                    3 -> onThreeFingerTap?.invoke()
                }
            }
        }
        val skip = if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) event.actionIndex else -1
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip || event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            sx += event.getX(i); sy += event.getY(i); n++
        }
        if (n == 0) return
        val fx = sx / n
        val fy = sy / n
        if (action == MotionEvent.ACTION_MOVE) {
            tapTravel += hypot(fx - lastFocusX, fy - lastFocusY)
            panBy(fx - lastFocusX, fy - lastFocusY)
        }
        lastFocusX = fx; lastFocusY = fy
    }

    private fun kindOf(event: MotionEvent, index: Int, spenButton: Boolean = false): PointerKind = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.STYLUS_ERASER
        MotionEvent.TOOL_TYPE_STYLUS ->
            if (spenButton || (event.buttonState and STYLUS_BUTTONS) != 0) PointerKind.STYLUS_BUTTON else PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.FINGER
    }

    private fun isStylus(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    /** `FLAG_CANCELED` (API 33): el sistema marcó el contacto como accidental (palma). */
    private fun isCanceled(event: MotionEvent) =
        Build.VERSION.SDK_INT >= 33 && (event.flags and FLAG_CANCELED) != 0

    // ------------------------------------------------------------------ dibujo

    override fun onDraw(canvas: Canvas) {
        // A draw may arrive before AndroidView.update after navigation. Keep the cached and
        // inverse matrices in sync with a zero-scale reset before rendering this frame.
        if (!viewport.isSet) fitIfNeeded(force = true)
        // AndroidView does not guarantee clipping against Compose siblings. In particular,
        // drawColor must never paint over a toolbar laid out to the left of this View.
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            drawContent(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val c = controller
        canvas.drawColor(deskColor)
        drawDryLayer(canvas)
        if (c == null) { drawWorkspaceBoundary(canvas); return }

        val wet = c.wetInk
        if (wet.any { it.brush == Brush.HIGHLIGHTER }) {
            // El resaltador en vivo va debajo de la tinta igual que el ya guardado.
            canvas.save()
            canvas.concat(docToScreen)
            StrokePainter.drawHighlighters(canvas, wet, paperColors.dark)
            canvas.restore()
            drawInkOverlay(canvas)
        }
        canvas.save()
        canvas.concat(docToScreen)
        StrokePainter.drawInkOnly(canvas, wet, paperColors.dark)
        // Capa del Modo IA: encima de la tinta, se oculta con el ojo.
        val halos = c.halos()
        if (halos.isNotEmpty()) {
            lassoPaint.strokeWidth = 1.2f * density / pxPerUnit
            val r = 8f * density / pxPerUnit
            halos.forEach { h ->
                haloRect.set(h.zone.left, h.zone.top, h.zone.right, h.zone.bottom)
                canvas.drawRoundRect(haloRect, r, r, haloFill)
                canvas.drawRoundRect(haloRect, r, r, lassoPaint)
            }
        }
        c.page.ai?.takeIf { c.aiVisible }?.let { ai -> AiMarkPainter.draw(canvas, ai.notes, ai.verdict(c.page.strokes)) }
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
            canvas.drawRect(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f, selectionFill)
            canvas.drawRect(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f, lassoPaint)
            canvas.drawCircle(b.right, b.bottom, 9f * density / pxPerUnit, handlePaint)
        }
        // Forma dinámica elegida: marco punteado y un tirador por medida editable.
        c.selectedShape?.let { s ->
            val b = s.bounds
            lassoPaint.strokeWidth = 1.5f * density / pxPerUnit
            canvas.drawRect(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f, lassoPaint)
            val r = 7f * density / pxPerUnit
            c.shapeHandles.forEach { h ->
                canvas.drawCircle(h.at.x, h.at.y, r, handlePaint)
                canvas.drawCircle(h.at.x, h.at.y, r, lassoPaint)
            }
        }
        canvas.restore()

        // Contorno de la goma: mientras borra y mientras el lápiz se acerca con una herramienta de borrar.
        val eraser = c.eraserPosition?.let { documentToScreen(it.x, it.y) } ?: if (hoverEraser) hoverX to hoverY else null
        if (eraser != null) {
            val r = c.eraserRadiusPx
            canvas.drawCircle(eraser.first, eraser.second, r, eraserOuter)
            canvas.drawCircle(eraser.first, eraser.second, r, eraserInner)
        }

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
        drawWorkspaceBoundary(canvas)
    }

    /** A visible frame marks the finite 3×3 area while leaving the central paper unchanged. */
    private fun drawWorkspaceBoundary(canvas: Canvas) {
        if (mode != Mode.NOTEBOOK) return
        val bounds = canvasBounds()
        val topLeft = documentToScreen(bounds.left, bounds.top)
        val bottomRight = documentToScreen(bounds.right, bounds.bottom)
        workspaceBorderPaint.color = paperColors.border.takeIf { it != 0 }
            ?: if (paperColors.dark) Color.argb(210, 230, 230, 235) else Color.argb(210, 94, 86, 70)
        canvas.drawRect(topLeft.first, topLeft.second, bottomRight.first, bottomRight.second, workspaceBorderPaint)
    }

    // Capa seca en la GPU (API 29+): la hoja se graba una vez como lista de dibujo en coordenadas de la hoja y
    // en cada cuadro solo cambia la matriz. Mover o hacer zoom ya no vuelve a pintar ni subir un bitmap de
    // pantalla completa, y escribir solo dibuja la tinta húmeda encima: así se sostienen los 120 Hz.
    private var dryNode: android.graphics.RenderNode? = null
    private var dryNodeValid = false
    private var dryNodeVersion = -1L
    private var dryNodeMath: List<InkStroke> = emptyList()
    private var dryNodeScale = 0f

    private fun drawDryNode(canvas: Canvas): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !canvas.isHardwareAccelerated) return false
        val c = controller
        val version = c?.page?.version ?: -1L
        val math = c?.mathResults.orEmpty()
        val node = dryNode ?: android.graphics.RenderNode("trazo-dry").also {
            it.setClipToBounds(false)
            dryNode = it
        }
        // El grosor del borde depende del zoom: se regraba solo si el zoom cambió de verdad.
        val scaleChanged = mode == Mode.NOTEBOOK && paperColors.border != 0 && kotlin.math.abs(pxPerUnit - dryNodeScale) > 0.02f * dryNodeScale
        val w = max(1, kotlin.math.ceil(pageWidth.toDouble()).toInt())
        val h = max(1, kotlin.math.ceil(pageHeight.toDouble()).toInt())
        val sized = node.width == w && node.height == h
        if (!dryNodeValid || !sized || !node.hasDisplayList() || version != dryNodeVersion || math !== dryNodeMath || scaleChanged) {
            node.setPosition(0, 0, w, h)
            val cc = node.beginRecording()
            try {
                if (mode == Mode.NOTEBOOK && paperColors.shadow != 0) cc.drawRect(4f, 6f, pageWidth + 4f, pageHeight + 6f, shadowPaint)
                PaperPainter.draw(cc, paper, pageWidth, pageHeight, paperColors)
                if (mode == Mode.NOTEBOOK && paperColors.border != 0) {
                    borderPaint.color = paperColors.border
                    borderPaint.strokeWidth = 1.5f * density / pxPerUnit
                    cc.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), borderPaint)
                }
                c?.page?.images?.takeIf { it.isNotEmpty() }?.let { ImagePainter.draw(cc, it, paperColors.dark) }
                c?.page?.allInk?.let { StrokePainter.drawAll(cc, it, paperColors.dark) }
                StrokePainter.drawInkOnly(cc, math, paperColors.dark)
            } finally {
                node.endRecording()
            }
            dryNodeVersion = version
            dryNodeMath = math
            dryNodeScale = pxPerUnit
            dryNodeValid = true
        }
        canvas.save()
        canvas.concat(docToScreen)
        canvas.drawRenderNode(node)
        canvas.restore()
        // La capa raster ya no hace falta: se libera su memoria.
        cache?.let { it.recycle(); cache = null; cacheValid = false }
        return true
    }

    private fun drawDryLayer(canvas: Canvas) {
        if (drawDryNode(canvas)) return
        val c = controller
        docToScreen.getValues(currentMatrixValues)
        val version = c?.page?.version ?: -1L
        var bmp = cache
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            cache = null
            cacheValid = false
            if (width <= 0 || height <= 0) return
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cache = bmp
            cacheValid = false
        }
        val math = c?.mathResults.orEmpty()
        if (!cacheValid || version != cacheVersion || math !== cacheMath || !currentMatrixValues.contentEquals(cacheMatrixValues)) {
            val cc = Canvas(bmp)
            cc.drawColor(deskColor)
            cc.save()
            cc.concat(docToScreen)
            if (mode == Mode.NOTEBOOK && paperColors.shadow != 0) cc.drawRect(4f, 6f, pageWidth + 4f, pageHeight + 6f, shadowPaint)
            PaperPainter.draw(cc, paper, pageWidth, pageHeight, paperColors)
            if (mode == Mode.NOTEBOOK && paperColors.border != 0) {
                borderPaint.color = paperColors.border
                borderPaint.strokeWidth = 1.5f * density / pxPerUnit
                cc.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), borderPaint)
            }
            // Las imágenes van debajo de la tinta.
            c?.page?.images?.takeIf { it.isNotEmpty() }?.let { ImagePainter.draw(cc, it, paperColors.dark) }
            c?.page?.allInk?.let { StrokePainter.drawAll(cc, it, paperColors.dark) }
            // Resultados de las cuentas: tinta derivada, no está en la página.
            StrokePainter.drawInkOnly(cc, math, paperColors.dark)
            cc.restore()
            cacheMath = math
            System.arraycopy(currentMatrixValues, 0, cacheMatrixValues, 0, 9)
            cacheVersion = version
            cacheValid = true
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    private fun drawInkOverlay(canvas: Canvas) {
        val page = controller?.page ?: return
        var bmp = inkOverlay
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            inkOverlay = null
            inkOverlayVersion = -1L
            if (width <= 0 || height <= 0) return
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            inkOverlay = bmp
            inkOverlayVersion = -1L
        }
        if (page.version != inkOverlayVersion || inkOverlayMath !== cacheMath ||
            !currentMatrixValues.contentEquals(inkOverlayMatrixValues)
        ) {
            bmp.eraseColor(0)
            val oc = Canvas(bmp)
            oc.concat(docToScreen)
            StrokePainter.drawInkOnly(oc, page.allInk, paperColors.dark)
            StrokePainter.drawInkOnly(oc, cacheMath, paperColors.dark)
            System.arraycopy(currentMatrixValues, 0, inkOverlayMatrixValues, 0, 9)
            inkOverlayVersion = page.version
            inkOverlayMath = cacheMath
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    companion object {
        const val TAG = "trazo_ink_canvas"
        private const val INVALID = -1
        private const val PALM_WINDOW_MS = 500L
        private const val FLAG_CANCELED = 0x20
        private const val MULTI_FINGER_TAP_MS = 300L
        private const val DOUBLE_TAP_MS = 450L
        /** Espera tras soltar el botón en el aire antes de decidir si fueron 1, 2 o 3 clics. */
        const val PEN_CLICK_WAIT_MS = 350L
        private val BUTTON_ACTIONS = setOf(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)
        /** Cuánto se deja quieto el lazo para que aparezca «Pegar». */
        private const val LASSO_HOLD_MS = 550L
        /** Acciones del S Pen con el botón apretado en Galaxy antiguos (fuera del SDK). */
        private const val SPEN_ACTION_DOWN = 211
        private const val SPEN_ACTION_UP = 212
        private const val SPEN_ACTION_MOVE = 213
        /** Botón lateral: según el fabricante llega como primario, secundario o botón secundario del ratón. */
        private const val STYLUS_BUTTONS =
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY
        private const val DEFAULT_ACCENT = 0xFF9A7C42.toInt()
    }
}
