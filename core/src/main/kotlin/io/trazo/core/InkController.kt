package io.trazo.core

import kotlin.math.hypot
import kotlin.math.max

enum class Tool { PEN, ERASER, STROKE_ERASER, LASSO }

/** Origen del contacto; la capa Android lo deriva de `MotionEvent.getToolType`. */
enum class PointerKind { STYLUS, STYLUS_ERASER, FINGER, MOUSE }

sealed interface InkEvent {
    /** La página cambió (dibujo, borrado, conversión, deshacer…). */
    data object PageChanged : InkEvent
    data object SelectionChanged : InkEvent
    data class Converted(val pattern: InkPattern, val score: Float, val removed: List<InkStroke>, val added: List<InkStroke>) : InkEvent
    /** Dos patrones parecidos: no se convierte; la UI puede ofrecer elegir (con vistas previas en tinta). */
    data class Ambiguous(val options: List<Match>, val gestureIds: List<String>, val anchor: Bounds) : InkEvent
    /** Pausa cumplida pero sin coincidencia clara: se conserva la escritura. */
    data object NotRecognized : InkEvent
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
    var recognitionEnabled: Boolean = true

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

    private enum class Mode { NONE, DRAW, CONSUMED, ERASE, STROKE_ERASE, LASSO_PATH, MOVE_SELECTION, SCALE_SELECTION }

    private var mode = Mode.NONE
    private var kind = PointerKind.STYLUS
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

    /** Trazo en curso (tinta húmeda) para dibujar sin esperar al reconocimiento. */
    val inProgress: InkStroke?
        get() = if (mode == Mode.DRAW && points.isNotEmpty()) currentStroke() else null

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

    fun penDown(x: Float, y: Float, pressure: Float, timeMs: Long, kind: PointerKind, pxPerUnit: Float = 1f) {
        if (mode != Mode.NONE) cancel()
        this.pxPerUnit = max(pxPerUnit, 1e-3f)
        this.kind = kind
        downTime = timeMs
        val effective = if (kind == PointerKind.STYLUS_ERASER) Tool.ERASER else tool
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
            Tool.LASSO -> {
                val sb = selectionBounds
                val handle = sb?.let { handleBounds(it) }
                dragStartX = x; dragStartY = y; dragged = false
                when {
                    handle != null && handle.contains(x, y) -> startDrag(Mode.SCALE_SELECTION, sb)
                    sb != null && sb.inflate(8f / this.pxPerUnit).contains(x, y) -> startDrag(Mode.MOVE_SELECTION, sb)
                    else -> {
                        clearSelection()
                        mode = Mode.LASSO_PATH
                        lassoPoints.clear(); lassoPoints += InkPoint(x, y)
                    }
                }
            }
        }
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
            Mode.CONSUMED, Mode.NONE -> Unit // tras convertir no se crea un trazo fantasma
        }
    }

    fun penUp(x: Float, y: Float, pressure: Float, timeMs: Long) {
        when (mode) {
            Mode.DRAW -> {
                penMoveIfMoved(x, y, pressure, timeMs)
                dwell.up()
                val stroke = currentStroke()
                val heldWithoutInk = lengthUnits * pxPerUnit < minInkLengthPx / 4f && timeMs - downTime >= holdMillis
                if (!heldWithoutInk) {
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
            Mode.CONSUMED -> resetContact()
            Mode.NONE -> Unit
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
        if (mode != Mode.DRAW || !recognitionEnabled) return false
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
                    emit(InkEvent.NotRecognized)
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
        mode != Mode.DRAW || !recognitionEnabled || lengthUnits * pxPerUnit < minInkLengthPx -> 0f
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
    fun chooseAmbiguous(pattern: InkPattern, gestureIds: List<String>): Boolean {
        val gesture = gestureIds.map { page.find(it) ?: return false }
        val output = InkReplacement.place(pattern, gesture.bounds(), ids, strokeClock(gesture.last().endTime))
        history.perform(page, StrokeEdit(gesture, output, StrokeEdit.Kind.CONVERT))
        emit(InkEvent.Converted(pattern, 1f, gesture, output))
        emit(InkEvent.PageChanged)
        return true
    }

    /** Inserta la tinta de un patrón centrada en (cx, cy) con altura [height] (desde la biblioteca). */
    fun insertPattern(pattern: InkPattern, cx: Float, cy: Float, height: Float, nowMs: Long = 0L): List<InkStroke> {
        val src = pattern.outputInk.bounds()
        val aspect = if (src.height > 0.5f) src.width / src.height else 1f
        val h = (if (src.height > 0.5f) height else height * 0.1f) / pattern.outputScale.coerceAtLeast(0.01f)
        val w = if (src.height > 0.5f) h * aspect else height * 2f / pattern.outputScale.coerceAtLeast(0.01f)
        val anchor = Bounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val output = InkReplacement.place(pattern, anchor, ids, strokeClock(nowMs))
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
        val output = InkReplacement.place(r.match.pattern, anchorGesture.bounds(), ids, strokeClock(downTime))
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
        color = color,
        width = width,
        startTime = strokeClock(downTime),
    )

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
    }

    private fun normalizePressure(p: Float, kind: PointerKind): Float = when {
        kind == PointerKind.FINGER || kind == PointerKind.MOUSE -> 0.7f
        p.isNaN() || p <= 0f -> 0.5f
        else -> p.coerceIn(0.05f, 1f)
    }
}
