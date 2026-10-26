@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import io.trazo.core.Paper
import io.trazo.core.Tool

@Composable
fun EditorScreen(notebookId: String, onBack: () -> Unit, onPatterns: () -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = "editor-$notebookId") { EditorViewModel(app, notebookId) }
    val settings by app.trazo.settings.settings.collectAsState()
    val patterns by app.trazo.repository.patterns.collectAsState()
    val density = LocalDensity.current.density
    val snackbar = remember { SnackbarHostState() }
    var showPatterns by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(settings, density) { vm.applySettings(settings, density) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.saveNow() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm.lastConversion) {
        val c = vm.lastConversion ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Reemplazado en tinta: ${c.pattern.name}", actionLabel = "Deshacer", withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) vm.undo()
        vm.lastConversion = null
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
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ajustes") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                        v.controller = vm.controller
                        v.viewport = vm.viewport
                        v.paper = vm.paper
                        v.fingerDraws = settings.fingerDraws
                        v.darkPaper = dark
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    vm.ambiguous?.let { amb ->
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
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SelectionBar(vm: EditorViewModel) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Selección", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { vm.controller.rotateSelection(-15f) }) { Text("Girar −15°") }
            TextButton(onClick = { vm.controller.rotateSelection(15f) }) { Text("Girar +15°") }
            TextButton(onClick = { vm.controller.scaleSelection(0.85f) }) { Text("Achicar") }
            TextButton(onClick = { vm.controller.scaleSelection(1.15f) }) { Text("Agrandar") }
            IconButton(onClick = { vm.controller.deleteSelection() }) { Icon(Icons.Filled.Delete, "Borrar selección") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.controller.clearSelection() }) { Icon(Icons.Filled.Close, "Quitar selección") }
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
