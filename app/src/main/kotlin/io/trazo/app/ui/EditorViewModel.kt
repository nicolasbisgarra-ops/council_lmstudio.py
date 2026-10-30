package io.trazo.app.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.trazo.app.data.EraserMode
import io.trazo.app.data.PageExporter
import io.trazo.app.data.SideButtonAction
import io.trazo.app.data.TrazoSettings
import io.trazo.app.data.highlighterInk
import io.trazo.app.ink.PaperColors
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
import io.trazo.core.LazyNotebook
import io.trazo.core.MathNotes
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.PageState
import io.trazo.core.Paper
import io.trazo.core.ParametricShape
import io.trazo.core.Tool
import io.trazo.core.withGestureExample
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

    /** Solo la página actual está decodificada; las demás quedan fijas hasta que se abren. */
    var notebook by mutableStateOf<LazyNotebook?>(null)
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
    private var saveJob: Job? = null
    /** Versión de los cambios y última versión confirmada en disco (abrir o mirar no modifica). */
    private var editVersion = 0L
    private var savedVersion = 0L
    private var attaching = false

    /**
     * Las últimas hojas visitadas con su historial, por id: volver a una hoja recupera su deshacer/rehacer.
     * Solo unas pocas, para no tener decodificada la tinta de todo el cuaderno.
     */
    private val sessions = object : LinkedHashMap<String, Pair<PageState, History>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<PageState, History>>) = size > PAGE_SESSIONS
    }

    val controller = InkController(patterns = { repo.patterns.value }, ids = IdGenerator.RANDOM).apply {
        val offset = System.currentTimeMillis() - android.os.SystemClock.uptimeMillis()
        strokeClock = { it + offset }
        addListener { e ->
            when (e) {
                InkEvent.PageChanged -> {
                    revision++; mathExplanation = null; if (!attaching) markDirty()
                    // Seguir escribiendo (o borrar, deshacer…) con la burbuja abierta conserva la escritura.
                    if (ambiguousArmed) { ambiguous = null; ambiguousArmed = false }
                }
                InkEvent.SelectionChanged -> { hasSelection = selection.isNotEmpty(); shapeSelected = selectedShapeId }
                is InkEvent.Converted -> lastConversion = e
                is InkEvent.Ambiguous -> { ambiguous = e; ambiguousArmed = false }
                is InkEvent.NotRecognized -> teachOffer = e
                is InkEvent.MathExplained -> mathExplanation = e.explanation
            }
        }
    }

    init {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { repo.loadLazy(notebookId) }
            notebook = n
            // Vuelve a la última página en la que se estuvo.
            if (n != null) attachPage(n.openPage)
            loading = false
        }
    }

    val pageCount: Int get() = notebook?.pageCount ?: 0
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

    /**
     * Abre la página [index]: se decodifica solo esa y las demás quedan fijas. Si se visitó hace poco,
     * vuelve con su historial ([sessions]). Recordar la última página es un cambio del cuaderno que se guarda.
     */
    private fun attachPage(index: Int) {
        val n = notebook ?: return
        val page = n.page(index)
        val (state, history) = sessions.getOrPut(page.id) { PageState(page.strokes, page.parametricShapes) to History() }
        val reopened = n.lastPage != index
        notebook = n.withPage(index, page).loadedOnly(index).copy(lastPage = index)
        pageIndex = index
        if (reopened) markDirty()
        attaching = true
        try {
            controller.attach(state, history)
        } finally {
            attaching = false
        }
    }

    fun goToPage(index: Int) {
        val n = notebook ?: return
        if (index !in 0 until n.pageCount || index == pageIndex) return
        syncNotebook()
        attachPage(index)
        viewport.scale = 0f // volver a encajar
    }

    /** Hoja nueva al final, o después de [after]. */
    fun addPage(after: Int? = null) {
        syncNotebook()
        val n = notebook ?: return
        val at = (after?.plus(1)) ?: n.pageCount
        notebook = n.withPageAdded(Page(IdGenerator.RANDOM.next()), at)
        attachPage(at)
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
        ambiguousArmed = false
        if (pattern != null) teach(pattern, a.gestureIds)
    }

    /** La burbuja de la ambigüedad ya está a la vista: el próximo cambio de la hoja la cierra. */
    private var ambiguousArmed = false

    fun ambiguousShown() { if (ambiguous != null) ambiguousArmed = true }

    /** Dónde quedó la punta al terminar el gesto dudoso (coordenadas de documento). */
    fun penOf(a: InkEvent.Ambiguous): Pair<Float, Float> =
        controller.strokesById(a.gestureIds)?.lastOrNull()?.points?.lastOrNull()?.let { it.x to it.y }
            ?: (a.anchor.right to a.anchor.top)

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

    /** Aviso pendiente cuando la ecuación solo tiene raíces imaginarias: el usuario decide si resolverla igual. */
    var complexPrompt by mutableStateOf<String?>(null)

    /** Calcula la selección y escribe el resultado; si no se puede, devuelve el motivo para mostrarlo. */
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

    /** Límites de la selección en documento, para ubicar el menú debajo. */
    val selectionBounds: io.trazo.core.Bounds? get() { revision; hasSelection; viewportTick; return controller.selectionBounds }

    /** Cambia al desplazar o hacer zoom: lo que flota sobre la hoja se reubica. */
    var viewportTick by mutableIntStateOf(0)

    /**
     * Copia la selección al portapapeles de Trazo (se pega en cualquier hoja o cuaderno) y su imagen al
     * del sistema, para pegarla en otras apps.
     */
    fun copySelection(onDone: (Boolean) -> Unit) {
        val strokes = controller.copySelection()
        if (strokes.isEmpty()) return
        TrazoClipboard.strokes = strokes
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(ctx.cacheDir, "clipboard").apply { mkdirs() }
                    val file = java.io.File(dir, "trazo-seleccion.png")
                    file.outputStream().use { PageExporter.writeStrokesPng(strokes, it) }
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                }.getOrNull()
            }
            if (uri != null) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                runCatching { cm?.setPrimaryClip(android.content.ClipData.newUri(ctx.contentResolver, "Trazo", uri)) }
            }
            onDone(uri != null)
        }
    }

    val canPaste: Boolean get() = TrazoClipboard.strokes.isNotEmpty()

    /** Pega lo copiado centrado en (x, y) de documento; queda seleccionado para moverlo. */
    fun paste(x: Float, y: Float) {
        val strokes = TrazoClipboard.strokes
        if (strokes.isEmpty()) return
        selectTool(EditorTool.LASSO)
        controller.pasteStrokes(strokes, x, y)
    }

    /** Pega en el centro de lo que se ve. */
    fun pasteInView() {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        paste((viewSize.first / 2f - viewport.tx) / vpScale, (viewSize.second / 2f - viewport.ty) / vpScale)
    }
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

    fun duplicatePage(index: Int = pageIndex) {
        val n = syncNotebook() ?: return
        notebook = n.withPageDuplicated(index)
        attachPage(index + 1)
        viewport.scale = 0f
        markDirty()
    }

    /** Elimina la página [index] (no se puede deshacer; la UI pide confirmación). */
    fun deletePage(index: Int = pageIndex) {
        val n = syncNotebook() ?: return
        if (n.pageCount < 2 || index !in 0 until n.pageCount) return
        val current = pageIndex
        sessions.remove(n.slots[index].id)
        notebook = n.withPageRemoved(index)
        when {
            index == current -> { attachPage(current.coerceAtMost(n.pageCount - 2)); viewport.scale = 0f }
            index < current -> { pageIndex = current - 1; notebook = notebook?.copy(lastPage = pageIndex) }
        }
        markDirty()
    }

    fun movePage(delta: Int) = movePage(pageIndex, pageIndex + delta)

    /** Cambia el orden de las hojas; la página abierta sigue abierta (con su historial). */
    fun movePage(from: Int, to: Int) {
        val n = syncNotebook() ?: return
        if (from !in 0 until n.pageCount || to !in 0 until n.pageCount || from == to) return
        notebook = n.withPageMoved(from, to)
        pageIndex = when {
            pageIndex == from -> to
            from < pageIndex && to >= pageIndex -> pageIndex - 1
            from > pageIndex && to <= pageIndex -> pageIndex + 1
            else -> pageIndex
        }
        notebook = notebook?.copy(lastPage = pageIndex)
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
        val page = n.page(pageIndex)
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

    // ---------------------------------------------------------------- miniaturas del panel de páginas

    /** Miniaturas por página, válidas mientras la página no cambie (misma [PageSlot]). */
    private val thumbs = object : LinkedHashMap<String, Pair<Any, ImageBitmap>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Any, ImageBitmap>>) = size > 80
    }

    /** Lo que identifica el contenido actual de la página [index] (la abierta cambia con cada trazo). */
    fun thumbKey(index: Int): Any? {
        val n = notebook ?: return null
        if (index !in 0 until n.pageCount) return null
        return if (index == pageIndex) revision else n.slots[index]
    }

    fun cachedThumb(index: Int): ImageBitmap? {
        val n = notebook ?: return null
        val key = thumbKey(index) ?: return null
        return thumbs[n.slots[index].id]?.takeIf { it.first == key }?.second
    }

    /** Dibuja la miniatura fuera del hilo principal: una página fija se decodifica solo para esto. */
    suspend fun thumbnail(index: Int, width: Int, colors: PaperColors): ImageBitmap? {
        cachedThumb(index)?.let { return it }
        val n = notebook ?: return null
        val key = thumbKey(index) ?: return null
        val id = n.slots[index].id
        val page = if (index == pageIndex) Page(id, controller.page.strokes.toList(), controller.page.shapes.toList()) else null
        val slot = n.slots[index]
        val bmp = withContext(Dispatchers.Default) {
            PageExporter.thumbnail(page ?: slot.page(), n.paper, width, colors).asImageBitmap()
        }
        thumbs[id] = key to bmp
        return bmp
    }

    /** Vuelca la página en edición al cuaderno (las demás no cambian: siguen fijas). */
    private fun syncNotebook(): LazyNotebook? {
        val n = notebook ?: return null
        val live = controller.page
        val page = Page(n.slots[pageIndex].id, live.strokes.toList(), live.shapes.toList())
        val updated = n.withPage(pageIndex, page).copy(updatedAt = System.currentTimeMillis())
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
            val saved = withContext(Dispatchers.IO) { runCatching { repo.saveLazy(snapshot) }.isSuccess }
            if (saved) savedVersion = maxOf(savedVersion, version)
        }
    }

    /** Guardado inmediato si hay cambios aún no confirmados en disco (al pasar a segundo plano o salir). */
    fun saveNow() {
        saveJob?.cancel()
        if (savedVersion == editVersion) return
        val version = editVersion
        syncNotebook()?.let { repo.saveLazy(it) }
        savedVersion = version
    }

    override fun onCleared() {
        if (!controller.isContactActive) saveNow()
        super.onCleared()
    }
}

/** Cuántas hojas recientes conservan su historial de deshacer. */
private const val PAGE_SESSIONS = 8
