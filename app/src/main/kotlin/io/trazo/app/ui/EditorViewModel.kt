package io.trazo.app.ui

import android.app.Application
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
import java.io.OutputStream

/**
 * Estado de un cuaderno abierto. El [controller] sobrevive a rotaciones; cada página tiene su
 * propio historial. Autoguardado atómico con espera corta tras cada cambio y al salir.
 */
class EditorViewModel(app: Application, val notebookId: String) : AndroidViewModel(app) {
    private val repo = app.trazo.repository
    private val settings = app.trazo.settings

    var notebook by mutableStateOf(repo.loadNotebook(notebookId))
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

    val controller = InkController(patterns = { repo.patterns.value }, ids = IdGenerator.RANDOM).apply {
        val offset = System.currentTimeMillis() - android.os.SystemClock.uptimeMillis()
        strokeClock = { it + offset }
        addListener { e ->
            when (e) {
                InkEvent.PageChanged -> { revision++; scheduleSave() }
                InkEvent.SelectionChanged -> hasSelection = selection.isNotEmpty()
                is InkEvent.Converted -> lastConversion = e
                is InkEvent.Ambiguous -> ambiguous = e
                is InkEvent.NotRecognized -> teachOffer = e
            }
        }
    }

    init {
        notebook?.let { attachPage(0) }
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
        controller.attach(state, history)
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
        scheduleSave()
    }

    fun setPaper(p: Paper) {
        notebook = notebook?.copy(paper = p)
        revision++
        scheduleSave()
    }

    fun rename(name: String) {
        notebook = notebook?.copy(name = name)
        scheduleSave()
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
        scheduleSave()
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
        scheduleSave()
    }

    fun movePage(delta: Int) {
        val n = syncNotebook() ?: return
        val to = pageIndex + delta
        if (to !in n.pages.indices) return
        notebook = n.withPageMoved(pageIndex, to)
        attachPage(to)
        scheduleSave()
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

    /** PDF vectorial de todo el cuaderno (para compartir; el formato editable es `.trazo`). */
    fun exportPdf(out: OutputStream) {
        val n = syncNotebook() ?: return
        PageExporter.writePdf(n, out)
    }

    fun exportPagePng(out: OutputStream) {
        val n = syncNotebook() ?: return
        PageExporter.writePng(n.pages[pageIndex], n.paper, out)
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

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            val snapshot = syncNotebook() ?: return@launch
            withContext(Dispatchers.IO) { repo.saveNotebook(snapshot) }
        }
    }

    /** Guardado inmediato (al pasar a segundo plano o salir). */
    fun saveNow() {
        saveJob?.cancel()
        syncNotebook()?.let { repo.saveNotebook(it) }
    }

    override fun onCleared() {
        if (!controller.isContactActive) saveNow()
        super.onCleared()
    }
}
