@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import io.trazo.app.ink.InkCanvasView
import io.trazo.app.trazo
import io.trazo.core.OutputSizing
import io.trazo.core.Paper

@Composable
fun PatternLibraryScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val repo = LocalContext.current.trazo.repository
    val patterns by repo.patterns.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patrones") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    TextButton(onClick = repo::restoreDefaultPatterns) {
                        Icon(Icons.Filled.Refresh, null); Text(" Restaurar ejemplos")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEdit(null) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Enseñar patrón") })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(patterns, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = { onEdit(p.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Vista previa en tinta: gesto → salida.
                        InkPreview(p.gestureExamples.first(), Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 8.dp))
                        InkPreview(p.outputInk, Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${p.gestureExamples.size} ejemplo(s) · ${p.outputInk.size} trazo(s) de salida",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Switch(checked = p.enabled, onCheckedChange = { repo.setPatternEnabled(p.id, it) })
                        IconButton(onClick = { onEdit(p.id) }) { Icon(Icons.Filled.Edit, "Editar") }
                        IconButton(onClick = { repo.deletePattern(p.id) }) { Icon(Icons.Filled.Delete, "Eliminar") }
                    }
                }
            }
        }
    }
}

@Composable
fun PatternEditorScreen(patternId: String?, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PatternEditorViewModel = viewModel(key = "pattern-${patternId ?: "new"}") { PatternEditorViewModel(app, patternId) }
    val settings by app.trazo.settings.settings.collectAsState()
    val density = LocalDensity.current.density
    vm.revision // recomponer al dibujar

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Enseñar patrón" else "Editar patrón") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    TextButton(onClick = { if (vm.save()) onDone() }, enabled = vm.canSave()) { Text("Guardar") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            OutlinedTextField(value = vm.name, onValueChange = { vm.name = it }, label = { Text("Nombre (solo para el menú)") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints {
                val wide = maxWidth > 600.dp
                val gesturePad: @Composable (Modifier) -> Unit = { m ->
                    Card(m) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Gesto a reconocer", style = MaterialTheme.typography.titleSmall)
                            ScrollableTabRow(selectedTabIndex = vm.currentExample, edgePadding = 0.dp) {
                                vm.gestureExamples.forEachIndexed { i, _ ->
                                    Tab(selected = i == vm.currentExample, onClick = { vm.currentExample = i }, text = { Text("Ejemplo ${i + 1}") })
                                }
                                Tab(selected = false, onClick = vm::addExample, text = { Text("+") })
                            }
                            key(vm.currentExample, vm.gestureExamples.size) {
                                InkPad(vm.gestureExamples[vm.currentExample], settings.holdMillis, density)
                            }
                            Row {
                                TextButton(onClick = { vm.gestureExamples[vm.currentExample].controller.undo() }) { Text("Deshacer") }
                                TextButton(onClick = vm::removeCurrentExample) { Text("Borrar ejemplo") }
                            }
                        }
                    }
                }
                val outputPad: @Composable (Modifier) -> Unit = { m ->
                    Card(m) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Cómo quiero que quede (tu tinta)", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(48.dp))
                            InkPad(vm.output, settings.holdMillis, density)
                            Row {
                                TextButton(onClick = { vm.output.controller.undo() }) { Text("Deshacer") }
                                TextButton(onClick = { vm.output.clear() }) { Text("Borrar todo") }
                            }
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

            Spacer(Modifier.height(16.dp))
            Text("Tamaño de la salida", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    OutputSizing.MATCH_HEIGHT to "Alto del gesto",
                    OutputSizing.MATCH_WIDTH to "Ancho del gesto",
                    OutputSizing.FIT_GESTURE to "Dentro del gesto",
                    OutputSizing.FIXED to "Fijo",
                ).forEach { (s, label) -> FilterChip(selected = vm.sizing == s, onClick = { vm.sizing = s }, label = { Text(label) }) }
            }
            LabeledSlider("Escala ${"%.2f".format(vm.outputScale)}×", vm.outputScale, 0.4f..2.5f) { vm.outputScale = it }
            LabeledSlider("Confianza mínima ${"%.0f".format(vm.minScore * 100)} %", vm.minScore, 0.6f..0.97f) { vm.minScore = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.customHold, onCheckedChange = { vm.customHold = it })
                Text("  Pausa propia para este patrón")
            }
            if (vm.customHold) LabeledSlider("Pausa ${vm.holdMillis.toLong()} ms", vm.holdMillis, 500f..2000f, steps = 14) { vm.holdMillis = it }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("Probar: dibujá el gesto y sostené la punta", style = MaterialTheme.typography.titleSmall)
                    Text(vm.testResult ?: " ", style = MaterialTheme.typography.labelLarge)
                    InkPad(vm.testPad, settings.holdMillis, density, recognize = true)
                    TextButton(onClick = vm::clearTest) { Text("Limpiar prueba") }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun InkPad(pad: Pad, holdMillis: Long, density: Float, recognize: Boolean = false) {
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
                paper = Paper.GRID
                controller = pad.controller
            }
        },
        update = { it.controller = pad.controller },
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.width(420.dp))
    }
}

private const val PAD_SIZE = 400f
