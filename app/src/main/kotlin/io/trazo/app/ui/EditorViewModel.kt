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
import io.trazo.app.data.PageExporter
import io.trazo.app.data.TrazoSettings
import io.trazo.app.ink.Viewport
import io.trazo.app.trazo
import io.trazo.core.History
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.InkPattern
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.PageState
import io.trazo.core.Paper
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
    var tool by mutableStateOf(Tool.PEN)
        private set
    var ambiguous by mutableStateOf<InkEvent.Ambiguous?>(null)
    var lastConversion by mutableStateOf<InkEvent.Converted?>(null)
    /** Pausa sin coincidencia: se ofrece enseñar el gesto (aviso discreto). */
    var teachOffer by mutableStateOf<InkEvent.NotRecognized?>(null)
    /** Hoja abierta para elegir qué patrón era el gesto. */
    var teaching by mutableStateOf<InkEvent.NotRecognized?>(null)
    var hasSelection by mutableStateOf(false)
        private set
    /** Cambia cuando se pide volver a encajar la hoja; la vista lo observa. */
    var fitRequest by mutableIntStateOf(0)
        private set

    val viewport = Viewport()
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
                InkEvent.PageChanged -> { revision++; if (!attaching) markDirty() }
                InkEvent.SelectionChanged -> hasSelection = selection.isNotEmpty()
                is InkEvent.Converted -> lastConversion = e
                is InkEvent.Ambiguous -> ambiguous = e
                is InkEvent.NotRecognized -> teachOffer = e
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
        controller.holdMillis = s.holdMillis
        controller.tremorRadiusPx = s.tremorRadiusDp * density
        controller.eraserRadiusPx = s.eraserRadiusDp * density
        controller.minInkLengthPx = 12f * density
        controller.handleRadiusPx = 24f * density
        controller.recognitionEnabled = s.gesturesEnabled
        controller.color = s.penColor
        controller.width = s.penWidth
    }

    fun selectTool(t: Tool) { tool = t; controller.tool = t }

    fun undo() { controller.undo() }
    fun redo() { controller.redo() }
    val canUndo: Boolean get() { revision; return controller.history.canUndo }
    val canRedo: Boolean get() { revision; return controller.history.canRedo }
    /** Hay un trazo en curso: los diálogos esperan a que se levante el lápiz. */
    val isDrawing: Boolean get() { revision; return controller.isContactActive }

    private fun attachPage(index: Int) {
        val n = notebook ?: return
        val page = n.pages[index]
        val (state, history) = pages.getOrPut(page.id) { PageState(page.strokes) to History() }
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

    fun duplicateSelection() { controller.duplicateSelection() }
    fun recolorSelection(color: Int) { controller.recolorSelection(color) }
    fun selectAll() {
        selectTool(Tool.LASSO)
        controller.selectAll()
    }

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
                    out.use { if (pdf) PageExporter.writePdf(n, it) else PageExporter.writePng(page, n.paper, it) }
                }.exceptionOrNull()
            }
            onDone(error)
        }
    }

    /** Vuelca las páginas en edición al modelo inmutable. */
    private fun syncNotebook(): Notebook? {
        val n = notebook ?: return null
        val updated = n.copy(
            pages = n.pages.map { p -> pages[p.id]?.let { p.copy(strokes = it.first.strokes.toList()) } ?: p },
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
