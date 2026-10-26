@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.trazo.app.data.NotebookSummary
import io.trazo.app.trazo
import io.trazo.core.Bounds
import io.trazo.core.Notebook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun NotebookListScreen(onOpen: (String) -> Unit, onPatterns: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = context.trazo.repository
    var items by remember { mutableStateOf(emptyList<NotebookSummary>()) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NotebookSummary?>(null) }
    var deleting by remember { mutableStateOf<NotebookSummary?>(null) }
    // Sobrevive a una recreación mientras el selector de archivos está abierto.
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        scope.launch { items = withContext(Dispatchers.IO) { repo.listNotebooks() } }
    }
    LaunchedEffect(Unit) { refresh() }

    /** Lectura/escritura de archivos fuera del hilo principal, con aviso del resultado. */
    fun io(done: String, failed: String, block: () -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            if (error == null) {
                refresh()
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "$failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId ?: return@rememberLauncherForActivityResult
        exportId = null
        if (uri != null) io("Cuaderno exportado", "No se pudo exportar") {
            val out = context.contentResolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
            out.use { repo.exportNotebook(id, it) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io("Cuaderno importado", "Archivo no válido") {
            val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo")
            input.use { repo.importNotebook(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trazo · cuadernos") },
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Text("Importar")
                    }
                    TextButton(onClick = onPatterns) { Text("Patrones") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ajustes") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Nuevo cuaderno") },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Creá un cuaderno y escribí con el lápiz.\nDibujá una integral y sostené la punta 1 s.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { nb ->
                Card(onClick = { onOpen(nb.id) }) {
                    InkPreview(
                        nb.firstPage.strokes,
                        Modifier.fillMaxWidth().aspectRatio(Notebook.PAGE_WIDTH / Notebook.PAGE_HEIGHT).background(Color(0xFFFBFAF5)),
                        frame = Bounds(0f, 0f, Notebook.PAGE_WIDTH, Notebook.PAGE_HEIGHT),
                    )
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(nb.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${nb.pageCount} pág. · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(nb.updatedAt)),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Row(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                        IconButton(onClick = { renaming = nb }) { Icon(Icons.Filled.Edit, "Renombrar") }
                        TextButton(onClick = { exportId = nb.id; exportLauncher.launch("${nb.name}.trazo") }) {
                            Text("Exportar")
                        }
                        IconButton(onClick = { deleting = nb }) { Icon(Icons.Filled.Delete, "Eliminar") }
                    }
                }
            }
        }
    }

    if (creating) NameDialog("Nuevo cuaderno", "Cálculo") { name ->
        creating = false
        if (name != null) onOpen(repo.createNotebook(name, context.trazo.settings.settings.value.paper).id)
    }
    renaming?.let { nb ->
        NameDialog("Renombrar", nb.name) { name ->
            renaming = null
            if (name != null) { repo.renameNotebook(nb.id, name); refresh() }
        }
    }
    deleting?.let { nb ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar \"${nb.name}\"?") },
            text = { Text("Se borran sus páginas y trazos de este dispositivo.") },
            confirmButton = { TextButton(onClick = { repo.deleteNotebook(nb.id); deleting = null; refresh() }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Nombre") }) },
        confirmButton = { TextButton(onClick = { onResult(text.trim()) }, enabled = text.isNotBlank()) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancelar") } },
    )
}
