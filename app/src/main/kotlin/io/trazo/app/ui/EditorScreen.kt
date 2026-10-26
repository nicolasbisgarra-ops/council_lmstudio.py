@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
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
import io.trazo.core.Tool

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
    val settings by app.trazo.settings.settings.collectAsState()
    val patterns by app.trazo.repository.patterns.collectAsState()
    val density = LocalDensity.current.density
    val snackbar = remember { SnackbarHostState() }
    var showPatterns by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(settings, density) { vm.applySettings(settings, density) }

    fun saveTo(uri: android.net.Uri?, done: String, write: (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        runCatching { context.contentResolver.openOutputStream(uri)?.use(write) }
            .onSuccess { Toast.makeText(context, done, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "No se pudo exportar: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        saveTo(uri, "PDF exportado", vm::exportPdf)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        saveTo(uri, "Página exportada", vm::exportPagePng)
    }

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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se encontró el cuaderno.")
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(notebook.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Página ${vm.pageIndex + 1} de ${vm.pageCount}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.saveNow(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = { vm.goToPage(vm.pageIndex - 1) }, enabled = vm.pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior")
                    }
                    IconButton(onClick = { vm.goToPage(vm.pageIndex + 1) }, enabled = vm.pageIndex < vm.pageCount - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Página siguiente")
                    }
                    IconButton(onClick = vm::addPage) { Icon(Icons.Filled.Add, "Nueva página") }
                    PaperMenu(vm.paper, vm::setPaper)
                    EditorMenu(
                        pageIndex = vm.pageIndex,
                        pageCount = vm.pageCount,
                        onFit = vm::fitToPage,
                        onSelectAll = vm::selectAll,
                        onDuplicatePage = vm::duplicatePage,
                        onMovePage = vm::movePage,
                        onDeletePage = { confirmDeletePage = true },
                        onPdf = { pdfLauncher.launch("${notebook.name}.pdf") },
                        onPng = { pngLauncher.launch("${notebook.name} - página ${vm.pageIndex + 1}.png") },
                        onSettings = onSettings,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // En tablet los patrones quedan en un panel lateral; en teléfono, en la hoja inferior.
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ToolBar(
                        vm = vm,
                        gesturesOn = settings.gesturesEnabled,
                        penColor = settings.penColor,
                        penWidth = settings.penWidth,
                        onGestures = { on -> app.trazo.settings.update { it.copy(gesturesEnabled = on) } },
                        onColor = { c -> app.trazo.settings.update { it.copy(penColor = c) } },
                        onWidth = { w -> app.trazo.settings.update { it.copy(penWidth = w) } },
                        onPatterns = { showPatterns = true },
                    )
                    if (vm.hasSelection) SelectionBar(vm)
                    val dark = isSystemInDarkTheme()
                    Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasSize = it.width to it.height }) {
                        AndroidView(
                            factory = { ctx ->
                                InkCanvasView(ctx).apply {
                                    viewport = vm.viewport
                                    controller = vm.controller
                                }
                            },
                            update = { v ->
                                vm.pageIndex // releer al cambiar de página
                                vm.fitRequest // y al pedir "Ajustar a la página"
                                v.controller = vm.controller
                                v.viewport = vm.viewport
                                v.paper = vm.paper
                                v.fingerDraws = settings.fingerDraws
                                v.showHoldIndicator = settings.showHoldIndicator
                                v.hapticsEnabled = settings.haptics
                                v.darkPaper = dark
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (wide) {
                    PatternRail(
                        patterns = patterns.filter { it.enabled },
                        onInsert = { vm.insertPattern(it, canvasSize.first, canvasSize.second) },
                        onLibrary = onPatterns,
                    )
                }
            }
        }
    }

    // Los diálogos esperan a que se levante el lápiz para no interrumpir el trazo.
    vm.ambiguous?.takeIf { !vm.isDrawing }?.let { amb ->
        AlertDialog(
            onDismissRequest = { vm.chooseAmbiguous(null) },
            title = { Text("Dos patrones se parecen") },
            text = {
                Column {
                    Text("Tu escritura se conservó. ¿Querés reemplazarla por alguno?")
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        amb.options.forEach { m ->
                            Card(onClick = { vm.chooseAmbiguous(m.pattern) }, modifier = Modifier.weight(1f)) {
                                InkPreview(m.pattern.outputInk, Modifier.fillMaxWidth().height(90.dp).background(MaterialTheme.colorScheme.surface))
                                Text(m.pattern.name, Modifier.padding(8.dp), maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.chooseAmbiguous(null) }) { Text("Conservar mi escritura") } },
        )
    }

    if (showPatterns) {
        ModalBottomSheet(onDismissRequest = { showPatterns = false }) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Insertar patrón", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showPatterns = false; onPatterns() }) { Text("Editar biblioteca") }
                }
                LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.height(320.dp)) {
                    items(patterns.filter { it.enabled }, key = { it.id }) { p ->
                        Card(
                            onClick = { vm.insertPattern(p, canvasSize.first, canvasSize.second); showPatterns = false },
                            modifier = Modifier.padding(6.dp),
                        ) {
                            InkPreview(p.outputInk, Modifier.fillMaxWidth().height(80.dp).background(MaterialTheme.colorScheme.surface))
                            Text(p.name, Modifier.padding(8.dp), maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    vm.teaching?.takeIf { !vm.isDrawing }?.let { offer ->
        TeachSheet(
            offer = offer,
            vm = vm,
            patterns = patterns.filter { it.enabled },
            learn = settings.learnFromCorrections,
            onNewPattern = { gesture -> vm.teaching = null; onTeachNewPattern(gesture) },
        )
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
    ModalBottomSheet(onDismissRequest = { vm.teaching = null }) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("¿Qué patrón era?", style = MaterialTheme.typography.titleMedium)
            if (groups.size > 1) {
                Text("Trazos que forman el gesto:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEachIndexed { i, (_, strokes) ->
                        Card(
                            onClick = { chosen = i },
                            modifier = Modifier.border(
                                if (i == chosen) 3.dp else 0.dp,
                                if (i == chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                MaterialTheme.shapes.medium,
                            ),
                        ) {
                            InkPreview(strokes, Modifier.size(88.dp).background(MaterialTheme.colorScheme.surface))
                            Text(if (strokes.size == 1) "1 trazo" else "${strokes.size} trazos", Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text(
                if (learn) "Reemplazar y recordar este gesto como ejemplo:" else "Reemplazar por:",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.height(260.dp)) {
                items(patterns, key = { it.id }) { p ->
                    Card(
                        onClick = { vm.teach(p, group.first); vm.teaching = null },
                        modifier = Modifier.padding(6.dp),
                    ) {
                        InkPreview(p.outputInk, Modifier.fillMaxWidth().height(72.dp).background(MaterialTheme.colorScheme.surface))
                        Text(p.name, Modifier.padding(8.dp), maxLines = 1)
                    }
                }
            }
            TextButton(onClick = { onNewPattern(group.second) }) { Text("Crear un patrón nuevo con este gesto") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolBar(
    vm: EditorViewModel,
    gesturesOn: Boolean,
    penColor: Int,
    penWidth: Float,
    onGestures: (Boolean) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onPatterns: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolChip("Lápiz", vm.tool == Tool.PEN) { vm.selectTool(Tool.PEN) }
            ToolChip("Goma", vm.tool == Tool.ERASER) { vm.selectTool(Tool.ERASER) }
            ToolChip("Goma de trazo", vm.tool == Tool.STROKE_ERASER) { vm.selectTool(Tool.STROKE_ERASER) }
            ToolChip("Lazo", vm.tool == Tool.LASSO) { vm.selectTool(Tool.LASSO) }
            VerticalDivider(Modifier.height(28.dp))
            TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text("Deshacer") }
            TextButton(onClick = vm::redo, enabled = vm.canRedo) { Text("Rehacer") }
            VerticalDivider(Modifier.height(28.dp))
            InkColors.forEach { c ->
                Box(
                    Modifier
                        .size(28.dp)
                        .background(Color(c), CircleShape)
                        .border(if (c == penColor) 3.dp else 1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (c == penColor) 1f else 0.3f), CircleShape)
                        .clickable { onColor(c) },
                )
            }
            listOf(2f to "Fino", 3f to "Medio", 5f to "Grueso").forEach { (w, label) ->
                ToolChip(label, penWidth == w) { onWidth(w) }
            }
            VerticalDivider(Modifier.height(28.dp))
            ToolChip("Gestos 1 s", gesturesOn) { onGestures(!gesturesOn) }
            TextButton(onClick = onPatterns) { Text("Patrones") }
        }
    }
}

@Composable
private fun PatternRail(patterns: List<InkPattern>, onInsert: (InkPattern) -> Unit, onLibrary: () -> Unit) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.width(136.dp).fillMaxHeight()) {
        Column {
            Text("Patrones", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(patterns, key = { it.id }) { p ->
                    Card(onClick = { onInsert(p) }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()) {
                        InkPreview(p.outputInk, Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface))
                        Text(p.name, Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = onLibrary, modifier = Modifier.padding(4.dp)) { Text("Biblioteca") }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SelectionBar(vm: EditorViewModel) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.controller.clearSelection() }) { Icon(Icons.Filled.Close, "Quitar selección") }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.controller.rotateSelection(-15f) }) { Text("Girar −15°") }
                TextButton(onClick = { vm.controller.rotateSelection(15f) }) { Text("Girar +15°") }
                TextButton(onClick = { vm.controller.scaleSelection(0.85f) }) { Text("Achicar") }
                TextButton(onClick = { vm.controller.scaleSelection(1.15f) }) { Text("Agrandar") }
                TextButton(onClick = vm::duplicateSelection) { Text("Duplicar") }
                InkColors.forEach { c ->
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(24.dp)
                            .background(Color(c), CircleShape)
                            .clickable { vm.recolorSelection(c) },
                    )
                }
                IconButton(onClick = { vm.controller.deleteSelection() }) { Icon(Icons.Filled.Delete, "Borrar selección") }
            }
        }
    }
}

@Composable
private fun EditorMenu(
    pageIndex: Int,
    pageCount: Int,
    onFit: () -> Unit,
    onSelectAll: () -> Unit,
    onDuplicatePage: () -> Unit,
    onMovePage: (Int) -> Unit,
    onDeletePage: () -> Unit,
    onPdf: () -> Unit,
    onPng: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Ajustar a la página") }, onClick = { open = false; onFit() })
            DropdownMenuItem(text = { Text("Seleccionar todo") }, onClick = { open = false; onSelectAll() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Duplicar página") }, onClick = { open = false; onDuplicatePage() })
            DropdownMenuItem(
                text = { Text("Mover página antes") }, enabled = pageIndex > 0,
                onClick = { open = false; onMovePage(-1) },
            )
            DropdownMenuItem(
                text = { Text("Mover página después") }, enabled = pageIndex < pageCount - 1,
                onClick = { open = false; onMovePage(1) },
            )
            DropdownMenuItem(
                text = { Text("Eliminar página…") }, enabled = pageCount > 1,
                onClick = { open = false; onDeletePage() },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Exportar cuaderno como PDF…") }, onClick = { open = false; onPdf() })
            DropdownMenuItem(text = { Text("Exportar página como PNG…") }, onClick = { open = false; onPng() })
            DropdownMenuItem(text = { Text("Ajustes") }, onClick = { open = false; onSettings() })
        }
    }
}

@Composable
private fun PaperMenu(current: Paper, onSelect: (Paper) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(paperName(current)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Paper.entries.forEach { p ->
                DropdownMenuItem(text = { Text(paperName(p)) }, onClick = { onSelect(p); open = false })
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
