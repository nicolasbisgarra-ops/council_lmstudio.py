package io.trazo.app.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.trazo.app.data.EraserMode
import io.trazo.app.data.PageExporter
import io.trazo.app.data.SideButtonAction
import io.trazo.app.data.TrazoSettings
import io.trazo.app.data.highlighterInk
import io.trazo.app.ink.Viewport
import io.trazo.app.trazo
import io.trazo.core.Brush
import io.trazo.core.GeoKind
import io.trazo.core.History
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.InkPattern
import io.trazo.core.InkStroke
import io.trazo.core.MathNotes
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.PageState
import io.trazo.core.Paper
import io.trazo.core.ParametricShape
import io.trazo.core.Tool
import io.trazo.core.withGestureExample
import io.trazo.core.withPageDuplicated
import io.trazo.core.withPageMoved
import io.trazo.core.withPageRemoved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Herramienta de la barra. El resaltador es la pluma con su propia punta, color y grosor. */
enum class EditorTool { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, PAN }

/**
 * Estado de un cuaderno abierto. El [controller] sobrevive a rotaciones; cada página tiene su
 * propio historial. Autoguardado atómico con espera corta tras cada cambio y al salir.
 */
class EditorViewModel(app: Application, val notebookId: String) : AndroidViewModel(app) {
    private val repo = app.trazo.repository
    private val settings = app.trazo.settings

    var notebook by mutableStateOf<Notebook?>(null)
        private set
    /** El cuaderno se lee fuera del hilo principal; mientras tanto la pantalla muestra un indicador. */
    var loading by mutableStateOf(true)
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    /** Cambia con cada edición para refrescar botones de deshacer/rehacer. */
    var revision by mutableIntStateOf(0)
        private set
    var editorTool by mutableStateOf(EditorTool.PEN)
        private set
    /** Zoom relativo a la hoja encajada al ancho (100 %); lo actualiza la vista. */
    var zoomPercent by mutableIntStateOf(100)
    var ambiguous by mutableStateOf<InkEvent.Ambiguous?>(null)
    var lastConversion by mutableStateOf<InkEvent.Converted?>(null)
    /** Pausa sin coincidencia: se ofrece enseñar el gesto (aviso discreto). */
    var teachOffer by mutableStateOf<InkEvent.NotRecognized?>(null)
    /** Hoja abierta para elegir qué patrón era el gesto. */
    var teaching by mutableStateOf<InkEvent.NotRecognized?>(null)
    /** Resultado tocado: qué se calculó y cómo (burbuja como en Samsung Notes). */
    var mathExplanation by mutableStateOf<MathNotes.Explanation?>(null)
    var hasSelection by mutableStateOf(false)
        private set
    /** Id de la forma dinámica elegida (null si no hay); dispara el inspector. */
    var shapeSelected by mutableStateOf<String?>(null)
        private set
    /** Cambia cuando se pide volver a encajar la hoja; la vista lo observa. */
    var fitRequest by mutableIntStateOf(0)
        private set

    val viewport = Viewport()
    private var lastSettings = settings.settings.value
    private val pages = HashMap<String, Pair<PageState, History>>()
    private var saveJob: Job? = null
    /** Versión de los cambios y última versión confirmada en disco (abrir o mirar no modifica). */
    private var editVersion = 0L
    private var savedVersion = 0L
    private var attaching = false

    val controller = InkController(patterns = { repo.patterns.value }, ids = IdGenerator.RANDOM).apply {
        val offset = System.currentTimeMillis() - android.os.SystemClock.uptimeMillis()
        strokeClock = { it + offset }
        addListener { e ->
            when (e) {
                InkEvent.PageChanged -> { revision++; mathExplanation = null; if (!attaching) markDirty() }
                InkEvent.SelectionChanged -> { hasSelection = selection.isNotEmpty(); shapeSelected = selectedShapeId }
                is InkEvent.Converted -> lastConversion = e
                is InkEvent.Ambiguous -> ambiguous = e
                is InkEvent.NotRecognized -> teachOffer = e
                is InkEvent.MathExplained -> mathExplanation = e.explanation
            }
        }
    }

    init {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { repo.loadNotebook(notebookId) }
            notebook = n
            if (n != null) attachPage(0)
            loading = false
        }
    }

    val pageCount: Int get() = notebook?.pages?.size ?: 0
    val paper: Paper get() = notebook?.paper ?: Paper.LINES

    fun applySettings(s: TrazoSettings, density: Float) {
        lastSettings = s
        controller.holdMillis = s.holdMillis
        controller.tremorRadiusPx = s.tremorRadiusDp * density
        controller.eraserRadiusPx = s.eraserRadiusDp * density
        controller.minInkLengthPx = 12f * density
        controller.handleRadiusPx = 24f * density
        controller.recognitionEnabled = s.gesturesEnabled
        controller.autoAlign = s.autoAlign
        controller.mathEnabled = s.mathNotes
        controller.mathColor = s.mathResultColor
        controller.shape = s.shape
        // El extremo goma del lápiz borra como la goma elegida; el botón lateral hace lo que se configuró.
        controller.hardwareEraserTool = eraserTool(s)
        controller.sideButtonTool = when (s.sideButton) {
            SideButtonAction.ERASER -> eraserTool(s)
            SideButtonAction.LASSO -> Tool.LASSO
            SideButtonAction.NONE -> null
        }
        if (editorTool == EditorTool.ERASER) controller.tool = eraserTool(s)
        applyToolStyle(s)
    }

    private fun eraserTool(s: TrazoSettings) = if (s.eraserMode == EraserMode.STROKE) Tool.STROKE_ERASER else Tool.ERASER

    /** Punta, color y grosor de la herramienta de escritura elegida. */
    private fun applyToolStyle(s: TrazoSettings) {
        if (editorTool == EditorTool.HIGHLIGHTER) {
            controller.brush = Brush.HIGHLIGHTER
            controller.color = s.highlighterInk()
            controller.width = s.highlighterWidth
        } else {
            controller.brush = s.brush
            controller.color = s.penColor
            controller.width = s.penWidth
        }
    }

    /** Herramienta a la que vuelve el modo formas al apagarse. */
    private var toolBeforeShapes = EditorTool.PEN

    /** Tres clics del botón en el aire: entra al modo formas o vuelve a la herramienta de antes. */
    fun toggleShapeTool(): Boolean {
        val on = editorTool != EditorTool.SHAPE
        selectTool(if (on) EditorTool.SHAPE else toolBeforeShapes)
        return on
    }

    fun selectTool(t: EditorTool) {
        if (t == EditorTool.SHAPE && editorTool != EditorTool.SHAPE) toolBeforeShapes = editorTool
        editorTool = t
        controller.tool = when (t) {
            EditorTool.PEN, EditorTool.HIGHLIGHTER -> Tool.PEN
            EditorTool.ERASER -> eraserTool(lastSettings)
            EditorTool.LASSO -> Tool.LASSO
            EditorTool.SHAPE -> Tool.SHAPE
            EditorTool.PAN -> Tool.PAN
        }
        applyToolStyle(lastSettings)
    }

    fun undo() { controller.undo() }
    fun redo() { controller.redo() }
    val canUndo: Boolean get() { revision; return controller.history.canUndo }
    val canRedo: Boolean get() { revision; return controller.history.canRedo }
    /** Hay un trazo en curso: los diálogos esperan a que se levante el lápiz. */
    val isDrawing: Boolean get() { revision; return controller.isContactActive }

    private fun attachPage(index: Int) {
        val n = notebook ?: return
        val page = n.pages[index]
        val (state, history) = pages.getOrPut(page.id) { PageState(page.strokes, page.parametricShapes) to History() }
        pageIndex = index
        attaching = true
        try {
            controller.attach(state, history)
        } finally {
            attaching = false
        }
    }

    fun goToPage(index: Int) {
        val n = notebook ?: return
        if (index !in n.pages.indices || index == pageIndex) return
        syncNotebook()
        attachPage(index)
        viewport.scale = 0f // volver a encajar
    }

    fun addPage() {
        syncNotebook()
        val n = notebook ?: return
        notebook = n.copy(pages = n.pages + Page(IdGenerator.RANDOM.next()))
        attachPage(n.pages.size)
        viewport.scale = 0f
        markDirty()
    }

    fun setPaper(p: Paper) {
        notebook = notebook?.copy(paper = p)
        revision++
        markDirty()
    }

    fun rename(name: String) {
        notebook = notebook?.copy(name = name)
        markDirty()
    }

    /** Elección tras una ambigüedad; con "aprender" activo, el gesto queda como ejemplo del patrón elegido. */
    fun chooseAmbiguous(pattern: InkPattern?) {
        val a = ambiguous ?: return
        ambiguous = null
        if (pattern != null) teach(pattern, a.gestureIds)
    }

    /** Trazos de un grupo candidato, si siguen en la página. */
    fun strokesOf(ids: List<String>) = controller.strokesById(ids)

    /** Reemplaza el gesto por la tinta del patrón y, si corresponde, lo guarda como ejemplo nuevo. */
    fun teach(pattern: InkPattern, gestureIds: List<String>): Boolean {
        val gesture = controller.strokesById(gestureIds) ?: return false
        controller.convertWith(pattern, gestureIds, android.os.SystemClock.uptimeMillis()) ?: return false
        if (settings.settings.value.learnFromCorrections) {
            val current = repo.patterns.value.firstOrNull { it.id == pattern.id } ?: pattern
            repo.upsertPattern(current.withGestureExample(gesture, now = System.currentTimeMillis()))
        }
        return true
    }

    /** Resultados de las cuentas de [page] para exportar, como se ven en la hoja. */
    private fun mathResults(page: Page): List<InkStroke> =
        if (controller.mathEnabled) MathNotes.results(page.strokes, repo.patterns.value, controller.mathColor) else emptyList()

    /** Calcula lo seleccionado y escribe el resultado (con «=» si faltaba); false si no había una cuenta. */
    /** Calcula la selección; si no se puede, devuelve el motivo para mostrarlo. */
    /** Aviso pendiente cuando la ecuación solo tiene raíces imaginarias: el usuario decide si resolverla igual. */
    var complexPrompt by mutableStateOf<String?>(null)

    fun calculateSelection(): String? {
        if (controller.calculateSelection()) return null
        if (controller.calculationOnlyComplex()) {
            complexPrompt = controller.calculationProblem()
            return null
        }
        return controller.calculationProblem()
    }

    fun calculateWithImaginaries() {
        complexPrompt = null
        controller.calculateSelection(complex = true)
    }

    fun duplicateSelection() { controller.duplicateSelection() }
    fun recolorSelection(color: Int) { controller.recolorSelection(color) }
    /** Color del primer trazo elegido (para marcarlo en la paleta). */
    val selectionColor: Int? get() { revision; return controller.strokesById(controller.selection.take(1))?.firstOrNull()?.color }
    fun selectAll() {
        selectTool(EditorTool.LASSO)
        controller.selectAll()
    }

    /** Borra toda la página de una vez (se puede deshacer). */
    fun clearPage() { controller.clearPage() }

    val pageIsEmpty: Boolean get() { revision; return controller.page.strokes.isEmpty() && controller.page.shapes.isEmpty() }

    fun duplicatePage() {
        val n = syncNotebook() ?: return
        notebook = n.withPageDuplicated(pageIndex)
        attachPage(pageIndex + 1)
        viewport.scale = 0f
        markDirty()
    }

    /** Elimina la página actual (no se puede deshacer; la UI pide confirmación). */
    fun deletePage() {
        val n = syncNotebook() ?: return
        if (n.pages.size < 2) return
        val removed = n.pages[pageIndex].id
        notebook = n.withPageRemoved(pageIndex)
        pages.remove(removed)
        attachPage(pageIndex.coerceAtMost(n.pages.size - 2))
        viewport.scale = 0f
        markDirty()
    }

    fun movePage(delta: Int) {
        val n = syncNotebook() ?: return
        val to = pageIndex + delta
        if (to !in n.pages.indices) return
        notebook = n.withPageMoved(pageIndex, to)
        attachPage(to)
        markDirty()
    }

    /** Inserta la tinta de salida de un patrón en el centro de la zona visible. */
    fun insertPattern(pattern: InkPattern, viewWidth: Int, viewHeight: Int) {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        val cx = ((viewWidth / 2f - viewport.tx) / vpScale).coerceIn(80f, Notebook.PAGE_WIDTH - 80f)
        val cy = ((viewHeight / 2f - viewport.ty) / vpScale).coerceIn(80f, Notebook.PAGE_HEIGHT - 80f)
        controller.insertPattern(pattern, cx, cy, 140f, android.os.SystemClock.uptimeMillis())
    }

    // ---------------------------------------------------------------- formas dinámicas

    val selectedShape: ParametricShape? get() { revision; shapeSelected; return controller.selectedShape }

    /** Tamaño en px del lienzo; lo informa la pantalla para centrar lo que se inserta. */
    var viewSize = 0 to 0

    /** Inserta una forma dinámica en el centro de la zona visible, ya seleccionada para editar. */
    fun insertParametric(kind: GeoKind, viewWidth: Int = viewSize.first, viewHeight: Int = viewSize.second) {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        val cx = ((viewWidth / 2f - viewport.tx) / vpScale).coerceIn(100f, Notebook.PAGE_WIDTH - 100f)
        val cy = ((viewHeight / 2f - viewport.ty) / vpScale).coerceIn(100f, Notebook.PAGE_HEIGHT - 100f)
        controller.insertParametric(kind, cx, cy, 160f)
    }

    fun setShapeDimension(axis: String, value: Float, commit: Boolean = false) { controller.setShapeDimension(axis, value, commit) }
    fun setShapeRotation(degrees: Float, commit: Boolean = false) { controller.setShapeRotation(degrees, commit) }
    fun commitShapeEdit() = controller.commitShapeEdit()
    fun deleteShape() = controller.deleteShape()
    fun duplicateShape() { controller.duplicateShape() }
    fun convertShapeToInk() { controller.convertShapeToInk() }
    fun clearShapeSelection() = controller.clearShapeSelection()

    fun fitToPage() {
        viewport.scale = 0f
        fitRequest++
    }

    /**
     * Exporta fuera del hilo principal: PDF vectorial de todo el cuaderno o PNG de la página actual
     * (para compartir; el formato editable es `.trazo`). [onDone] recibe el error, o null si salió bien.
     */
    fun exportTo(resolver: ContentResolver, uri: Uri, pdf: Boolean, onDone: (Throwable?) -> Unit) {
        val n = syncNotebook() ?: return
        val page = n.pages[pageIndex]
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val out = resolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
                    out.use {
                        if (pdf) PageExporter.writePdf(n, it, ::mathResults)
                        else PageExporter.writePng(page, n.paper, it, results = mathResults(page))
                    }
                }.exceptionOrNull()
            }
            onDone(error)
        }
    }

    /** Vuelca las páginas en edición al modelo inmutable. */
    private fun syncNotebook(): Notebook? {
        val n = notebook ?: return null
        val updated = n.copy(
            pages = n.pages.map { p -> pages[p.id]?.let { p.copy(strokes = it.first.strokes.toList(), parametricShapes = it.first.shapes.toList()) } ?: p },
            updatedAt = System.currentTimeMillis(),
        )
        notebook = updated
        return updated
    }

    private fun markDirty() {
        editVersion++
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            if (savedVersion == editVersion) return@launch
            val version = editVersion
            val snapshot = syncNotebook() ?: return@launch
            val saved = withContext(Dispatchers.IO) { runCatching { repo.saveNotebook(snapshot) }.isSuccess }
            if (saved) savedVersion = maxOf(savedVersion, version)
        }
    }

    /** Guardado inmediato si hay cambios aún no confirmados en disco (al pasar a segundo plano o salir). */
    fun saveNow() {
        saveJob?.cancel()
        if (savedVersion == editVersion) return
        val version = editVersion
        syncNotebook()?.let { repo.saveNotebook(it) }
        savedVersion = version
    }

    override fun onCleared() {
        if (!controller.isContactActive) saveNow()
        super.onCleared()
    }
}
