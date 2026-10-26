package io.trazo.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val settingsStore = app.trazo.settings

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
    var hasSelection by mutableStateOf(false)
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
                InkEvent.NotRecognized -> Unit
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

    fun chooseAmbiguous(pattern: InkPattern?) {
        val a = ambiguous ?: return
        ambiguous = null
        if (pattern != null) controller.chooseAmbiguous(pattern, a.gestureIds)
    }

    /** Inserta la tinta de salida de un patrón en el centro de la zona visible. */
    fun insertPattern(pattern: InkPattern, viewWidth: Int, viewHeight: Int) {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        val cx = ((viewWidth / 2f - viewport.tx) / vpScale).coerceIn(80f, Notebook.PAGE_WIDTH - 80f)
        val cy = ((viewHeight / 2f - viewport.ty) / vpScale).coerceIn(80f, Notebook.PAGE_HEIGHT - 80f)
        controller.insertPattern(pattern, cx, cy, 140f, android.os.SystemClock.uptimeMillis())
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
