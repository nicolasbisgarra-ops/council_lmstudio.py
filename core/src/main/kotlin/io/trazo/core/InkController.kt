package io.trazo.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Herramienta activa. [PAN] desplaza la hoja: lo resuelve la vista y el controlador lo ignora. */
enum class Tool { PEN, ERASER, STROKE_ERASER, LASSO, SHAPE, PAN }

/**
 * Origen del contacto; la capa Android lo deriva de `MotionEvent.getToolType` y de los botones.
 * [STYLUS_ERASER] es la goma del lápiz (extremo trasero) y [STYLUS_BUTTON], el lápiz con el botón
 * lateral apretado (S Pen): cada uno usa su propia herramienta (ver [InkController.toolFor]).
 */
enum class PointerKind { STYLUS, STYLUS_ERASER, STYLUS_BUTTON, FINGER, MOUSE }

sealed interface InkEvent {
    /** La página cambió (dibujo, borrado, conversión, deshacer…). */
    data object PageChanged : InkEvent
    data object SelectionChanged : InkEvent
    data class Converted(val pattern: InkPattern, val score: Float, val removed: List<InkStroke>, val added: List<InkStroke>) : InkEvent
    /** Dos patrones parecidos: no se convierte; la UI puede ofrecer elegir (con vistas previas en tinta). */
    data class Ambiguous(val options: List<Match>, val gestureIds: List<String>, val anchor: Bounds) : InkEvent
    /** Pausa cumplida sin coincidencia clara: se conserva la escritura. [candidates] son los grupos de ids (del más chico al más grande, el primero es solo el trazo actual) que la UI puede ofrecer para enseñar como gesto. */
    data class NotRecognized(val candidates: List<List<String>>, val anchor: Bounds) : InkEvent
}

/**
 * Sesión de edición de una página, independiente de Android.
 *
 * Recibe contactos (coordenadas de documento + tiempos de un reloj monotónico) y ticks del
 * temporizador. Implementa la máquina de estados del handoff:
 *
 * ```
 * DOWN → DRAWING ─(quieto ≥ pausa, contacto)→ RECONOCER ─ok→ REEMPLAZO (un solo comando) → CONSUMIDO hasta UP
 *                                                      └─dudoso/nada→ sigue DRAWING, se conserva la tinta
 * UP antes de la pausa → se guarda el trazo original · CANCEL → se descarta lo pendiente, nunca convierte
 * ```
 */
class InkController(
    page: PageState = PageState(),
    history: History = History(),
    var patterns: () -> List<InkPattern> = { emptyList() },
    private val ids: IdGenerator = IdGenerator.RANDOM,
    val recognizer: GestureRecognizer = GestureRecognizer(),
) {
    var page: PageState = page
        private set
    var history: History = history
        private set

    var tool: Tool = Tool.PEN
        set(value) {
            if (field != value) { field = value; if (value != Tool.LASSO) clearSelection() }
        }
    var color: Int = DEFAULT_INK_COLOR
    var width: Float = DEFAULT_INK_WIDTH
    /** Punta de la pluma. Con [Brush.HIGHLIGHTER] nunca se reconoce un gesto. */
    var brush: Brush = Brush.PEN
    /** Forma que dibuja [Tool.SHAPE]. */
    var shape: ShapeKind = ShapeKind.LINE
    /** Herramienta mientras se aprieta el botón lateral del lápiz; null = el botón no cambia nada. */
    var sideButtonTool: Tool? = Tool.ERASER
    /** Herramienta de la goma del lápiz (extremo trasero). */
    var hardwareEraserTool: Tool = Tool.ERASER
    var recognitionEnabled: Boolean = true

    /** Estilo con que se escribe ahora. */
    val style: InkStyle get() = InkStyle(color, width, brush)

    /** Pausa global; un patrón puede pedir más con [InkPattern.holdMillis]. */
    var holdMillis: Long = 1000L
    /** Temblor tolerado, en píxeles de pantalla. */
    var tremorRadiusPx: Float = 8f
    /** Tinta mínima (px) para intentar reconocer: apoyar sin dibujar nunca convierte. */
    var minInkLengthPx: Float = 20f
    var eraserRadiusPx: Float = 14f
    /** Radio del tirador de escala de la selección, en píxeles. */
    var handleRadiusPx: Float = 22f

    /** Convierte el reloj de eventos al guardado en [InkStroke.startTime]. */
    var strokeClock: (Long) -> Long = { it }

    private val listeners = mutableListOf<(InkEvent) -> Unit>()
    fun addListener(l: (InkEvent) -> Unit) { listeners += l }
    fun removeListener(l: (InkEvent) -> Unit) { listeners -= l }
    private fun emit(e: InkEvent) = listeners.toList().forEach { it(e) }

    private enum class Mode { NONE, DRAW, CONSUMED, ERASE, STROKE_ERASE, LASSO_PATH, MOVE_SELECTION, SCALE_SELECTION, SHAPE }

    private var mode = Mode.NONE
    private var kind = PointerKind.STYLUS
    /** Estilo y forma tomados al apoyar: cambiarlos a mitad de un trazo no lo altera. */
    private var contactStyle = InkStyle()
    private var contactShape = ShapeKind.LINE
    /** Herramienta del contacto en curso (null sin contacto). */
    var contactTool: Tool? = null
        private set
    /** El contacto empezó fuera de una selección y la quitó: si es solo un toque, no deja tinta. */
    private var dismissedSelection = false
    private var pxPerUnit = 1f
    private var downTime = 0L
    private var currentId = ""
    private val points = ArrayList<InkPoint>()
    private var lengthUnits = 0f
    private val dwell = DwellDetector()
    private var evaluatedStableFrom = Long.MIN_VALUE
    private var pendingConvert: Recognition.Convert? = null

    // Borrador: transacción de un contacto.
    private var eraseRemoved = LinkedHashMap<String, InkStroke>()
    private var eraseAdded = LinkedHashMap<String, InkStroke>()
    private var erasePositions = HashMap<String, Int>()
    private var eraseOriginalIndex: Map<String, Int> = emptyMap()
    private var lastEraseX = 0f
    private var lastEraseY = 0f

    // Lazo y selección.
    private val lassoPoints = ArrayList<InkPoint>()
    var selection: List<String> = emptyList()
        private set
    private var dragOriginals: List<InkStroke> = emptyList()
    private var dragBounds: Bounds? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragged = false

    // Formas.
    private var shapeX0 = 0f
    private var shapeY0 = 0f
    private var shapeX1 = 0f
    private var shapeY1 = 0f

    /** Trazo en curso (tinta húmeda) para dibujar sin esperar al reconocimiento. */
    val inProgress: InkStroke?
        get() = if (mode == Mode.DRAW && points.isNotEmpty()) currentStroke() else null

    /** Tinta húmeda del contacto: el trazo que se escribe o la forma que se arrastra. */
    val wetInk: List<InkStroke>
        get() = when (mode) {
            Mode.DRAW -> listOfNotNull(inProgress)
            Mode.SHAPE -> shapeStrokes(PREVIEW_IDS)
            else -> emptyList()
        }

    /** Centro del borrador mientras se borra (para dibujar su contorno). */
    val eraserPosition: InkPoint?
        get() = if (mode == Mode.ERASE || mode == Mode.STROKE_ERASE) InkPoint(lastEraseX, lastEraseY) else null

    val lassoPath: List<InkPoint> get() = if (mode == Mode.LASSO_PATH) lassoPoints else emptyList()

    val selectionBounds: Bounds?
        get() = selection.mapNotNull { page.find(it) }.takeIf { it.isNotEmpty() }?.bounds()

    val isContactActive: Boolean get() = mode != Mode.NONE

    /** Punto donde se muestra el indicador de pausa. */
    val holdAnchor: InkPoint? get() = if (mode == Mode.DRAW) points.lastOrNull() else null

    fun attach(page: PageState, history: History) {
        cancel()
        clearSelection()
        this.page = page
        this.history = history
        emit(InkEvent.PageChanged)
    }

    // ---------------------------------------------------------------- contactos

    /** Herramienta que usa un contacto de ese tipo: la goma y el botón lateral del lápiz tienen la suya. */
    fun toolFor(kind: PointerKind): Tool = when (kind) {
        PointerKind.STYLUS_ERASER -> hardwareEraserTool
        PointerKind.STYLUS_BUTTON -> sideButtonTool ?: tool
        else -> tool
    }

    fun penDown(x: Float, y: Float, pressure: Float, timeMs: Long, kind: PointerKind, pxPerUnit: Float = 1f) {
        if (mode != Mode.NONE) cancel()
        this.pxPerUnit = max(pxPerUnit, 1e-3f)
        this.kind = kind
        downTime = timeMs
        contactStyle = style
        contactShape = shape
        val effective = toolFor(kind)
        contactTool = effective
        dismissedSelection = false
        // Una selección hecha (lazo del botón lateral, patrón recién insertado) también se arrastra
        // con la pluma o las formas; tocar fuera de ella la quita.
        if (selection.isNotEmpty() && effective != Tool.LASSO) {
            if (effective == Tool.PEN || effective == Tool.SHAPE) {
                if (grabSelection(x, y)) return
                dismissedSelection = true
            }
            clearSelection()
        }
        when (effective) {
            Tool.PEN -> {
                mode = Mode.DRAW
                currentId = ids.next()
                points.clear(); lengthUnits = 0f
                points += InkPoint(x, y, normalizePressure(pressure, kind), 0L)
                dwell.durationMs = holdMillis
                dwell.radius = tremorRadiusPx / this.pxPerUnit
                dwell.minInkLength = minInkLengthPx / this.pxPerUnit
                dwell.down(x, y, timeMs)
                evaluatedStableFrom = Long.MIN_VALUE
                pendingConvert = null
            }
            Tool.ERASER, Tool.STROKE_ERASER -> {
                mode = if (effective == Tool.ERASER) Mode.ERASE else Mode.STROKE_ERASE
                eraseRemoved = LinkedHashMap(); eraseAdded = LinkedHashMap(); erasePositions = HashMap()
                eraseOriginalIndex = page.strokes.withIndex().associate { it.value.id to it.index }
                lastEraseX = x; lastEraseY = y
                eraseAt(x, y)
            }
            Tool.LASSO -> if (!grabSelection(x, y)) {
                clearSelection()
                mode = Mode.LASSO_PATH
                lassoPoints.clear(); lassoPoints += InkPoint(x, y)
            }
            Tool.SHAPE -> {
                mode = Mode.SHAPE
                shapeX0 = x; shapeY0 = y; shapeX1 = x; shapeY1 = y
            }
            Tool.PAN -> Unit // desplazar la hoja es cosa de la vista
        }
    }

    /**
     * Cambió el tipo de contacto sin levantar el lápiz. Al apretar el botón lateral se termina lo que
     * se hacía y se sigue desde (x, y) con la herramienta del botón; un trazo todavía mínimo se
     * descarta, porque el botón suele apretarse justo al apoyar. Al soltarlo se sigue con la misma
     * herramienta hasta levantar el lápiz.
     */
    fun changeKind(kind: PointerKind, x: Float, y: Float, pressure: Float, timeMs: Long) {
        if (contactTool == null || kind == this.kind) return
        if (kind != PointerKind.STYLUS_BUTTON || toolFor(kind) == contactTool) { this.kind = kind; return }
        if (mode == Mode.DRAW && lengthUnits * pxPerUnit < minInkLengthPx) cancel() else penUp(x, y, pressure, timeMs)
        penDown(x, y, pressure, timeMs, kind, pxPerUnit)
    }

    fun penMove(x: Float, y: Float, pressure: Float, timeMs: Long) {
        when (mode) {
            Mode.DRAW -> {
                val last = points.last()
                val p = InkPoint(x, y, normalizePressure(pressure, kind), (timeMs - downTime).coerceAtLeast(last.timeMs))
                lengthUnits += dist(last, p)
                points += p
                dwell.move(x, y, timeMs)
                if (dwell.stableFromMs != evaluatedStableFrom) pendingConvert = null
            }
            Mode.ERASE, Mode.STROKE_ERASE -> {
                // Interpola posiciones intermedias para no saltar tinta con movimientos rápidos.
                val step = max(eraserRadiusPx / pxPerUnit / 2f, 0.5f)
                val d = hypot(x - lastEraseX, y - lastEraseY)
                val n = (d / step).toInt().coerceAtLeast(1)
                for (k in 1..n) eraseAt(lastEraseX + (x - lastEraseX) * k / n, lastEraseY + (y - lastEraseY) * k / n)
                lastEraseX = x; lastEraseY = y
            }
            Mode.LASSO_PATH -> lassoPoints += InkPoint(x, y)
            Mode.MOVE_SELECTION, Mode.SCALE_SELECTION -> dragTo(x, y)
            Mode.SHAPE -> { shapeX1 = x; shapeY1 = y }
            Mode.CONSUMED, Mode.NONE -> Unit // tras convertir no se crea un trazo fantasma
        }
    }

    fun penUp(x: Float, y: Float, pressure: Float, timeMs: Long) {
        when (mode) {
            Mode.DRAW -> {
                penMoveIfMoved(x, y, pressure, timeMs)
                dwell.up()
                val stroke = currentStroke()
                val tiny = lengthUnits * pxPerUnit < minInkLengthPx / 4f
                val heldWithoutInk = tiny && timeMs - downTime >= holdMillis
                val tapToDismiss = tiny && dismissedSelection && timeMs - downTime < TAP_MS
                if (!heldWithoutInk && !tapToDismiss) {
                    history.perform(page, StrokeEdit(emptyList(), listOf(stroke), StrokeEdit.Kind.DRAW))
                    emit(InkEvent.PageChanged)
                }
                resetContact()
            }
            Mode.ERASE, Mode.STROKE_ERASE -> {
                penMove(x, y, pressure, timeMs)
                val edit = StrokeEdit(eraseRemoved.values.toList(), eraseAdded.values.toList(), StrokeEdit.Kind.ERASE)
                history.record(edit, erasePositions)
                resetContact()
            }
            Mode.LASSO_PATH -> {
                lassoPoints += InkPoint(x, y)
                selection = Lasso.select(page.strokes, lassoPoints).map { it.id }
                resetContact()
                emit(InkEvent.SelectionChanged)
            }
            Mode.MOVE_SELECTION, Mode.SCALE_SELECTION -> {
                dragTo(x, y)
                if (dragged) {
                    val finals = dragOriginals.mapNotNull { page.find(it.id) }
                    history.record(StrokeEdit(dragOriginals, finals, StrokeEdit.Kind.TRANSFORM), emptyMap())
                    emit(InkEvent.PageChanged)
                }
                resetContact()
                emit(InkEvent.SelectionChanged)
            }
            Mode.SHAPE -> {
                shapeX1 = x; shapeY1 = y
                val strokes = shapeStrokes(ids)
                if (strokes.isNotEmpty()) {
                    history.perform(page, StrokeEdit(emptyList(), strokes, StrokeEdit.Kind.INSERT))
                    emit(InkEvent.PageChanged)
                }
                resetContact()
            }
            Mode.CONSUMED -> resetContact()
            Mode.NONE -> resetContact()
        }
    }

    /** `ACTION_CANCEL` (palma, navegación, pérdida de foco): se descarta lo pendiente y nunca se convierte. */
    fun cancel() {
        when (mode) {
            Mode.ERASE, Mode.STROKE_ERASE -> {
                // Revierte el borrado parcial de este contacto.
                val edit = StrokeEdit(eraseRemoved.values.toList(), eraseAdded.values.toList(), StrokeEdit.Kind.ERASE)
                if (!edit.isEmpty) { page.revert(edit, erasePositions); emit(InkEvent.PageChanged) }
            }
            Mode.MOVE_SELECTION, Mode.SCALE_SELECTION -> if (dragged) {
                page.apply(StrokeEdit(dragOriginals, dragOriginals, StrokeEdit.Kind.TRANSFORM))
                emit(InkEvent.PageChanged)
            }
            else -> Unit
        }
        dwell.cancel()
        resetContact()
    }

    /**
     * Llamar periódicamente mientras hay contacto (p. ej. cada 16–50 ms).
     * @return true si conviene redibujar (indicador de pausa o conversión).
     */
    fun tick(nowMs: Long): Boolean {
        if (mode != Mode.DRAW || !recognitionEnabled || contactStyle.brush == Brush.HIGHLIGHTER) return false
        val pats = patterns().filter { it.enabled }
        val minHold = minOf(holdMillis, pats.minOfOrNull { it.holdMillis ?: holdMillis } ?: holdMillis)
        if (!dwell.isStill(nowMs, minHold, lengthUnits)) return dwell.isTouching
        if (evaluatedStableFrom != dwell.stableFromMs) {
            // Una sola evaluación por período de quietud; moverse fuera del radio abre otro.
            evaluatedStableFrom = dwell.stableFromMs
            pendingConvert = null
            when (val r = recognize(pats)) {
                is Recognition.Convert -> pendingConvert = r
                is Recognition.Ambiguous -> {
                    emit(InkEvent.Ambiguous(r.options, r.gesture.map { it.id }, r.gesture.bounds()))
                    return true
                }
                Recognition.None -> {
                    val gesture = trimHold(currentStroke())
                    val groups = recognizer.candidateGroups(gesture, page.strokes)
                    emit(InkEvent.NotRecognized(groups.map { g -> g.map { it.id } }, gesture.bounds))
                    return true
                }
            }
        }
        val pending = pendingConvert ?: return false
        if (nowMs - dwell.stableFromMs >= (pending.match.pattern.holdMillis ?: holdMillis)) {
            dwell.consume()
            convert(pending)
        }
        return true
    }

    /** Avance 0..1 del indicador de pausa. */
    fun dwellProgress(nowMs: Long): Float = when {
        mode != Mode.DRAW || !recognitionEnabled || contactStyle.brush == Brush.HIGHLIGHTER ||
            lengthUnits * pxPerUnit < minInkLengthPx -> 0f
        // Ya evaluado sin coincidencia en esta quietud: no prometer una conversión.
        evaluatedStableFrom == dwell.stableFromMs && pendingConvert == null -> 0f
        else -> dwell.progress(nowMs)
    }

    // ---------------------------------------------------------------- comandos

    fun undo(): Boolean {
        if (mode != Mode.NONE) return false
        clearSelection()
        return (history.undo(page) != null).also { if (it) emit(InkEvent.PageChanged) }
    }

    fun redo(): Boolean {
        if (mode != Mode.NONE) return false
        clearSelection()
        return (history.redo(page) != null).also { if (it) emit(InkEvent.PageChanged) }
    }

    /** El usuario eligió un patrón tras una ambigüedad. */
    fun chooseAmbiguous(pattern: InkPattern, gestureIds: List<String>): Boolean = convertWith(pattern, gestureIds) != null

    /** Todos los trazos con esos ids, en ese orden, o null si falta alguno. */
    fun strokesById(ids: List<String>): List<InkStroke>? = ids.map { page.find(it) ?: return null }

    /**
     * Reemplaza esos trazos (que deben estar en la página) por la salida de [pattern] en un solo paso deshacible (StrokeEdit.Kind.CONVERT); emite Converted(score = 1f) y PageChanged. Devuelve la tinta insertada o null si falta algún trazo.
     * @param nowMs reloj de eventos para el startTime de la salida; 0 = el final del gesto.
     */
    fun convertWith(pattern: InkPattern, gestureIds: List<String>, nowMs: Long = 0L): List<InkStroke>? {
        if (mode != Mode.NONE || gestureIds.isEmpty()) return null
        val gesture = strokesById(gestureIds.distinct()) ?: return null
        val start = strokeClock(if (nowMs != 0L) nowMs else gesture.last().endTime)
        val output = InkReplacement.place(pattern, gesture.bounds(), ids, start, style = gesture.last().style)
        history.perform(page, StrokeEdit(gesture, output, StrokeEdit.Kind.CONVERT))
        if (selection.any { it in gestureIds }) clearSelection()
        emit(InkEvent.Converted(pattern, 1f, gesture, output))
        emit(InkEvent.PageChanged)
        return output
    }

    /** Inserta la tinta de un patrón centrada en (cx, cy) con altura [height] (desde la biblioteca), con el estilo actual; la barra elástica conserva su largo natural. */
    fun insertPattern(pattern: InkPattern, cx: Float, cy: Float, height: Float, nowMs: Long = 0L): List<InkStroke> {
        val src = pattern.outputInk.bounds()
        val aspect = if (src.height > 0.5f) src.width / src.height else 1f
        val h = (if (src.height > 0.5f) height else height * 0.1f) / pattern.outputScale.coerceAtLeast(0.01f)
        val w = if (src.height > 0.5f) h * aspect else height * 2f / pattern.outputScale.coerceAtLeast(0.01f)
        val anchor = Bounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val output = InkReplacement.place(pattern, anchor, ids, strokeClock(nowMs), style = style, elastic = false)
        history.perform(page, StrokeEdit(emptyList(), output, StrokeEdit.Kind.INSERT))
        emit(InkEvent.PageChanged)
        return output
    }

    fun clearSelection() {
        if (selection.isNotEmpty()) { selection = emptyList(); emit(InkEvent.SelectionChanged) }
    }

    fun select(ids: List<String>) {
        selection = ids.filter { page.contains(it) }
        emit(InkEvent.SelectionChanged)
    }

    fun selectAll() {
        if (mode != Mode.NONE) return
        select(page.strokes.map { it.id })
    }

    /** Duplica la selección desplazada (dx, dy): ids nuevos; los groupId se reasignan de forma consistente (los que compartían grupo comparten el nuevo); la selección pasa a ser la copia. Un solo StrokeEdit.Kind.INSERT. Devuelve los ids nuevos. */
    fun duplicateSelection(dx: Float = 24f, dy: Float = 24f): List<String> {
        if (mode != Mode.NONE) return emptyList()
        val strokes = selection.mapNotNull { page.find(it) }
        if (strokes.isEmpty()) return emptyList()
        val copies = strokes.copiedWithNewIds(ids, Affine.translate(dx, dy))
        history.perform(page, StrokeEdit(emptyList(), copies, StrokeEdit.Kind.INSERT))
        selection = copies.map { it.id }
        emit(InkEvent.PageChanged); emit(InkEvent.SelectionChanged)
        return selection
    }

    /** Cambia el color de la selección en un solo paso deshacible (mismos ids, Kind.TRANSFORM). */
    fun recolorSelection(color: Int) {
        if (mode != Mode.NONE) return
        val strokes = selection.mapNotNull { page.find(it) }.filter { it.color != color }
        if (strokes.isEmpty()) return
        history.perform(page, StrokeEdit(strokes, strokes.map { it.copy(color = color) }, StrokeEdit.Kind.TRANSFORM))
        emit(InkEvent.PageChanged)
    }

    fun deleteSelection() {
        val strokes = selection.mapNotNull { page.find(it) }
        if (strokes.isEmpty()) return
        history.perform(page, StrokeEdit(strokes, emptyList(), StrokeEdit.Kind.DELETE))
        selection = emptyList()
        emit(InkEvent.PageChanged); emit(InkEvent.SelectionChanged)
    }

    fun transformSelection(t: Affine) {
        val strokes = selection.mapNotNull { page.find(it) }
        if (strokes.isEmpty()) return
        history.perform(page, StrokeEdit(strokes, strokes.map { it.transformed(t) }, StrokeEdit.Kind.TRANSFORM))
        emit(InkEvent.PageChanged); emit(InkEvent.SelectionChanged)
    }

    fun scaleSelection(factor: Float) {
        val b = selectionBounds ?: return
        transformSelection(Affine.scale(factor, b.centerX, b.centerY))
    }

    fun rotateSelection(degrees: Float) {
        val b = selectionBounds ?: return
        transformSelection(Affine.rotate(degrees, b.centerX, b.centerY))
    }

    /** Selecciona todos los trazos del mismo grupo (salida de un patrón) que [strokeId]. */
    fun selectGroupOf(strokeId: String) {
        val s = page.find(strokeId) ?: return
        select(if (s.groupId == null) listOf(s.id) else page.strokes.filter { it.groupId == s.groupId }.map { it.id })
    }

    /** Zona del tirador de escala (esquina inferior derecha de la selección), en documento. */
    fun handleBounds(selection: Bounds): Bounds {
        val r = handleRadiusPx / pxPerUnit
        return Bounds(selection.right - r, selection.bottom - r, selection.right + r, selection.bottom + r)
    }

    // ---------------------------------------------------------------- interno

    private fun recognize(pats: List<InkPattern>): Recognition {
        val full = currentStroke()
        val gesture = trimHold(full)
        if (pats.isEmpty()) return Recognition.None
        return when (val r = recognizer.recognize(gesture, page.strokes, pats)) {
            // Para quitar, se usa el trazo actual completo (incluido el temblor de la pausa).
            is Recognition.Convert -> r.copy(gesture = r.gesture.map { if (it.id == currentId) full else it })
            is Recognition.Ambiguous -> r
            Recognition.None -> r
        }
    }

    private fun convert(r: Recognition.Convert) {
        val anchorGesture = r.gesture.map { if (it.id == currentId) trimHold(it) else it }
        val output = InkReplacement.place(r.match.pattern, anchorGesture.bounds(), ids, strokeClock(downTime), style = contactStyle)
        // Un solo comando: deshacer restaura todo el gesto (incluido el trazo en curso).
        val edit = StrokeEdit(r.gesture, output, StrokeEdit.Kind.CONVERT)
        history.perform(page, edit)
        mode = Mode.CONSUMED
        points.clear()
        emit(InkEvent.Converted(r.match.pattern, r.match.score, r.gesture, output))
        emit(InkEvent.PageChanged)
    }

    /** Quita las muestras registradas durante la pausa (temblor) para que no deformen el reconocimiento. */
    private fun trimHold(stroke: InkStroke): InkStroke {
        val cutoff = dwell.stableFromMs - downTime
        val head = stroke.points.filter { it.timeMs <= cutoff }
        val tail = stroke.points.subList(head.size, stroke.points.size)
        if (head.isEmpty() || tail.isEmpty()) return stroke
        // La cola mezcla el final real del trazo (dentro del radio) y el temblor de la pausa:
        // se reemplaza por un único punto en la posición media de reposo.
        val rest = tail.subList(tail.size / 2, tail.size)
        val end = InkPoint(rest.map { it.x }.average().toFloat(), rest.map { it.y }.average().toFloat(), rest.last().pressure, tail.first().timeMs)
        return stroke.copy(points = head + end)
    }

    private fun currentStroke() = InkStroke(
        id = currentId,
        points = points.toList(),
        color = contactStyle.color,
        width = contactStyle.width,
        startTime = strokeClock(downTime),
        brush = contactStyle.brush,
    )

    /** Trazos de la forma arrastrada; vacío mientras es demasiado chica (un toque no deja una forma). */
    private fun shapeStrokes(idGen: IdGenerator): List<InkStroke> {
        if (max(abs(shapeX1 - shapeX0), abs(shapeY1 - shapeY0)) * pxPerUnit < MIN_SHAPE_PX) return emptyList()
        return Shapes.build(contactShape, shapeX0, shapeY0, shapeX1, shapeY1, contactStyle, idGen, strokeClock(downTime))
    }

    private fun penMoveIfMoved(x: Float, y: Float, pressure: Float, timeMs: Long) {
        val last = points.last()
        if (last.x != x || last.y != y) penMove(x, y, pressure, timeMs)
    }

    private fun eraseAt(x: Float, y: Float) {
        val r = eraserRadiusPx / pxPerUnit
        val snapshot = page.strokes.toList()
        var changed = false
        snapshot.forEach { s ->
            val pieces = if (mode == Mode.STROKE_ERASE) {
                if (Eraser.hits(s, x, y, r)) emptyList() else null
            } else {
                Eraser.erase(s, x, y, r, ids)
            } ?: return@forEach
            if (pieces.size == 1 && pieces[0].id == s.id) return@forEach
            page.apply(StrokeEdit(listOf(s), pieces, StrokeEdit.Kind.ERASE))
            if (eraseAdded.remove(s.id) == null) {
                eraseRemoved[s.id] = s
                eraseOriginalIndex[s.id]?.let { erasePositions[s.id] = it }
            }
            pieces.forEach { eraseAdded[it.id] = it }
            changed = true
        }
        if (changed) emit(InkEvent.PageChanged)
    }

    /** Empieza a mover o escalar la selección si (x, y) cae sobre ella o su tirador. */
    private fun grabSelection(x: Float, y: Float): Boolean {
        val sb = selectionBounds ?: return false
        dragStartX = x; dragStartY = y; dragged = false
        when {
            handleBounds(sb).contains(x, y) -> startDrag(Mode.SCALE_SELECTION, sb)
            sb.inflate(8f / pxPerUnit).contains(x, y) -> startDrag(Mode.MOVE_SELECTION, sb)
            else -> return false
        }
        return true
    }

    private fun startDrag(m: Mode, bounds: Bounds) {
        mode = m
        dragOriginals = selection.mapNotNull { page.find(it) }
        dragBounds = bounds
    }

    private fun dragTo(x: Float, y: Float) {
        val b = dragBounds ?: return
        val dx = x - dragStartX
        val dy = y - dragStartY
        if (!dragged && hypot(dx, dy) * pxPerUnit < 3f) return
        dragged = true
        val t = if (mode == Mode.MOVE_SELECTION) {
            Affine.translate(dx, dy)
        } else {
            val w0 = max(b.width, 1f)
            val h0 = max(b.height, 1f)
            val s = (((b.width + dx) / w0 + (b.height + dy) / h0) / 2f).coerceIn(0.1f, 20f)
            Affine.scale(s, b.left, b.top)
        }
        page.apply(StrokeEdit(dragOriginals, dragOriginals.map { it.transformed(t) }, StrokeEdit.Kind.TRANSFORM))
        emit(InkEvent.PageChanged)
    }

    private fun resetContact() {
        mode = Mode.NONE
        points.clear()
        lengthUnits = 0f
        pendingConvert = null
        lassoPoints.clear()
        dragOriginals = emptyList()
        dragBounds = null
        contactTool = null
        dismissedSelection = false
    }

    private fun normalizePressure(p: Float, kind: PointerKind): Float = when {
        kind == PointerKind.FINGER || kind == PointerKind.MOUSE -> 0.7f
        p.isNaN() || p <= 0f -> 0.5f
        else -> p.coerceIn(0.05f, 1f)
    }

    private companion object {
        /** Lado mínimo (px) para que el arrastre de la herramienta de formas cree una forma. */
        const val MIN_SHAPE_PX = 6f
        /** Un contacto más corto que esto es un toque. */
        const val TAP_MS = 300L
        val PREVIEW_IDS = IdGenerator { "preview" }
    }
}
