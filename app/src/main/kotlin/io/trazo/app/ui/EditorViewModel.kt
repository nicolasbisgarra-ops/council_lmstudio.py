package io.trazo.app.ui

import android.app.Application
import android.graphics.BitmapFactory
import io.trazo.ai.AiReviewPrompt
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
import io.trazo.ai.AiException
import io.trazo.ai.AiClient
import io.trazo.ai.AiProvider
import io.trazo.ai.AiMark
import io.trazo.ai.AiProfile
import io.trazo.ai.AiReview
import io.trazo.ai.AiOAuth
import io.trazo.ai.AiReviewer
import io.trazo.ai.DurableReviewQueue
import io.trazo.ai.DurableReviewLease
import io.trazo.ai.PendingRemoteReview
import io.trazo.ai.ReviewBackend
import io.trazo.ai.ReviewCancelHandle
import io.trazo.ai.ReviewLifecycleGate
import io.trazo.ai.ReviewScope
import io.trazo.ai.ReviewTicket
import io.trazo.ai.MarkKind
import io.trazo.ai.AiTutor
import io.trazo.ai.AiTutorPrompt
import io.trazo.ai.TutorRole
import io.trazo.ai.TutorTurn
import io.trazo.app.data.AiChatMessage
import io.trazo.app.ai.AiCapture
import io.trazo.app.ai.AiConnectivity
import io.trazo.app.ai.AiMarkPainter
import io.trazo.app.ai.LocalCompletion
import io.trazo.app.data.StudyFeature
import io.trazo.app.data.AiQueued
import io.trazo.app.data.AiSend
import io.trazo.app.data.EraserMode
import io.trazo.app.data.PageExporter
import io.trazo.app.data.SideButtonAction
import io.trazo.app.data.TrazoSettings
import io.trazo.app.data.imagePreset
import io.trazo.app.data.highlighterInk
import io.trazo.app.ink.PaperColors
import io.trazo.app.ink.Viewport
import io.trazo.app.trazo
import io.trazo.core.AiNote
import io.trazo.core.AiNotes
import io.trazo.core.AiReviewRecord
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
import io.trazo.core.PageAi
import io.trazo.core.PageImage
import io.trazo.core.PageImages
import io.trazo.core.ImagePreset
import io.trazo.core.PageState
import io.trazo.core.Paper
import io.trazo.core.ParametricInk
import io.trazo.core.ParametricShape
import io.trazo.core.Tool
import io.trazo.core.TutorText
import io.trazo.core.bounds
import io.trazo.core.moveItem
import io.trazo.core.withGestureExample
import io.trazo.core.study.ExamHelp
import io.trazo.core.study.ExamAccessGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Herramienta de la barra. El resaltador es la pluma con su propia punta, color y grosor. */
enum class EditorTool { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, PAN }

/** Injectable review boundary for deterministic tests; production uses the configured AiReviewer. */
typealias AiReviewCall = (AiProfile, String?, List<String>, ByteArray?, (AiMark) -> Unit) -> AiReview

/**
 * Estado de un cuaderno abierto. El [controller] sobrevive a rotaciones; cada página tiene su
 * propio historial. Autoguardado atómico con espera corta tras cada cambio y al salir.
 */
class EditorViewModel(
    private val app: Application,
    val notebookId: String,
    private val aiReviewCall: AiReviewCall? = null,
    private val remoteOnlineOverride: (() -> Boolean)? = null,
    /** Hoja con que se abre (un resultado de búsqueda); null = la última abierta. */
    private val startPage: Int? = null,
) : AndroidViewModel(app) {
    private val repo = app.trazo.repository.also { it.markOpen(notebookId) }
    private val settings = app.trazo.settings

    /** Solo la página actual está decodificada; las demás quedan fijas hasta que se abren. */
    var notebook by mutableStateOf<LazyNotebook?>(null)
        private set
    /** El cuaderno se lee fuera del hilo principal; mientras tanto la pantalla muestra un indicador. */
    var loading by mutableStateOf(true)
        private set
    var pageLoadError by mutableStateOf<String?>(null)
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
    private var attachedPageId: String? = null

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
        paperOf = { this@EditorViewModel.paper }
        addListener { e ->
            when (e) {
                InkEvent.PageChanged -> {
                    revision++; mathExplanation = null; if (!attaching) markDirty()
                    // Una marca de la IA se va sola si cambian los trazos de su renglón.
                    AiNotes.prune(page.ai, page.allInk).let { if (it !== page.ai) setAi(it) }
                    activeReviewTicket?.let { ticket ->
                        reviewScopeForCurrentPage()?.let { actual ->
                            if (actual.notebookId == ticket.scope.notebookId && actual.pageId == ticket.scope.pageId && actual != ticket.scope) {
                                cancelAiReview()
                                aiProgress = AiProgress(false, message = "La página cambió durante la revisión.", error = true)
                            } else {
                                reviewGate.contentChanged(actual)
                            }
                        }
                    }
                    discussionScope?.let { old ->
                        val current = reviewScopeForCurrentPage()
                        if (current != old) cancelDiscussion()
                    }
                    // Seguir escribiendo (o borrar, deshacer…) con la burbuja abierta conserva la escritura.
                    if (ambiguousArmed) { ambiguous = null; ambiguousArmed = false }
                }
                InkEvent.SelectionChanged -> { hasSelection = selection.isNotEmpty(); shapeSelected = selectedShapeId }
                is InkEvent.Converted -> lastConversion = e
                is InkEvent.Ambiguous -> { ambiguous = e; ambiguousArmed = false }
                is InkEvent.NotRecognized -> teachOffer = e
                is InkEvent.MathExplained -> mathExplanation = e.explanation
                is InkEvent.AiNoteTapped -> { aiNote = e.note; aiAnswer = null }
                InkEvent.AiNotesChanged -> {
                    if (page.ai !== lastAi) { lastAi = page.ai; if (!attaching) markDirty() }
                    aiNote = aiNote?.let { open -> page.ai?.notes?.firstOrNull { it.id == open.id } }
                    aiRevision++
                }
                is InkEvent.ScratchErased -> Unit
            }
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
        controller.scratchToErase = s.scratchToErase
        controller.autoAlign = s.autoAlign
        controller.rows = s.rows
        controller.symbolColors = s.symbolColors
        controller.mathEnabled = s.mathNotes && app.trazo.study.isAllowed(StudyFeature.CALCULATOR)
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
    private fun attachPage(index: Int, hardReset: Boolean = false): Boolean {
        val n = notebook ?: return false
        if (index !in n.slots.indices) return false
        val page = try {
            n.page(index)
        } catch (e: Exception) {
            pageLoadError = e.message ?: e.javaClass.simpleName
            return false
        }
        val (state, history) = sessions.getOrPut(page.id) { PageState(page.strokes, page.parametricShapes, page.images).apply { ai = page.ai } to History() }
        if (attachedPageId != null && attachedPageId != page.id) cancelAiForPageSwitch()
        val reopened = n.lastPage != index
        notebook = n.withPage(index, page).loadedOnly(index).copy(lastPage = index)
        pageIndex = index
        if (reopened) markDirty()
        attaching = true
        try {
            controller.attach(state, history)
            lastAi = state.ai
        } finally {
            attaching = false
        }
        attachedPageId = page.id
        loadChat(page.id)
        pageLoadError = null
        reviewScopeForCurrentPage()?.let { scope -> if (hardReset) reviewGate.openPage(scope) else reviewGate.navigateTo(scope) }
        aiNote = null
        aiAnswer = null
        return true
    }

    fun goToPage(index: Int) {
        val n = notebook ?: return
        if (index !in 0 until n.pageCount || index == pageIndex) return
        syncNotebook()
        if (!attachPage(index)) return
        viewport.scale = 0f // volver a encajar
    }

    /** Hoja nueva al final, o después de [after]. */
    fun addPage(after: Int? = null) {
        syncNotebook()
        val n = notebook ?: return
        val at = (after?.plus(1)) ?: n.pageCount
        notebook = n.withPageAdded(Page(IdGenerator.RANDOM.next()), at)
        if (!attachPage(at)) return
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
        if (!app.trazo.study.isAllowed(StudyFeature.CALCULATOR)) return "La calculadora está bloqueada durante el examen."
        if (controller.calculateSelection()) return null
        if (controller.calculationOnlyComplex()) {
            complexPrompt = controller.calculationProblem()
            return null
        }
        return controller.calculationProblem()
    }

    fun calculateWithImaginaries() {
        complexPrompt = null
        if (!app.trazo.study.isAllowed(StudyFeature.CALCULATOR)) return
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
                runCatching { cm?.setPrimaryClip(android.content.ClipData.newUri(ctx.contentResolver, CLIP_LABEL, uri)) }
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

    // ------------------------------------------------------------------ imágenes

    /** Imagen abierta en el editor de recorte: la original, una copia chica para la vista previa y su destino. */
    class ImageEditSession(
        val source: android.graphics.Bitmap,
        val preview: android.graphics.Bitmap,
        /** Archivo ya guardado de la original (al volver a editar una imagen de la hoja). */
        val sourceName: String?,
        val existing: PageImage?,
        val at: Pair<Float, Float>?,
        val quad: List<Float>,
        val preset: ImagePreset,
    )

    var imageEdit by mutableStateOf<ImageEditSession?>(null)
        private set
    var imageBusy by mutableStateOf(false)
        private set
    /** Aviso de un fallo al leer o guardar una imagen (la pantalla lo muestra y lo limpia). */
    var imageMessage by mutableStateOf<String?>(null)

    /** La única imagen elegida con el lazo (para «Recortar y ajustar»). */
    val selectedImage: PageImage? get() { revision; hasSelection; return controller.selectedImage }

    private fun viewCenter(): Pair<Float, Float> {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        return (viewSize.first / 2f - viewport.tx) / vpScale to (viewSize.second / 2f - viewport.ty) / vpScale
    }

    /** Punto de documento bajo (x, y) de la vista. */
    fun viewToDoc(x: Float, y: Float): Pair<Float, Float> {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        return (x - viewport.tx) / vpScale to (y - viewport.ty) / vpScale
    }

    /** Regla o transportador que flota sobre la hoja (null si no hay). */
    var rulerKind by mutableStateOf<io.trazo.core.Ruler.Kind?>(null)
        private set

    /** Pone la herramienta en el centro de la vista, o la quita si ya estaba. */
    fun toggleRuler(kind: io.trazo.core.Ruler.Kind) {
        if (controller.ruler?.kind == kind) {
            controller.ruler = null; rulerKind = null
        } else {
            val (cx, cy) = viewCenter()
            controller.ruler = io.trazo.core.Ruler(kind, cx, cy); rulerKind = kind
        }
    }

    private fun storeImage(bmp: android.graphics.Bitmap): String {
        val bytes = io.trazo.app.data.ImageImport.encode(bmp) ?: error("La imagen es demasiado grande")
        return repo.storeImage(bytes).also { io.trazo.app.image.ImagePainter.stored(it) }
    }

    /** Abre una imagen de la galería o de archivos en el editor de recorte, con el ajuste predeterminado. */
    fun openImageEditor(open: () -> java.io.InputStream, darkPaper: Boolean, at: Pair<Float, Float>? = null) {
        if (imageBusy) return
        imageBusy = true
        viewModelScope.launch {
            val src = withContext(Dispatchers.IO) { runCatching { io.trazo.app.data.ImageImport.decode(open) }.getOrNull() }
            imageBusy = false
            if (src == null) { imageMessage = "No se pudo leer la imagen"; return@launch }
            imageEdit = ImageEditSession(
                src, io.trazo.app.data.ImageImport.scaledDown(src, PREVIEW_SIDE), null, null, at,
                PageImages.FULL_QUAD, lastSettings.imagePreset(darkPaper),
            )
        }
    }

    /** Vuelve a abrir la imagen elegida desde su original, con su recorte y ajuste. */
    fun editSelectedImage() {
        val img = selectedImage ?: return
        if (imageBusy) return
        val name = img.source ?: img.asset
        val file = io.trazo.app.image.ImagePainter.file(name) ?: return
        imageBusy = true
        viewModelScope.launch {
            val src = withContext(Dispatchers.IO) { io.trazo.app.data.ImageImport.decodeFile(file.path) }
            imageBusy = false
            if (src == null) { imageMessage = "No se encontró la imagen original"; return@launch }
            imageEdit = ImageEditSession(
                src, io.trazo.app.data.ImageImport.scaledDown(src, PREVIEW_SIDE), name, img, null,
                img.quad?.takeIf { PageImages.isValidQuad(it) } ?: PageImages.FULL_QUAD,
                if (img.source == null) ImagePreset.NONE else img.preset,
            )
        }
    }

    /** Los mapas de bits los libera el recolector: la vista previa puede estar dibujándolos todavía. */
    fun cancelImageEdit() { imageEdit = null }

    /** Vista previa del recorte y el ajuste sobre la copia chica. */
    suspend fun previewImage(s: ImageEditSession, quad: List<Float>, preset: ImagePreset): android.graphics.Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching { io.trazo.app.data.ImageImport.process(s.preview, quad, preset, PREVIEW_SIDE) }.getOrNull()
        }

    /** Aplica el recorte y el ajuste a la original y pone (o reemplaza) la imagen en la hoja. */
    fun finishImageEdit(quad: List<Float>, preset: ImagePreset) {
        val s = imageEdit ?: return
        if (imageBusy) return
        imageBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val q = quad.takeIf { PageImages.isValidQuad(it) } ?: PageImages.FULL_QUAD
                    val out = io.trazo.app.data.ImageImport.process(s.source, q, preset)
                    try {
                        val plain = preset == ImagePreset.NONE && q == PageImages.FULL_QUAD
                        val asset = storeImage(out)
                        val source = when {
                            plain && s.sourceName == null -> null
                            s.sourceName != null -> s.sourceName
                            else -> storeImage(s.source)
                        }
                        Triple(asset, source.takeIf { it != asset }, Triple(out.width, out.height, q))
                    } finally {
                        out.recycle()
                    }
                }
            }
            imageBusy = false
            result.onFailure { imageMessage = "No se pudo guardar la imagen: ${it.message ?: it.javaClass.simpleName}" }
            result.onSuccess { (asset, source, info) ->
                val (w, h, q) = info
                placeImage(s.existing, s.at, asset, source, w, h, preset, q)
                cancelImageEdit()
            }
        }
    }

    private fun placeImage(
        existing: PageImage?, at: Pair<Float, Float>?, asset: String, source: String?,
        w: Int, h: Int, preset: ImagePreset, quad: List<Float>,
    ) {
        val storedQuad = quad.takeIf { source != null && it != PageImages.FULL_QUAD }
        val storedPreset = if (source == null) ImagePreset.NONE else preset
        if (existing != null) {
            // Conserva el centro y el ancho; el alto sigue la nueva proporción.
            val b = existing.bounds
            val bw = b.width
            val bh = bw * h / w.coerceAtLeast(1)
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            controller.replaceImage(existing.copy(
                asset = asset, source = source, preset = storedPreset, quad = storedQuad,
                left = cx - bw / 2f, top = cy - bh / 2f, right = cx + bw / 2f, bottom = cy + bh / 2f,
            ))
        } else {
            val (cx, cy) = at ?: viewCenter()
            val b = PageImages.placement(w, h, cx, cy)
            selectTool(EditorTool.LASSO)
            controller.insertImage(PageImage(
                IdGenerator.RANDOM.next(), asset, b.left, b.top, b.right, b.bottom,
                preset = storedPreset, source = source, quad = storedQuad,
            ))
        }
    }

    /** Pega o suelta una imagen sin abrir el editor: va con el ajuste predeterminado de esta hoja. */
    fun insertImageDirect(open: () -> java.io.InputStream, darkPaper: Boolean, at: Pair<Float, Float>? = null, onDone: () -> Unit = {}) {
        if (imageBusy) { onDone(); return }
        imageBusy = true
        val preset = lastSettings.imagePreset(darkPaper)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val src = io.trazo.app.data.ImageImport.decode(open) ?: error("No es una imagen")
                    try {
                        val out = io.trazo.app.data.ImageImport.process(src, PageImages.FULL_QUAD, preset)
                        try {
                            val asset = storeImage(out)
                            val source = if (preset == ImagePreset.NONE) null else storeImage(src)
                            Triple(asset, source.takeIf { it != asset }, out.width to out.height)
                        } finally { out.recycle() }
                    } finally { src.recycle() }
                }
            }
            imageBusy = false
            onDone()
            result.onFailure { imageMessage = "No se pudo pegar la imagen: ${it.message ?: it.javaClass.simpleName}" }
            result.onSuccess { (asset, source, size) ->
                placeImage(null, at, asset, source, size.first, size.second, preset, PageImages.FULL_QUAD)
            }
        }
    }

    private val clipboard get() = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)

    /** Hay una imagen de otra app en el portapapeles del sistema (lo copiado en Trazo se pega como tinta). */
    val canPasteImage: Boolean get() = runCatching {
        val d = clipboard?.primaryClipDescription ?: return@runCatching false
        d.hasMimeType("image/*") && d.label?.toString() != CLIP_LABEL
    }.getOrDefault(false)

    /** Pega la imagen del portapapeles del sistema en (x, y) de documento, o en el centro de la vista. */
    fun pasteSystemImage(darkPaper: Boolean, at: Pair<Float, Float>? = null): Boolean {
        val ctx = getApplication<Application>()
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return false
        val uri = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .firstOrNull { it.authority != "${ctx.packageName}.files" } ?: return false
        insertImageDirect({ ctx.contentResolver.openInputStream(uri) ?: error("No se pudo abrir la imagen") }, darkPaper, at)
        return true
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

    val pageIsEmpty: Boolean get() { revision; return controller.page.strokes.isEmpty() && controller.page.shapes.isEmpty() && controller.page.images.isEmpty() }

    fun duplicatePage(index: Int = pageIndex) {
        val n = syncNotebook() ?: return
        val duplicated = try { n.withPageDuplicated(index) } catch (e: Exception) {
            pageLoadError = e.message ?: e.javaClass.simpleName
            return
        }
        notebook = duplicated
        if (!attachPage(index + 1)) return
        viewport.scale = 0f
        markDirty()
    }

    /** Elimina la página [index] (no se puede deshacer; la UI pide confirmación). */
    fun deletePage(index: Int = pageIndex) {
        val n = syncNotebook() ?: return
        if (n.pageCount < 2 || index !in 0 until n.pageCount) return
        if (index == pageIndex) {
            val replacementIndex = pageIndex.coerceAtMost(n.pageCount - 2)
            try { n.withPageRemoved(index).page(replacementIndex) }
            catch (e: Exception) { pageLoadError = e.message ?: e.javaClass.simpleName; return }
        }
        val deletedPageId = n.slots[index].id
        val current = pageIndex
        sessions.remove(n.slots[index].id)
        notebook = n.withPageRemoved(index)
        when {
            index == current -> {
                if (!attachPage(current.coerceAtMost(n.pageCount - 2))) return
                viewport.scale = 0f
            }
            index < current -> { pageIndex = current - 1; notebook = notebook?.copy(lastPage = pageIndex) }
        }
        reviewGate.contentChanged(ReviewScope(notebookId, deletedPageId, "deleted"))
        markDirty()
    }

    fun movePage(delta: Int) = movePage(pageIndex, pageIndex + delta)

    /** Cambia el orden de las hojas; la página abierta sigue abierta (con su historial). */
    fun movePage(from: Int, to: Int, deferSave: Boolean = false) {
        // Durante el gesto la tinta viva sigue en el controlador: no la copies en cada cruce.
        val n = (if (deferSave) notebook else syncNotebook()) ?: return
        if (from !in 0 until n.pageCount || to !in 0 until n.pageCount || from == to) return
        notebook = n.copy(slots = moveItem(n.slots, from, to))
        pageIndex = when {
            pageIndex == from -> to
            from < pageIndex && to >= pageIndex -> pageIndex - 1
            from > pageIndex && to <= pageIndex -> pageIndex + 1
            else -> pageIndex
        }
        notebook = notebook?.copy(lastPage = pageIndex)
        if (deferSave) {
            editVersion++
            saveJob?.cancel()
        } else markDirty()
    }

    /** Al terminar el gesto, el guardado habitual vuelca la tinta y el orden juntos. */
    fun persistPageOrder() { scheduleSave() }

    /** Inserta la tinta de salida de un patrón en el centro de la zona visible. */
    fun insertPattern(pattern: InkPattern, viewWidth: Int, viewHeight: Int) {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        val cx = ((viewWidth / 2f - viewport.tx) / vpScale).coerceIn(-Notebook.PAGE_WIDTH + 80f, 2f * Notebook.PAGE_WIDTH - 80f)
        val cy = ((viewHeight / 2f - viewport.ty) / vpScale).coerceIn(-Notebook.PAGE_HEIGHT + 80f, 2f * Notebook.PAGE_HEIGHT - 80f)
        controller.insertPattern(pattern, cx, cy, 140f, android.os.SystemClock.uptimeMillis())
    }

    // ---------------------------------------------------------------- formas dinámicas

    val selectedShape: ParametricShape? get() { revision; shapeSelected; return controller.selectedShape }

    /** Tamaño en px del lienzo; lo informa la pantalla para centrar lo que se inserta. */
    var viewSize = 0 to 0

    /** Inserta una forma dinámica en el centro de la zona visible, ya seleccionada para editar. */
    fun insertParametric(kind: GeoKind, viewWidth: Int = viewSize.first, viewHeight: Int = viewSize.second) {
        val vpScale = viewport.scale.takeIf { it > 0f } ?: 1f
        val cx = ((viewWidth / 2f - viewport.tx) / vpScale).coerceIn(-Notebook.PAGE_WIDTH + 100f, 2f * Notebook.PAGE_WIDTH - 100f)
        val cy = ((viewHeight / 2f - viewport.ty) / vpScale).coerceIn(-Notebook.PAGE_HEIGHT + 100f, 2f * Notebook.PAGE_HEIGHT - 100f)
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
    fun exportTo(resolver: ContentResolver, uri: Uri, pdf: Boolean, includeAi: Boolean, clean: Boolean = false, onDone: (Throwable?) -> Unit) {
        val n = syncNotebook() ?: return
        val page = n.page(pageIndex)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val out = resolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
                    out.use {
                        if (pdf) PageExporter.writePdf(n, it, ::mathResults, includeAi, clean)
                        else PageExporter.writePng(page, n.paper, it, results = mathResults(page), includeAi = includeAi)
                    }
                }.exceptionOrNull()
            }
            onDone(error)
        }
    }

    // ---------------------------------------------------------------- Modo IA

    /** Cómo va la revisión en curso (o cómo terminó), para la barra de progreso. */
    data class AiProgress(
        val running: Boolean,
        val lines: Int = 0,
        val marks: Int = 0,
        val message: String? = null,
        val error: Boolean = false,
    )

    private val ai = app.trazo.ai
    private val journal = app.trazo.aiJournal
    private val localCompletion = LocalCompletion(app)
    private val reviewer = AiReviewer(AiClient(local = localCompletion))
    private val reviewGate = ReviewLifecycleGate()
    private var durableReviews: DurableReviewQueue? = null
    private var reviewQueueProblem: String? = null
    private var activeReviewTicket: ReviewTicket? = null
    private var queueClaimJob: Job? = null
    private var discussionJob: Job? = null
    private var discussionId = 0L
    private var discussionScope: ReviewScope? = null
    private val connectivity by lazy { AiConnectivity(app) { /* Connectivity never starts a review. */ } }
    private var aiJob: Job? = null
    /** Las marcas que ya se guardaron: si cambian, la hoja cambió. */
    private var lastAi: PageAi? = null

    private fun reviewScope(pageId: String, page: Page): ReviewScope =
        ReviewScope(notebookId, pageId, reviewFingerprint(page.allInk))

    private fun reviewFingerprint(strokes: List<InkStroke>): String {
        val canonical = strokes.joinToString("\n") { s ->
            buildString {
                append(s.id).append('|').append(s.color).append('|').append(s.width.toBits())
                    .append('|').append(s.startTime).append('|').append(s.groupId).append('|')
                    .append(s.patternId).append('|').append(s.brush.name).append('|').append(s.calc)
                s.points.forEach { p -> append('|').append(p.x.toBits()).append(',').append(p.y.toBits())
                    .append(',').append(p.pressure.toBits()).append(',').append(p.timeMs) }
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun pageForReview(pageId: String): Page? {
        val n = notebook ?: return null
        val index = n.slots.indexOfFirst { it.id == pageId }
        if (index < 0) return null
        if (index == pageIndex && attachedPageId == pageId) {
            return controller.page.toPage(pageId)
        }
        sessions[pageId]?.first?.let { state ->
            return state.toPage(pageId)
        }
        return runCatching { n.page(index) }.getOrNull()
    }

    private fun reviewScopeForCurrentPage(): ReviewScope? {
        val id = notebook?.slots?.getOrNull(pageIndex)?.id ?: return null
        val page = pageForReview(id) ?: return null
        return reviewScope(id, page)
    }

    /** Marca tocada: abre su burbuja. */
    var aiNote by mutableStateOf<AiNote?>(null)
    /** La respuesta del modelo a la última discusión de [aiNote]. */
    var aiAnswer by mutableStateOf<String?>(null)
        private set
    var aiDiscussing by mutableStateOf(false)
        private set
    var aiProgress by mutableStateOf<AiProgress?>(null)
        private set
    /** Cambia cuando cambian las marcas de la hoja o su visibilidad. */
    var aiRevision by mutableIntStateOf(0)
        private set
    /** La capa de marcas se ve (el ojo la oculta). */
    var aiVisible by mutableStateOf(true)
        private set

    private fun remoteOnline(): Boolean = remoteOnlineOverride?.invoke() ?: connectivity.online

    val aiNotes: List<AiNote> get() { aiRevision; revision; return controller.page.ai?.notes.orEmpty() }

    /** Revisiones en cola sin conexión para este cuaderno. */
    val aiPending: Int get() { aiRevision; return durableReviews?.pendingCount(notebookId) ?: journal.pending(notebookId) }

    /** Ya usó el Modo IA: al exportar se pregunta si van las marcas. */
    val usesAi: Boolean get() = aiNotes.isNotEmpty() || journal.log.value.isNotEmpty()

    fun toggleAiVisible() {
        aiVisible = !aiVisible
        controller.aiVisible = aiVisible
        if (!aiVisible) aiNote = null
    }

    fun dismissAiProgress() { if (aiProgress?.running != true) aiProgress = null }

    fun cancelAiReview() {
        activeReviewTicket?.let(reviewGate::cancel)
        activeReviewTicket = null
        localCompletion.cancel()
        queueClaimJob?.cancel()
        queueClaimJob = null
        aiJob?.cancel()
        aiJob = null
        val id = pendingReview
        if (id.isNotEmpty()) updateAi(pendingPage) { a -> a?.copy(notes = a.notes.filterNot { it.reviewId == id }) }
        activeQueueLease?.let(::releaseQueueLease)
        activeQueueLease = null
        pendingReview = ""
        aiProgress = AiProgress(running = false, message = "Revisión cancelada.")
    }

    private fun cancelDiscussion() {
        discussionId++
        discussionJob?.cancel()
        discussionJob = null
        discussionScope = null
        localCompletion.cancel()
        aiDiscussing = false
        aiAnswer = null
    }

    private fun cancelAiForPageSwitch() {
        if (aiJob?.isActive == true || queueClaimJob?.isActive == true || activeReviewTicket != null) {
            cancelAiReview()
        }
        if (aiDiscussing || discussionJob?.isActive == true) cancelDiscussion()
        if (chatBusy) cancelChat()
    }

    /** La revisión en curso y su hoja: sus marcas parciales se descartan si se cancela. */
    private var pendingReview = ""
    private var pendingPage = ""
    private var activeQueueLease: DurableReviewLease? = null

    /**
     * Manda la hoja (o lo elegido con el lazo) al modelo en uso. Las marcas aparecen a medida que llegan;
     * al terminar se cruzan con el motor de Trazo y quedan guardadas en la hoja. Los pedidos sin conexión
     * quedan pendientes y solo se envían cuando el usuario vuelve a tocar Revisar página.
     */
    fun reviewWithAi(selectionOnly: Boolean = false) {
        val n = notebook ?: return
        if (attachedPageId != n.slots.getOrNull(pageIndex)?.id) return
        val pageId = n.slots[pageIndex].id
        val shapeIds = controller.selectedShapeId?.let { id ->
            controller.page.allInk.asSequence()
                .filter { it.groupId == ParametricInk.groupOf(id) }
                .map { it.id }
                .toSet()
        }.orEmpty()
        val only = if (selectionOnly) (controller.selection.toSet() + shapeIds).takeIf { it.isNotEmpty() } else null
        val currentProfile = ai.settings.value.current
        if (currentProfile != null && currentProfile.provider != AiProvider.ON_DEVICE && remoteOnline()) {
            sendQueued(pageId, only) { review(pageId, only) }
        } else {
            review(pageId, only)
        }
    }

    private fun review(pageId: String, only: Set<String>?, queuedLease: DurableReviewLease? = null) {
        if (!app.trazo.study.isAllowed(StudyFeature.AI_REVIEW)) {
            queuedLease?.let(::releaseQueueLease)
            aiProgress = AiProgress(false, message = "El Modo IA está bloqueado durante el examen.", error = true)
            return
        }
        if (aiJob?.isActive == true) { queuedLease?.let(::releaseQueueLease); return }
        val n = notebook ?: run { queuedLease?.let(::releaseQueueLease); return }
        val profile = ai.settings.value.current
        if (profile == null) { queuedLease?.let(::releaseQueueLease); aiProgress = AiProgress(false, message = "Configure un modelo en Ajustes → Modo IA.", error = true); return }
        profile.problem?.let { queuedLease?.let(::releaseQueueLease); aiProgress = AiProgress(false, message = it, error = true); return }
        if (queuedLease != null && (profile.provider == AiProvider.ON_DEVICE || !remoteOnline())) { releaseQueueLease(queuedLease); return }
        if (profile.provider.signIn && ai.session(profile.id) == null) {
            queuedLease?.let(::releaseQueueLease)
            aiProgress = AiProgress(false, message = "Falta iniciar sesión en «${profile.name}» (Ajustes → Modo IA).", error = true)
            return
        }
        val permit = app.trazo.study.issueAid(ExamHelp.AI_REVIEW)
        if (permit == null) { queuedLease?.let(::releaseQueueLease); return }
        val pageState = pageForReview(pageId) ?: run { queuedLease?.let(::releaseQueueLease); return }
        val strokes = pageState.allInk
        val scope = reviewScope(pageId, pageState)
        if (queuedLease != null && scope.fingerprint != queuedLease.request.fingerprint) {
            viewModelScope.launch(Dispatchers.IO) {
                durableReviews?.acknowledge(queuedLease, successfullyCommitted = true)
                withContext(Dispatchers.Main.immediate) { aiRevision++ }
            }
            return
        }
        val reviewId = queuedLease?.let { stableQueueReviewId(it.request) } ?: IdGenerator.RANDOM.next()
        if (queuedLease != null && pageState.ai?.history?.any { it.id == reviewId } == true) {
            val snapshot = syncNotebook()
            if (snapshot == null) { releaseQueueLease(queuedLease); return }
            viewModelScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { durableReviews?.completeAfterPersist(queuedLease) { repo.saveLazy(snapshot) } == true }.getOrDefault(false)
                }
                if (saved) { aiRevision++ } else releaseQueueLease(queuedLease)
            }
            return
        }
        val lines = MathNotes.reviewLabels(strokes, repo.patterns.value, only)
        if (lines.isEmpty()) { queuedLease?.let(::releaseQueueLease); aiProgress = AiProgress(false, message = "No hay nada escrito para revisar.", error = true); return }
        val lineIds = lines.flatMap { it.strokeIds }
        if (profile.provider != AiProvider.ON_DEVICE && !remoteOnline()) {
            if (queuedLease == null) queue(pageId, only, permit) else releaseQueueLease(queuedLease)
            return
        }
        val ticket = reviewGate.startFor(
            scope,
            if (profile.provider == AiProvider.ON_DEVICE) ReviewBackend.ON_DEVICE else ReviewBackend.REMOTE,
            ReviewCancelHandle { localCompletion.cancel(); aiJob?.cancel() },
        )
        if (ticket == null) { queuedLease?.let(::releaseQueueLease); return }
        activeReviewTicket = ticket
        activeQueueLease = queuedLease
        pendingReview = reviewId
        pendingPage = pageId
        val pageNumber = (n.slots.indexOfFirst { it.id == pageId } + 1).coerceAtLeast(1)
        aiProgress = AiProgress(running = true, lines = lines.size)
        aiJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val captured = strokes.filter { it.id in lineIds.toSet() }
            var imageBytes = 0
            var imageWidth: Int? = null
            var imageHeight: Int? = null
            val textChars = AiReviewPrompt.systemInstructions(profile.instructions).length +
                AiReviewPrompt.userText(lines.map { it.text }, profile.rigor).length
            var streamed = 0
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val png = if (profile.provider == AiProvider.ON_DEVICE && profile.localTextOnly) null
                        else AiCapture.png(captured, lines, profile.imageQuality.longEdgePx).also {
                            imageBytes = it.size
                            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(it, 0, it.size, size)
                            imageWidth = size.outWidth.takeIf { w -> w > 0 }
                            imageHeight = size.outHeight.takeIf { h -> h > 0 }
                        }
                        val key = ai.credential(profile, AiOAuth())
                        val onMark: (AiMark) -> Unit = { mark ->
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                if (!app.trazo.study.isAllowed(StudyFeature.AI_REVIEW)) return@launch
                                val actual = pageForReview(pageId)?.let { reviewScope(pageId, it) } ?: return@launch
                                var accepted = false
                                val permitted = app.trazo.study.acceptAid(permit) {
                                    accepted = reviewGate.commitPartialOnTarget(ticket, actual) {
                                        val note = AiNotes.place(listOf(mark.toMark()), lines, IdGenerator.RANDOM, reviewId)
                                        if (note.isNotEmpty()) {
                                            streamed++
                                            aiProgress = aiProgress?.copy(marks = streamed)
                                            updateAi(pageId) { a -> (a ?: PageAi()).let { it.copy(notes = it.notes + note) } }
                                        }
                                    }
                                }
                                if (!permitted) return@launch
                                if (!accepted && pendingReview == reviewId) discardPartialNotes(pageId, reviewId)
                            }
                        }
                        aiReviewCall?.invoke(profile, key, lines.map { it.text }, png, onMark)
                            ?: reviewer.review(profile, key, lines.map { it.text }, png, onMark)
                    }
                }
                val millis = System.currentTimeMillis() - started
                if (pendingReview != reviewId || !app.trazo.study.isAllowed(StudyFeature.AI_REVIEW)) return@launch
                val resultValue = result.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@launch
                    discardPartialNotes(pageId, reviewId)
                    val offline = profile.provider != AiProvider.ON_DEVICE && !remoteOnline()
                    val message = (e as? AiException)?.message ?: e.message ?: e.javaClass.simpleName
                    log(AiSend(started, profile.name, profile.model, n.name, pageNumber, lines.size, imageBytes, only != null, millis, message, textChars = textChars, imageWidth = imageWidth, imageHeight = imageHeight))
                    if (offline && queuedLease == null) queue(pageId, only, permit)
                    else aiProgress = AiProgress(false, lines = lines.size, message = message, error = !offline)
                    reviewGate.finishFailure(ticket)
                    return@launch
                }
                val ids = if (queuedLease == null) IdGenerator.RANDOM else stableIds(reviewId)
                val placed = AiNotes.place(resultValue.marks.map { it.toMark() }, lines, ids, reviewId)
                // Engine verification stays local; interpreted expressions never enter the request.
                val localLines = MathNotes.reviewLines(strokes.filter { it.calc?.result != true }, repo.patterns.value, only)
                val checkedLines = lines.map { raw ->
                    val local = localLines.firstOrNull { it.strokeIds.toSet() == raw.strokeIds.toSet() }?.text
                    // Si el motor no lee el renglón, prueba con la lectura que devolvió el modelo; la cuenta la hace Trazo.
                    raw.copy(text = local ?: resultValue.readings[raw.number]?.let(AiNotes::normalizeReading) ?: "?")
                }
                val notes = AiNotes.crossCheck(placed, checkedLines, ids, reviewId)
                // Veredicto: el del modelo, corregido por el motor (sus marcas cuentan; las dudosas no).
                val correct = AiNotes.correct(resultValue.correct, notes)
                val record = AiReviewRecord(
                    id = reviewId, at = System.currentTimeMillis(), model = profile.model.ifBlank { profile.name },
                    correct = correct, score = resultValue.score, selection = only != null, lineCount = lines.size,
                    notes = notes, area = captured.bounds(), strokeIds = lineIds, fingerprint = AiNotes.fingerprint(lineIds, captured),
                )
                val actual = pageForReview(pageId)?.let { reviewScope(pageId, it) }
                var accepted = false
                val permitted = actual != null && app.trazo.study.acceptAid(permit) {
                    accepted = reviewGate.commitFinalOnTarget(ticket, actual!!) {
                        updateAi(pageId) { old ->
                            val base = old ?: PageAi()
                            if (base.history.any { it.id == reviewId }) base
                            else AiNotes.record(base.copy(notes = base.notes.filterNot { it.reviewId == reviewId }), record)
                        }
                    }
                }
                if (!permitted || !accepted) {
                    discardPartialNotes(pageId, reviewId)
                    queuedLease?.let(::releaseQueueLease)
                    aiProgress = AiProgress(false, lines = lines.size, message = "La página cambió durante la revisión.", error = true)
                    return@launch
                }
                val snapshot = syncNotebook()
                if (snapshot == null) { queuedLease?.let(::releaseQueueLease); return@launch }
                val persisted = withContext(Dispatchers.IO) {
                    runCatching {
                        if (queuedLease == null) { repo.saveLazy(snapshot); true }
                        else durableReviews?.completeAfterPersist(queuedLease) { repo.saveLazy(snapshot) } == true
                    }.getOrDefault(false)
                }
                if (!persisted) {
                    queuedLease?.let(::releaseQueueLease)
                    aiProgress = AiProgress(false, lines = lines.size, message = "No se pudo guardar la revisión; queda pendiente para reintentar.", error = true)
                    return@launch
                }
                if (queuedLease == null && only == null) {
                    withContext(Dispatchers.IO) { runCatching { durableReviews?.discardPage(notebookId, pageId) } }
                }
                if (queuedLease != null) aiRevision++
                aiProgress = AiProgress(
                    running = false, lines = lines.size, marks = notes.size,
                    message = reviewMessage(notes, correct, resultValue.score),
                )
                log(AiSend(started, profile.name, profile.model, n.name, pageNumber, lines.size, imageBytes, only != null, millis, null, notes.size, resultValue.score, textChars, imageWidth, imageHeight, resultValue.usage))
            } finally {
                if (activeReviewTicket == ticket) activeReviewTicket = null
                if (activeQueueLease == queuedLease) activeQueueLease = null
                if (pendingReview == reviewId) pendingReview = ""
                if (aiJob === coroutineContext[Job]) aiJob = null
                reviewGate.finishFailure(ticket)
                if (queuedLease != null) withContext(NonCancellable + Dispatchers.IO) { durableReviews?.release(queuedLease) }
                // Queue resumption is explicit: a successful review never starts another queued request.
            }
        }
    }

    private fun queue(pageId: String, only: Set<String>?, permit: ExamAccessGate.Permit) {
        val page = pageForReview(pageId) ?: return
        val request = PendingRemoteReview(notebookId, pageId, only?.toList(), reviewFingerprint(page.allInk), System.currentTimeMillis())
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val queue = durableReviews ?: return@runCatching false
                    var wrote = false
                    val allowed = app.trazo.study.acceptAid(permit) { queue.enqueue(request); wrote = true }
                    allowed && wrote
                }.getOrDefault(false)
            }
            aiRevision++
            aiProgress = AiProgress(false, message = if (saved) "Sin conexión: revisión pendiente. Toque «Revisar página» para enviarla." else "No se pudo guardar la revisión pendiente.", error = !saved)
        }
    }

    /** Sends at most the current page's pending request, and only from the explicit review action. */
    private fun sendQueued(pageId: String, only: Set<String>?, ifEmpty: () -> Unit) {
        if (!app.trazo.study.isAllowed(StudyFeature.AI_REVIEW) || notebook == null || durableReviews == null) {
            ifEmpty()
            return
        }
        if (aiJob?.isActive == true || queueClaimJob?.isActive == true) return
        val profile = ai.settings.value.current
        if (profile == null || profile.provider == AiProvider.ON_DEVICE || !remoteOnline() ||
            profile.problem != null || (profile.provider.signIn && ai.session(profile.id) == null)) {
            ifEmpty()
            return
        }
        queueClaimJob = viewModelScope.launch {
            var claimed: DurableReviewLease? = null
            try {
                claimed = withContext(Dispatchers.IO) {
                    durableReviews?.claimNext {
                        it.notebookId == notebookId && it.pageId == pageId && it.strokeIds?.toSet() == only
                    }
                }
                val lease = claimed
                if (lease == null) {
                    ifEmpty()
                    return@launch
                }
                val page = pageForReview(lease.request.pageId)
                val ids = lease.request.strokeIds?.toSet()
                if (page == null || (ids != null && !page.allInk.map { it.id }.containsAll(ids))) {
                    withContext(Dispatchers.IO) { durableReviews?.acknowledge(lease, successfullyCommitted = true) }
                    claimed = null
                    aiRevision++
                    if (pageId == notebook?.slots?.getOrNull(pageIndex)?.id) ifEmpty()
                    return@launch
                }
                if (reviewFingerprint(page.allInk) != lease.request.fingerprint) {
                    withContext(Dispatchers.IO) { durableReviews?.acknowledge(lease, successfullyCommitted = true) }
                    claimed = null
                    aiRevision++
                    ifEmpty()
                    return@launch
                }
                claimed = null // review() owns and releases the lease from this point onward.
                review(lease.request.pageId, ids, lease)
            } finally {
                queueClaimJob = null
                claimed?.let { lease -> withContext(NonCancellable + Dispatchers.IO) { durableReviews?.release(lease) } }
            }
        }
    }

    /** Resumen de una revisión: cuántas marcas firmes, cuántas comprobó el motor y cuántas quedaron dudosas. */
    private fun reviewMessage(notes: List<AiNote>, correct: Boolean, score: Double?): String {
        val firm = notes.filterNot { it.disputed }
        val doubtful = notes.size - firm.size
        val confirmed = firm.count { it.confirmed || it.fromEngine }
        val tail = if (doubtful > 0) " · $doubtful dudosa" + (if (doubtful > 1) "s" else "") else ""
        return when {
            firm.isEmpty() && correct -> "Sin errores" + (score?.let { " · ${AiMarkPainter.formatScore(it)}/10" } ?: "") + tail
            notes.isEmpty() -> "El modelo no marcó nada."
            firm.isEmpty() -> "Sin errores firmes$tail"
            else -> (if (firm.size == 1) "1 marca" else "${firm.size} marcas") +
                (if (confirmed > 0) " · $confirmed comprobada" + (if (confirmed > 1) "s" else "") + " por Trazo" else "") + tail
        }
    }

    private fun discardPartialNotes(pageId: String, reviewId: String) {
        updateAi(pageId) { a -> a?.copy(notes = a.notes.filterNot { it.reviewId == reviewId }) }
    }

    private fun releaseQueueLease(lease: DurableReviewLease) {
        viewModelScope.launch(Dispatchers.IO) { durableReviews?.release(lease) }
    }

    private fun stableQueueReviewId(request: PendingRemoteReview): String {
        val raw = listOf(request.notebookId, request.pageId, request.key.selectionSignature, request.fingerprint).joinToString("") { "${it.length}:$it" }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "queued-$hash"
    }

    private fun stableIds(reviewId: String): IdGenerator {
        var i = 0
        return IdGenerator { "$reviewId-${++i}" }
    }

    private fun log(s: AiSend) { viewModelScope.launch(Dispatchers.IO) { journal.record(s) } }

    /**
     * Cambia las marcas de la hoja [pageId], esté abierta o no (la revisión pudo terminar después de
     * pasar de hoja). Lo que ya no coincide con la tinta actual se descarta.
     */
    private fun updateAi(pageId: String, change: (PageAi?) -> PageAi?) {
        val n = notebook ?: return
        val i = n.slots.indexOfFirst { it.id == pageId }
        if (i < 0) return
        if (i == pageIndex) {
            controller.setAi(AiNotes.prune(change(controller.page.ai), controller.page.allInk))
            return
        }
        val session = sessions[pageId]?.first
        val page = n.page(i)
        val next = AiNotes.prune(change(session?.ai ?: page.ai), session?.allInk ?: page.allInk)
        session?.ai = next
        notebook = n.withPage(i, page.copy(ai = next)).loadedOnly(pageIndex)
        markDirty()
    }

    fun removeAiNote(note: AiNote) {
        controller.setAi(controller.page.ai?.without(note.id))
        aiNote = null
    }

    /** Escribe la explicación del resultado debajo de la cuenta, con una letra parecida a la del usuario. */
    fun writeExplanation(e: MathNotes.Explanation) {
        val ink = MathNotes.handwrite(controller.page.strokes, repo.patterns.value, e)
        mathExplanation = null
        controller.addInk(ink)
    }

    /** Copia la respuesta del tutor del chat en la hoja, debajo de lo último escrito, con la letra del usuario. */
    fun writeTutorAnswer(text: String) {
        val ink = MathNotes.handwriteBelow(controller.page.strokes, repo.patterns.value, TutorText.plain(text))
        if (ink.isNotEmpty()) controller.addInk(ink)
    }

    /** La marca pasa a ser tinta común de la hoja (se puede mover, borrar o deshacer). */
    fun aiNoteToInk(note: AiNote) {
        val all = controller.page.ai?.notes.orEmpty()
        removeAiNote(note)
        controller.addInk(AiMarkPainter.toInk(note, IdGenerator.RANDOM, all = all))
    }

    /**
     * El usuario discute la marca (17): el modelo la mantiene, la cambia o la retira, y explica por qué.
     */
    fun discussAiNote(note: AiNote, argument: String) {
        if (!app.trazo.study.isAllowed(StudyFeature.AI_REVIEW)) { aiAnswer = "El Modo IA está bloqueado durante el examen."; return }
        if (aiDiscussing || argument.isBlank()) return
        val profile = ai.settings.value.current ?: run { aiAnswer = "Configure un modelo en Ajustes → Modo IA."; return }
        val n = notebook ?: return
        val pageId = n.slots.getOrNull(pageIndex)?.id ?: return
        val page = pageForReview(pageId) ?: return
        if (page.ai?.notes?.any { it.id == note.id } != true) return
        val permit = app.trazo.study.issueAid(ExamHelp.AI_REVIEW) ?: return
        val strokes = page.allInk
        val lines = MathNotes.reviewLabels(strokes, repo.patterns.value, note.strokeIds.toSet())
        val ids = note.strokeIds.toSet()
        val captured = strokes.filter { it.id in ids }
        val kind = runCatching { MarkKind.valueOf(note.kind) }.getOrDefault(MarkKind.CALCULATION)
        val mark = if (note.fromEngine) {
            // Discussing a local warning must not disclose the solver's verdict or computed values.
            AiMark(1, kind, "Revisar este renglón desde la imagen y las etiquetas originales.")
        } else AiMark(1, kind, note.short, note.detail, note.fix)
        val requestId = ++discussionId
        var started = false
        if (!app.trazo.study.acceptAid(permit) { aiDiscussing = true; aiAnswer = null; started = true } || !started) return
        discussionScope = reviewScope(pageId, page)
        discussionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val png = if (profile.provider == AiProvider.ON_DEVICE && profile.localTextOnly) null
                    else captured.takeIf { it.isNotEmpty() }?.let { AiCapture.png(it, lines, profile.imageQuality.longEdgePx) }
                    reviewer.discuss(profile, ai.credential(profile, AiOAuth()), mark, lines.joinToString("  ") { it.text }.ifBlank { "?" }, argument.trim(), png)
                }
            }
            var applied = false
            val allowed = app.trazo.study.acceptAid(permit) {
                if (requestId == discussionId) {
                    aiDiscussing = false
                    val current = pageForReview(pageId)?.ai?.notes?.firstOrNull { it.id == note.id }
                    if (aiNote?.id == note.id && current == note) {
                        applied = true
                        result.onSuccess { d ->
                            aiAnswer = d.answer.ifBlank { if (d.keep) "El modelo mantiene la marca." else "El modelo retira la marca." }
                            when {
                                !d.keep -> updateAi(pageId) { it?.without(note.id) }
                                d.mark != null -> {
                                    val m = requireNotNull(d.mark)
                                    val changed = note.copy(kind = m.kind.name, short = m.short, detail = m.detail, fix = m.fix, disputed = false)
                                    updateAi(pageId) { a -> a?.copy(notes = a.notes.map { if (it.id == note.id) changed else it }) }
                                    if (aiNote?.id == note.id) aiNote = changed
                                }
                            }
                        }.onFailure { e ->
                            aiAnswer = "No se pudo consultar: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
            }
            if (!allowed && requestId == discussionId) aiDiscussing = false
            if (allowed && !applied && requestId == discussionId) aiDiscussing = false
            if (requestId == discussionId) discussionScope = null
        }
    }

    private fun AiMark.toMark() = AiNotes.Mark(line, kind.name, short, detail, fix)

    // ---------------------------------------------------------------- chat de revisión

    private val tutor = AiTutor(AiClient(local = localCompletion))
    private val chats = app.trazo.aiChats

    /** El panel del chat está desplegado. */
    var chatOpen by mutableStateOf(false)
        private set
    var chatMessages by mutableStateOf<List<AiChatMessage>>(emptyList())
        private set
    /** La respuesta que va llegando; null si no hay ninguna en curso. */
    var chatStreaming by mutableStateOf<String?>(null)
        private set
    var chatBusy by mutableStateOf(false)
        private set
    private var chatJob: Job? = null
    private var chatId = 0L
    private var chatPage: String? = null

    /** Despliega el chat; la primera vez en la hoja pide la revisión completa. */
    fun openChat() {
        chatOpen = true
        if (chatMessages.isEmpty() && !chatBusy) sendChat(null)
    }

    fun closeChat() { chatOpen = false }

    fun toggleChat() { if (chatOpen) closeChat() else openChat() }

    private fun loadChat(pageId: String) {
        if (chatPage == pageId) return
        cancelChat()
        chatPage = pageId
        chatMessages = chats.load(notebookId, pageId)
    }

    private fun setChat(pageId: String, messages: List<AiChatMessage>) {
        if (chatPage == pageId) chatMessages = messages
        viewModelScope.launch(Dispatchers.IO) { chats.save(notebookId, pageId, messages) }
    }

    private fun chatProblem(text: String) {
        val pageId = chatPage ?: return
        setChat(pageId, chatMessages + AiChatMessage(TutorRole.TUTOR, text, System.currentTimeMillis(), error = true))
    }

    private fun cancelChat() {
        chatId++
        if (chatBusy) localCompletion.cancel()
        chatJob?.cancel()
        chatJob = null
        chatBusy = false
        chatStreaming = null
    }

    /** Borra la conversación de la hoja y vuelve a revisarla desde cero. */
    fun restartChat() {
        val pageId = chatPage ?: return
        cancelChat()
        setChat(pageId, emptyList())
        sendChat(null)
    }

    /** Marca en la hoja el renglón que señaló el tutor. */
    fun showChatLine(message: AiChatMessage) {
        val present = controller.page.allInk.mapTo(HashSet()) { it.id }
        val ids = message.strokeIds.filter { it in present }
        if (ids.isNotEmpty()) controller.select(ids)
    }

    /**
     * Manda un mensaje al tutor ([question] null = la revisión inicial). La transcripción viaja entera en
     * cada pedido, con los renglones actuales de la hoja: si el estudiante corrigió algo, el tutor lo ve.
     */
    fun sendChat(question: String?) {
        if (chatBusy) return
        val n = notebook ?: return
        val pageId = n.slots.getOrNull(pageIndex)?.id ?: return
        if (attachedPageId != pageId) return
        loadChat(pageId)
        if (!app.trazo.study.isAllowed(StudyFeature.AI_REVIEW)) { chatProblem("El Modo IA está bloqueado durante el examen."); return }
        val profile = ai.settings.value.current ?: run { chatProblem("Configure un modelo en Ajustes → Modo IA."); return }
        profile.problem?.let { chatProblem(it); return }
        if (profile.provider.signIn && ai.session(profile.id) == null) {
            chatProblem("Falta iniciar sesión en «${profile.name}» (Ajustes → Modo IA).")
            return
        }
        if (profile.provider != AiProvider.ON_DEVICE && !remoteOnline()) { chatProblem("Sin conexión: el chat necesita red para este modelo."); return }
        val page = pageForReview(pageId) ?: return
        val lines = MathNotes.reviewLabels(page.allInk, repo.patterns.value)
        val attachment = chatAttachment
        if (lines.isEmpty() && page.images.isEmpty() && attachment == null) {
            chatProblem("No hay nada escrito para revisar. Escriba en la hoja, pegue el enunciado o adjunte una imagen.")
            return
        }
        val q = question?.trim()?.takeIf { it.isNotEmpty() } ?: if (attachment != null) "Mire la imagen que adjunto." else null
        val previous = chatMessages.filterNot { it.error }
        if (previous.isNotEmpty() && q == null) return
        val permit = app.trazo.study.issueAid(ExamHelp.AI_REVIEW) ?: return
        val history = previous.map { m ->
            val verdict = when {
                m.correct == true -> "\nCORRECTO: ${m.verdict.orEmpty()}"
                m.correct == false -> "\nERROR (renglón ${m.line ?: "?"}): ${m.verdict.orEmpty()}"
                else -> ""
            }
            TutorTurn(m.role, m.text + verdict)
        }
        val now = System.currentTimeMillis()
        setChat(pageId, chatMessages + listOfNotNull(q?.let { AiChatMessage(TutorRole.STUDENT, it, now, image = attachment) }))
        chatAttachment = null
        val paper = n.paper
        val id = ++chatId
        chatBusy = true
        chatStreaming = ""
        chatJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val png = if (profile.provider == AiProvider.ON_DEVICE && profile.localTextOnly) null
                    else chatImage(page, paper, attachment, profile.imageQuality.longEdgePx)
                    val key = ai.credential(profile, AiOAuth())
                    val buffer = StringBuilder()
                    tutor.ask(profile, key, lines.map { it.text }, png, history, q) { chunk ->
                        buffer.append(chunk)
                        val shown = buffer.toString()
                        viewModelScope.launch(Dispatchers.Main.immediate) { if (id == chatId && chatBusy) chatStreaming = shown }
                    }
                }
            }
            if (id != chatId) return@launch
            chatBusy = false
            chatStreaming = null
            val e = result.exceptionOrNull()
            if (e is kotlinx.coroutines.CancellationException) return@launch
            val allowed = app.trazo.study.acceptAid(permit) {
                result.onSuccess { reply ->
                    val v = AiTutorPrompt.verdict(reply, lines.size)
                    val ids = v?.line?.let { l -> lines.getOrNull(l - 1)?.strokeIds }.orEmpty()
                    val text = if (v != null) AiTutorPrompt.withoutVerdict(reply) else reply.trim()
                    setChat(pageId, chatMessages + AiChatMessage(TutorRole.TUTOR, text, System.currentTimeMillis(), v?.line, v?.correct, v?.summary, ids))
                }
            }
            if (e != null) chatProblem("No se pudo consultar: ${(e as? AiException)?.message ?: e.message ?: e.javaClass.simpleName}")
            else if (!allowed) chatProblem("El Modo IA está bloqueado durante el examen.")
        }
    }

    /** Imagen que el estudiante va a mandar con el próximo mensaje (nombre en la carpeta de imágenes). */
    var chatAttachment by mutableStateOf<String?>(null)
        private set
    var chatAttaching by mutableStateOf(false)
        private set

    /** Adjunta una imagen de la galería al próximo mensaje del chat. */
    fun attachChatImage(open: () -> java.io.InputStream) {
        if (chatAttaching) return
        chatAttaching = true
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = io.trazo.app.data.ImageImport.decode(open, CHAT_IMAGE_SIDE) ?: error("No se pudo leer la imagen")
                    try { storeImage(bmp) } finally { bmp.recycle() }
                }
            }
            chatAttaching = false
            name.onSuccess { chatAttachment = it }.onFailure { chatProblem(it.message ?: "No se pudo leer la imagen") }
        }
    }

    fun clearChatAttachment() { chatAttachment = null }

    /** Archivo de una imagen adjunta en el chat, para mostrarla en la burbuja. */
    fun chatImageFile(name: String): java.io.File = java.io.File(repo.imagesDir, name)

    /** La hoja entera (con sus imágenes) y, debajo, la imagen adjunta; un solo PNG para el modelo. */
    private fun chatImage(page: Page, paper: Paper, attachment: String?, longEdgePx: Int): ByteArray? {
        val sheet = if (page.allInk.isNotEmpty() || page.images.isNotEmpty()) {
            runCatching { PageExporter.thumbnail(page, paper, longEdgePx.coerceIn(256, 2400)) }.getOrNull()
        } else null
        val extra = attachment?.let { io.trazo.app.data.ImageImport.decodeFile(chatImageFile(it).path) }
        try {
            return AiCapture.chatPng(sheet, extra, longEdgePx)
        } finally {
            sheet?.recycle()
            extra?.recycle()
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
        val page = if (index == pageIndex && attachedPageId == id) controller.page.toPage(id) else null
        val slot = n.slots[index]
        val bmp = runCatching {
            withContext(Dispatchers.Default) {
                PageExporter.thumbnail(page ?: slot.page(), n.paper, width, colors).asImageBitmap()
            }
        }.getOrNull() ?: return null
        thumbs[id] = key to bmp
        return bmp
    }

    /** Vuelca la página en edición al cuaderno (las demás no cambian: siguen fijas). */
    private fun syncNotebook(): LazyNotebook? {
        val n = notebook ?: return null
        if (attachedPageId != n.slots.getOrNull(pageIndex)?.id) return null
        val page = controller.page.toPage(n.slots[pageIndex].id)
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
        val snapshot = syncNotebook() ?: return
        repo.saveLazy(snapshot)
        savedVersion = version
    }

    override fun onCleared() {
        reviewGate.close()
        discussionId++
        discussionJob?.cancel()
        localCompletion.cancel()
        // Closing a native context can wait for decode. Keep that wait off the UI thread.
        Thread({ localCompletion.close() }, "trazo-local-close").start()
        connectivity.stop()
        queueClaimJob?.cancel()
        // Una revisión a medias no se guarda: sus marcas parciales se quitan (QA53).
        if (aiJob?.isActive == true && pendingReview.isNotEmpty()) {
            val id = pendingReview
            aiJob?.cancel()
            updateAi(pendingPage) { a -> a?.copy(notes = a.notes.filterNot { it.reviewId == id }) }
        }
        if (!controller.isContactActive) saveNow()
        // Cerrado y guardado: la sincronización ya puede llevarlo al otro dispositivo.
        repo.markClosed(notebookId)
        app.trazo.sync.requestSync()
        super.onCleared()
    }

    /** Migrate old JSON requests only after each durable replacement is written successfully. */
    private fun migrateLegacyQueue(book: LazyNotebook, target: DurableReviewQueue) {
        val old = journal.queue.value.filter { it.notebookId == notebookId }
        for ((pageId, items) in old.groupBy { it.pageId }) {
            val index = book.slots.indexOfFirst { it.id == pageId }
            if (index < 0) continue
            val page = book.page(index)
            val fingerprint = reviewFingerprint(page.allInk)
            for (item in items) {
                val selection = item.strokeIds?.takeIf { it.isNotEmpty() }
                target.enqueue(PendingRemoteReview(notebookId, pageId, selection, fingerprint, item.at))
            }
            // AiJournal's delete is best-effort; if it fails, replay is idempotent by queue key+fingerprint.
            journal.take(notebookId, pageId)
        }
    }

    // Al final de la clase: Main.immediate corre esto en el acto y todas las propiedades ya deben existir.
    init {
        viewModelScope.launch {
            app.trazo.study.state.collect { state ->
                reviewGate.setExamMode(state.examActive)
                controller.mathEnabled = lastSettings.mathNotes && !state.examActive
                if (state.examActive) {
                    if (aiJob?.isActive == true) cancelAiReview()
                    discussionId++
                    discussionJob?.cancel()
                    localCompletion.cancel()
                    aiDiscussing = false
                    aiNote = null
                    complexPrompt = null
                    mathExplanation = null
                    aiVisible = false
                    controller.aiVisible = false
                }
                revision++
            }
        }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val bookResult = runCatching { repo.loadLazy(notebookId) }
                    val book = bookResult.getOrNull()
                    val queueResult = runCatching { app.trazo.reviewQueue }
                    val queue = queueResult.getOrNull()
                    val migrationError = if (book != null && queue != null) runCatching { migrateLegacyQueue(book, queue) }.exceptionOrNull() else null
                    Triple(book, queue, bookResult.exceptionOrNull() to (queueResult.exceptionOrNull() ?: migrationError))
                }
                val n = loaded.first
                durableReviews = loaded.second
                reviewQueueProblem = loaded.third.second?.message ?: loaded.third.second?.javaClass?.simpleName
                notebook = n
                if (n == null) {
                    pageLoadError = loaded.third.first?.message ?: loaded.third.first?.javaClass?.simpleName ?: "No se pudo abrir el cuaderno."
                } else {
                    // Decode the selected page before attaching or allowing a save to replace its raw slot.
                    attachPage(startPage?.takeIf { it in n.slots.indices } ?: n.openPage, hardReset = true)
                }
                if (reviewQueueProblem != null) aiProgress = AiProgress(false, message = "La cola durable no está disponible: $reviewQueueProblem", error = true)
            } catch (e: Exception) {
                pageLoadError = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
            if (attachedPageId != null) {
                connectivity.start()
            }
        }
    }
}

/** Cuántas hojas recientes conservan su historial de deshacer. */
private const val PAGE_SESSIONS = 8
/** Lado de la copia para la vista previa del recorte. */
private const val PREVIEW_SIDE = 900
private const val CHAT_IMAGE_SIDE = 2048
/** Etiqueta de lo que Trazo copia al portapapeles del sistema (no se vuelve a pegar como imagen). */
private const val CLIP_LABEL = "Trazo"
