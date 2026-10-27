@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import io.trazo.app.data.PaperTone
import io.trazo.app.ink.InkCanvasView
import io.trazo.app.ink.PaperColors
import io.trazo.app.trazo
import io.trazo.core.InkPattern
import io.trazo.core.InkStroke
import io.trazo.core.OutputSizing
import io.trazo.core.Paper
import io.trazo.core.PatternCategory

/**
 * Biblioteca: los patrones agrupados por sección (básicos, los tuyos, números, operadores…), con
 * búsqueda, activar o desactivar una sección entera, y editar o eliminar cada patrón.
 */
@Composable
fun PatternLibraryScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val context = LocalContext.current
    val repo = context.trazo.repository
    val patterns by repo.patterns.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InkPattern?>(null) }
    val sections = remember(patterns) { patternSections(patterns) }
    val shown = remember(sections, query) { filterSections(sections, query) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use(repo::exportPatterns) }
            .onSuccess { Toast.makeText(context, "Patrones exportados", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "No se pudo exportar: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.use(repo::importPatterns) ?: 0 }
            .onSuccess { n -> Toast.makeText(context, "Patrones agregados: $n", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Archivo no válido: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Biblioteca de patrones", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${patterns.count { it.enabled }} activos de ${patterns.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                            DropdownMenuItem(
                                text = { Text("Importar patrones…") },
                                leadingIcon = { Icon(TrazoIcons.Import, null) },
                                onClick = { menu = false; importLauncher.launch(arrayOf("application/json", "*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Exportar patrones…") },
                                leadingIcon = { Icon(TrazoIcons.Export, null) },
                                onClick = { menu = false; exportLauncher.launch("patrones.trazo-patrones.json") },
                            )
                            DropdownMenuItem(
                                text = { Text("Restaurar signos de partida") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = {
                                    menu = false
                                    repo.restoreDefaultPatterns()
                                    Toast.makeText(context, "Básicos y signos restaurados; tus patrones no cambiaron", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEdit(null) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Enseñar patrón") })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query, { query = it }, "Buscar signo o sección",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (patterns.isEmpty()) "No hay patrones. Enseñá uno o restaurá los signos de partida desde el menú."
                        else "No hay patrones que coincidan.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            LazyVerticalGrid(
                GridCells.Adaptive(136.dp),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            ) {
                shown.forEach { (name, list) ->
                    val open = query.isNotBlank() || name !in collapsed
                    item(key = "h:$name", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            name, list, open,
                            onToggleOpen = { collapsed = if (name in collapsed) collapsed - name else collapsed + name },
                            onEnable = { on -> repo.setPatternsEnabled(list.map { it.id }, on) },
                        )
                    }
                    if (open) {
                        items(list, key = { "p:${it.id}" }) { p ->
                            LibraryTile(
                                p,
                                onOpen = { onEdit(p.id) },
                                onToggle = { repo.setPatternEnabled(p.id, !p.enabled) },
                                onDelete = { deleting = p },
                            )
                        }
                    }
                }
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar «${p.name}»?") },
            text = {
                Text(
                    if (p.builtIn) "Se borra de la biblioteca. Podés recuperarlo con «Restaurar signos de partida»."
                    else "Se borran el patrón y sus ejemplos. Lo que ya escribiste en tus cuadernos no cambia.",
                )
            },
            confirmButton = { TextButton(onClick = { repo.deletePattern(p.id); deleting = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Encabezado de sección: plegar, cuántos están activos y una casilla para toda la sección. */
@Composable
private fun SectionHeader(
    name: String,
    list: List<InkPattern>,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onEnable: (Boolean) -> Unit,
) {
    val on = list.count { it.enabled }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = if (open) "Plegar sección" else "Desplegar sección", onClick = onToggleOpen)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            SectionLabel(name)
            Text(
                "$on de ${list.size} activos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TriStateCheckbox(
            state = when (on) {
                0 -> ToggleableState.Off
                list.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            },
            onClick = { onEnable(on < list.size) },
            modifier = Modifier.semantics { contentDescription = "Activar toda la sección $name" },
        )
    }
}

/** Tarjeta de la biblioteca: la tinta de salida, el gesto en miniatura y un menú. */
@Composable
private fun LibraryTile(p: InkPattern, onOpen: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.padding(4.dp),
    ) {
        Column(Modifier.padding(start = 8.dp, top = 8.dp)) {
            Box(Modifier.fillMaxWidth().height(76.dp).padding(end = 8.dp).alpha(if (p.enabled) 1f else 0.38f)) {
                InkPreview(p.outputInk, Modifier.fillMaxSize().padding(start = 26.dp))
                InkPreview(p.gestureExamples.firstOrNull().orEmpty(), Modifier.size(32.dp).align(Alignment.TopStart), color = scheme.secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (p.enabled) (if (p.gestureExamples.size == 1) "1 ejemplo" else "${p.gestureExamples.size} ejemplos") else "Desactivado",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (p.enabled) scheme.onSurfaceVariant else scheme.error,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opciones de ${p.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                        DropdownMenuItem(
                            text = { Text(if (p.enabled) "Desactivar" else "Activar") },
                            leadingIcon = { Icon(if (p.enabled) Icons.Filled.Close else Icons.Filled.Check, null) },
                            onClick = { menu = false; onToggle() },
                        )
                        DropdownMenuItem(
                            text = { Text("Editar") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar…") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

private val SizingOptions = listOf(
    OutputSizing.MATCH_HEIGHT to "Alto del gesto",
    OutputSizing.MATCH_WIDTH to "Ancho del gesto",
    OutputSizing.FIT_GESTURE to "Dentro del gesto",
    OutputSizing.FIXED to "Fijo",
)

@Composable
fun PatternEditorScreen(patternId: String?, seedGesture: List<InkStroke>? = null, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PatternEditorViewModel = viewModel(key = "pattern-${patternId ?: "new"}") { PatternEditorViewModel(app, patternId, seedGesture) }
    val settings by app.trazo.settings.settings.collectAsState()
    val density = LocalDensity.current.density
    val paper = paperColors(PaperTone.THEME)
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    vm.revision // recomponer al dibujar

    @Composable
    fun pad(p: Pad, recognize: Boolean = false) = InkPad(p, settings.holdMillis, density, paper, accent, recognize)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Enseñar patrón" else "Editar patrón") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    Button(onClick = { if (vm.save()) onDone() }, enabled = vm.canSave(), modifier = Modifier.padding(end = 8.dp)) { Text("Guardar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text("Nombre (solo para el menú)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            )
            SectionLabel("Sección")
            ChoiceRow(PatternCategory.ORDER, vm.category ?: PatternCategory.MINE, { it }, { c -> vm.category = c.takeIf { it != PatternCategory.MINE } })
            BoxWithConstraints {
                val wide = maxWidth > 600.dp
                val gesturePad: @Composable (Modifier) -> Unit = { m ->
                    PadCard("Gesto a reconocer", m) {
                        ScrollableTabRow(selectedTabIndex = vm.currentExample, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            vm.gestureExamples.forEachIndexed { i, _ ->
                                Tab(selected = i == vm.currentExample, onClick = { vm.currentExample = i }, text = { Text("Ejemplo ${i + 1}") })
                            }
                            Tab(selected = false, onClick = vm::addExample, text = { Icon(Icons.Filled.Add, "Agregar ejemplo") })
                        }
                        key(vm.currentExample, vm.gestureExamples.size) { pad(vm.gestureExamples[vm.currentExample]) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolButton(TrazoIcons.Undo, "Deshacer", onClick = { vm.gestureExamples[vm.currentExample].controller.undo() })
                            TextButton(onClick = vm::removeCurrentExample) { Text("Borrar ejemplo") }
                        }
                    }
                }
                val outputPad: @Composable (Modifier) -> Unit = { m ->
                    PadCard("Cómo quiero que quede (tu tinta)", m) {
                        Spacer(Modifier.height(48.dp))
                        pad(vm.output)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolButton(TrazoIcons.Undo, "Deshacer", onClick = { vm.output.controller.undo() })
                            TextButton(onClick = { vm.output.clear() }) { Text("Borrar todo") }
                        }
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        gesturePad(Modifier.weight(1f))
                        outputPad(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        gesturePad(Modifier.fillMaxWidth())
                        outputPad(Modifier.fillMaxWidth())
                    }
                }
            }

            SectionLabel("Tamaño de la salida")
            ChoiceRow(SizingOptions.map { it.first }, vm.sizing, { s -> SizingOptions.first { it.first == s }.second }, { vm.sizing = it })
            Column(Modifier.widthIn(max = 520.dp)) {
                LabeledSlider("Escala", "%.2f×".format(vm.outputScale), vm.outputScale, 0.4f..2.5f, onChange = { vm.outputScale = it })
                LabeledSlider(
                    "Confianza mínima", "${"%.0f".format(vm.minScore * 100)} %", vm.minScore, 0.6f..0.97f,
                    onChange = { vm.minScore = it },
                )
                SwitchRow("Pausa propia para este patrón", vm.customHold, { vm.customHold = it })
                if (vm.customHold) {
                    LabeledSlider("Pausa", "${vm.holdMillis.toLong()} ms", vm.holdMillis, 500f..2000f, onChange = { vm.holdMillis = it }, steps = 14)
                }
                SwitchRow(
                    "Barra elástica", vm.elastic, { vm.elastic = it },
                    subtitle = "La barra final llega hasta donde termina el gesto (como la de la raíz).",
                )
                if (vm.elasticMissing()) {
                    Text(
                        "No encontré una barra horizontal a la derecha de la salida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            PadCard("Probar: dibujá el gesto y sostené la punta", Modifier.fillMaxWidth()) {
                Text(vm.testResult ?: " ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                pad(vm.testPad, recognize = true)
                TextButton(onClick = vm::clearTest) { Text("Limpiar prueba") }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PadCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

@Composable
private fun InkPad(pad: Pad, holdMillis: Long, density: Float, paper: PaperColors, accent: Int, recognize: Boolean = false) {
    pad.controller.holdMillis = holdMillis
    pad.controller.tremorRadiusPx = 6f * density
    pad.controller.minInkLengthPx = 12f * density
    pad.controller.recognitionEnabled = recognize
    AndroidView(
        factory = { ctx ->
            InkCanvasView(ctx).apply {
                mode = InkCanvasView.Mode.PAD
                pageWidth = PAD_SIZE
                pageHeight = PAD_SIZE
                this.paper = Paper.GRID
                controller = pad.controller
            }
        },
        update = {
            it.controller = pad.controller
            it.paperColors = paper
            it.accentColor = accent
        },
        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(10.dp)),
    )
}

private const val PAD_SIZE = 400f
