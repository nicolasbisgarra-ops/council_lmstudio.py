@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import io.trazo.app.ink.InkCanvasView
import io.trazo.app.trazo
import io.trazo.core.InkEvent
import io.trazo.core.InkPattern
import io.trazo.core.InkStroke
import io.trazo.core.Paper
import kotlinx.coroutines.launch

/** La vista de tinta en pantalla, para los botones de zoom de la barra. */
private class CanvasRef { var view: InkCanvasView? = null }

@Composable
fun EditorScreen(
    notebookId: String,
    onBack: () -> Unit,
    onPatterns: () -> Unit,
    onSettings: () -> Unit,
    onTeachNewPattern: (List<InkStroke>) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = "editor-$notebookId") { EditorViewModel(app, notebookId) }
    val store = app.trazo.settings
    val settings by store.settings.collectAsState()
    val patterns by app.trazo.repository.patterns.collectAsState()
    val enabledPatterns = remember(patterns) { patterns.filter { it.enabled } }
    val density = LocalDensity.current.density
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val canvas = remember { CanvasRef() }
    var canvasSize by remember { mutableStateOf(0 to 0) }
    var showInsert by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var wheelAt by remember { mutableStateOf<Offset?>(null) }
    // El panel lateral de patrones se puede minimizar para dejarle todo el ancho a la hoja.
    var railOpen by rememberSaveable { mutableStateOf(true) }
    val paper = paperColors(settings.paperTone)
    val accent = MaterialTheme.colorScheme.primary.toArgb()

    LaunchedEffect(settings, density) { vm.applySettings(settings, density) }

    fun export(uri: android.net.Uri?, pdf: Boolean) {
        if (uri == null) return
        val appContext = context.applicationContext
        vm.exportTo(appContext.contentResolver, uri, pdf) { error ->
            val message = when {
                error != null -> "No se pudo exportar: ${error.message}"
                pdf -> "PDF exportado"
                else -> "Página exportada"
            }
            Toast.makeText(appContext, message, if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> export(uri, pdf = true) }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> export(uri, pdf = false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                // Una interrupción nunca convierte un gesto a medio sostener.
                Lifecycle.Event.ON_PAUSE -> vm.controller.cancel()
                Lifecycle.Event.ON_STOP -> vm.saveNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm.lastConversion) {
        val c = vm.lastConversion ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(
            "Reemplazado en tinta: ${c.pattern.name}", actionLabel = "Deshacer",
            withDismissAction = true, duration = SnackbarDuration.Short,
        )
        if (r == SnackbarResult.ActionPerformed) vm.undo()
        vm.lastConversion = null
    }

    LaunchedEffect(vm.teachOffer) {
        val offer = vm.teachOffer ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(
            "No reconocí el gesto: tu escritura quedó igual", actionLabel = "Enseñar",
            withDismissAction = true, duration = SnackbarDuration.Long,
        )
        if (r == SnackbarResult.ActionPerformed) vm.teaching = offer
        vm.teachOffer = null
    }

    val notebook = vm.notebook
    if (notebook == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (vm.loading) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No se encontró el cuaderno.")
                    TextButton(onClick = onBack) { Text("Volver") }
                }
            }
        }
        return
    }

    // Borrar la página entera se deshace como cualquier otro cambio; el aviso lo ofrece a mano.
    fun clearPage() {
        if (vm.pageIsEmpty) return
        vm.clearPage()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Página borrada", actionLabel = "Deshacer", withDismissAction = true, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    // Un color rápido recolorea la selección si la hay; si no, es la tinta de la herramienta.
    fun quickColor(c: Int) {
        when {
            vm.hasSelection -> vm.recolorSelection(c)
            vm.editorTool == EditorTool.HIGHLIGHTER -> store.update { it.copy(highlighterColor = c) }
            else -> {
                store.update { it.copy(penColor = c) }
                if (vm.editorTool != EditorTool.PEN && vm.editorTool != EditorTool.SHAPE) vm.selectTool(EditorTool.PEN)
            }
        }
    }

    // Botón del lápiz en el aire: 1 clic abre o cierra la ruleta, 2 alternan los colores de
    // Preferencias, 3 prenden o apagan el modo formas.
    fun penButtonClicks(count: Int, x: Float, y: Float) {
        when (count) {
            1 -> wheelAt = if (wheelAt == null) Offset(x, y) else null
            2 -> {
                wheelAt = null
                val (a, b) = store.settings.value.swapColors
                store.update { it.copy(penColor = if (it.penColor == a) b else a) }
                if (vm.editorTool != EditorTool.PEN && vm.editorTool != EditorTool.SHAPE) vm.selectTool(EditorTool.PEN)
            }
            else -> {
                wheelAt = null
                val on = vm.toggleShapeTool()
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        if (on) "Modo formas activado" else "Modo formas desactivado",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                vm, notebook.name, settings, store::update,
                EditorActions(
                    onBack = { vm.saveNow(); onBack() },
                    onRename = { renaming = true },
                    onInsert = { showInsert = true },
                    onZoom = { f -> canvas.view?.zoomStep(f) },
                    onPickColor = { colorTarget = it },
                    onQuickColor = ::quickColor,
                    onClearPage = ::clearPage,
                    onDeletePage = { confirmDeletePage = true },
                    onPdf = { pdfLauncher.launch("${notebook.name}.pdf") },
                    onPng = { pngLauncher.launch("${notebook.name} - página ${vm.pageIndex + 1}.png") },
                    onPatterns = onPatterns,
                    onSettings = onSettings,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color(paper.desk),
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // En tablet los patrones quedan en un panel lateral; en teléfono, en la hoja inferior.
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { canvasSize = it.width to it.height; vm.viewSize = canvasSize }) {
                    AndroidView(
                        factory = { ctx ->
                            InkCanvasView(ctx).apply {
                                viewport = vm.viewport
                                controller = vm.controller
                                onTwoFingerTap = vm::undo
                                val toggleGestures = {
                                    store.update { it.copy(gesturesEnabled = !it.gesturesEnabled) }
                                    val on = store.settings.value.gesturesEnabled
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        snackbar.showSnackbar(
                                            if (on) "Detección automática activada" else "Detección automática desactivada",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                    Unit
                                }
                                onThreeFingerTap = toggleGestures
                                onHoldSecondDoubleTap = toggleGestures
                                onHoldDoubleTap = {
                                    store.update { it.copy(autoAlign = !it.autoAlign) }
                                    val on = store.settings.value.autoAlign
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        snackbar.showSnackbar(
                                            if (on) "Auto-alineado activado" else "Auto-alineado desactivado",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                }
                                onPenButtonClicks = ::penButtonClicks
                                onViewportChanged = { vm.zoomPercent = this.zoomPercent; vm.mathExplanation = null }
                                canvas.view = this
                            }
                        },
                        onRelease = { v -> if (canvas.view === v) canvas.view = null },
                        update = { v ->
                            vm.pageIndex // releer al cambiar de página
                            vm.fitRequest // y al pedir "Ajustar a la página"
                            v.controller = vm.controller
                            v.viewport = vm.viewport
                            v.paper = vm.paper
                            v.fingerDraws = settings.fingerDraws
                            v.showHoldIndicator = settings.showHoldIndicator
                            v.hapticsEnabled = settings.haptics
                            v.paperColors = paper
                            v.accentColor = accent
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    vm.selectedShape?.let { shape ->
                        ShapeInspector(vm, shape, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                    vm.mathExplanation?.let { e ->
                        MathBubble(e, vm.viewport, onDismiss = { vm.mathExplanation = null })
                    }
                    if (vm.hasSelection) {
                        SelectionPill(
                            vm,
                            onCustomColor = { colorTarget = ColorTarget.Selection },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        )
                    }
                    wheelAt?.let { at ->
                        ToolWheel(
                            vm, settings, at,
                            onColor = ::quickColor,
                            onCustomColor = {
                                colorTarget = if (vm.editorTool == EditorTool.HIGHLIGHTER) ColorTarget.Highlighter else ColorTarget.Pen
                            },
                            onDismiss = { wheelAt = null },
                            // La ruleta tapa el lienzo: escucha ella misma el botón para cerrarse o cambiar.
                            onPenButtonClicks = { n -> penButtonClicks(n, at.x, at.y) },
                        )
                    }
                }
                if (wide && railOpen) {
                    PatternRail(
                        patterns = enabledPatterns,
                        onInsert = { vm.insertPattern(it, canvasSize.first, canvasSize.second) },
                        onLibrary = onPatterns,
                        onMinimize = { railOpen = false },
                    )
                } else if (wide) {
                    PatternRailTab(onOpen = { railOpen = true })
                }
            }
        }
    }

    // Los diálogos esperan a que se levante el lápiz para no interrumpir el trazo.
    vm.complexPrompt?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.complexPrompt = null },
            title = { Text("No tiene solución real") },
            text = { Text("$msg Sus raíces son imaginarias. ¿Querés resolverla con números imaginarios?") },
            confirmButton = { TextButton(onClick = { vm.calculateWithImaginaries() }) { Text("Resolver con imaginarios") } },
            dismissButton = { TextButton(onClick = { vm.complexPrompt = null }) { Text("No") } },
        )
    }
    vm.ambiguous?.takeIf { !vm.isDrawing }?.let { amb ->
        AlertDialog(
            onDismissRequest = { vm.chooseAmbiguous(null) },
            title = { Text("Dos patrones se parecen") },
            text = {
                Column {
                    Text("Tu escritura se conservó. ¿Querés reemplazarla por alguno?")
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        amb.options.forEach { m ->
                            PatternTile(m.pattern, onClick = { vm.chooseAmbiguous(m.pattern) }, Modifier.weight(1f), previewHeight = 84.dp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.chooseAmbiguous(null) }) { Text("Conservar mi escritura") } },
        )
    }

    if (showInsert) {
        ModalBottomSheet(
            onDismissRequest = { showInsert = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxHeight(0.8f).padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Insertar patrón", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showInsert = false; onPatterns() }) { Text("Biblioteca") }
                }
                Text(
                    "Va al centro de la vista como tinta tuya, editable con el lazo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                PatternPicker(
                    enabledPatterns,
                    onPick = { p -> vm.insertPattern(p, canvasSize.first, canvasSize.second); showInsert = false },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    vm.teaching?.takeIf { !vm.isDrawing }?.let { offer ->
        TeachSheet(
            offer = offer,
            vm = vm,
            patterns = enabledPatterns,
            learn = settings.learnFromCorrections,
            onNewPattern = { gesture -> vm.teaching = null; onTeachNewPattern(gesture) },
        )
    }

    colorTarget?.let { target ->
        val initial = when (target) {
            ColorTarget.Pen -> settings.penColor
            ColorTarget.Highlighter -> settings.highlighterColor
            ColorTarget.Selection -> vm.selectionColor ?: settings.penColor
            is ColorTarget.Quick -> settings.quickColors.getOrElse(target.slot) { settings.penColor }
        }
        ColorPickerDialog(
            initial = initial,
            title = when (target) {
                ColorTarget.Pen -> "Color de la pluma"
                ColorTarget.Highlighter -> "Color del resaltador"
                ColorTarget.Selection -> "Color de la selección"
                is ColorTarget.Quick -> "Cambiar este color rápido"
            },
            onDismiss = { colorTarget = null },
            onPick = { c ->
                colorTarget = null
                when (target) {
                    ColorTarget.Pen -> store.update { it.copy(penColor = c) }
                    ColorTarget.Highlighter -> store.update { it.copy(highlighterColor = c) }
                    ColorTarget.Selection -> vm.recolorSelection(c)
                    is ColorTarget.Quick -> store.update { s ->
                        s.copy(quickColors = s.quickColors.mapIndexed { i, q -> if (i == target.slot) c else q }, penColor = c)
                    }
                }
            },
            suggestions = if (target == ColorTarget.Highlighter) HighlighterPalette else PenPalette,
        )
    }

    if (renaming) {
        NameDialog("Renombrar cuaderno", notebook.name) { name ->
            renaming = false
            if (!name.isNullOrBlank()) vm.rename(name)
        }
    }

    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text("¿Eliminar la página ${vm.pageIndex + 1}?") },
            text = { Text("Se borran sus trazos. Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { confirmDeletePage = false; vm.deletePage() }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDeletePage = false }) { Text("Cancelar") } },
        )
    }
}

/**
 * "¿Qué patrón era?": elegir qué trazos forman el gesto (si hay varios grupos posibles) y por qué
 * patrón reemplazarlos. Todas las vistas previas son tinta.
 */
@Composable
private fun TeachSheet(
    offer: InkEvent.NotRecognized,
    vm: EditorViewModel,
    patterns: List<InkPattern>,
    learn: Boolean,
    onNewPattern: (List<InkStroke>) -> Unit,
) {
    val groups = remember(offer, vm.revision) {
        offer.candidates.mapNotNull { ids -> vm.strokesOf(ids)?.let { ids to it } }
    }
    if (groups.isEmpty()) {
        LaunchedEffect(offer) { vm.teaching = null }
        return
    }
    var chosen by remember(offer) { mutableIntStateOf(0) }
    val group = groups[chosen.coerceIn(0, groups.lastIndex)]
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = { vm.teaching = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text("¿Qué patrón era?", style = MaterialTheme.typography.titleLarge)
            if (groups.size > 1) {
                Text("Trazos que forman el gesto:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEachIndexed { i, (_, strokes) ->
                        val selected = i == chosen
                        Surface(
                            onClick = { chosen = i },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                            border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
                        ) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                InkPreview(strokes, Modifier.size(84.dp))
                                Text(if (strokes.size == 1) "1 trazo" else "${strokes.size} trazos", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            Text(
                if (learn) "Reemplazar y recordar este gesto como ejemplo:" else "Reemplazar por:",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            PatternPicker(patterns, onPick = { p -> vm.teach(p, group.first); vm.teaching = null }, modifier = Modifier.weight(1f))
            TextButton(onClick = { onNewPattern(group.second) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Crear un patrón nuevo con este gesto")
            }
        }
    }
}

/** Panel lateral de tablet: los patrones a mano para insertar con un toque. */
@Composable
private fun PatternRail(
    patterns: List<InkPattern>,
    onInsert: (InkPattern) -> Unit,
    onLibrary: () -> Unit,
    onMinimize: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(232.dp).fillMaxHeight()) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Minimizar patrones")
                }
                SectionLabel("Patrones", Modifier.weight(1f))
                TextButton(onClick = onLibrary) { Text("Biblioteca") }
            }
            PatternPicker(patterns, onInsert, Modifier.weight(1f), minTile = 96.dp, showSections = false)
        }
    }
}

/** Pestaña angosta que queda al minimizar el panel de patrones: un toque lo vuelve a abrir. */
@Composable
private fun PatternRailTab(onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(40.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onOpen, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mostrar patrones")
            }
            IconButton(onClick = onOpen) {
                Icon(TrazoIcons.Patterns, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

fun paperName(p: Paper) = when (p) {
    Paper.PLAIN -> "Liso"
    Paper.LINES -> "Rayado"
    Paper.GRID -> "Cuadriculado"
    Paper.DOTS -> "Punteado"
}
