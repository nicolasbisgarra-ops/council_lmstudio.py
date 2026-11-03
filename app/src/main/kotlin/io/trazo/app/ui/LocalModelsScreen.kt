@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.trazo.app.data.LocalModelStore
import io.trazo.app.data.ModelDownloadRequest
import io.trazo.app.ai.LocalModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/** Standalone local model catalog; callers can add this destination to their navigation stack. */
@Composable
fun LocalModelsScreen(onBack: () -> Unit, onUse: (LocalModel) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(context) { LocalModelStore(context) }
    val models by store.models.collectAsState()
    val projectors by store.projectors.collectAsState()
    val selectedId by store.selectedModelId.collectAsState()
    val downloadProgress by store.downloadProgress.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<LocalModel?>(null) }
    var projectorTarget by remember { mutableStateOf<LocalModel?>(null) }
    var removeProjectorTarget by remember { mutableStateOf<java.io.File?>(null) }
    var downloadDialog by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                try {
                    val imported = store.import(uri)
                    message = "Modelo importado: ${store.displayName(imported)}"
                } catch (error: Exception) {
                    message = error.localizedMessage ?: "No se pudo importar el modelo."
                } finally {
                    busy = false
                }
            }
        }
    }
    val projectorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                try {
                    val file = store.importProjector(uri)
                    message = "Proyector importado: ${file.name}"
                } catch (error: Exception) {
                    message = error.localizedMessage ?: "No se pudo importar el proyector."
                } finally {
                    busy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modelos locales") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Importá modelos para usarlos sin conexión. Trazo comprueba el encabezado, pero el motor todavía puede rechazarlos. Se copian al almacenamiento privado; límite de 8 GB por archivo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = !busy,
                onClick = { importPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(TrazoIcons.Import, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Importando…" else "Importar .task o .gguf")
            }
            OutlinedButton(
                enabled = !busy && models.any { it.modelFile.extension.equals("gguf", ignoreCase = true) },
                onClick = { projectorPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(TrazoIcons.Import, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Importar proyector de imagen .mmproj para GGUF")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = { downloadDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(TrazoIcons.Import, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Descargar modelo desde URL HTTPS verificada")
            }
            downloadProgress?.let { progress ->
                val total = progress.totalBytes
                val mib = progress.downloadedBytes / (1024 * 1024)
                Text(
                    if (total == null || total <= 0L) "Descarga: $mib MiB" else
                        "Descarga: $mib / ${total / (1024 * 1024)} MiB (${progress.downloadedBytes * 100 / total}%)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (downloadJob?.isActive == true) {
                TextButton(onClick = { downloadJob?.cancel() }) { Text("Cancelar descarga") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (models.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AiStarsIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Todavía no hay modelos", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Elegí un archivo .task compatible con MediaPipe o un .gguf compatible con llama.cpp.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    items(models, key = { it.id }) { model ->
                        LocalModelRow(
                            model = model,
                            name = store.displayName(model),
                            selected = selectedId == model.id,
                            projectorName = model.projectorFile?.name,
                            onSelect = { store.select(model.id) },
                            onRemove = { removeTarget = model },
                            onProjector = { projectorTarget = model },
                            onUse = { store.select(model.id); onUse(model) },
                        )
                    }
                }
            }
        }
    }

    if (downloadDialog) {
        var url by remember { mutableStateOf("") }
        var fileName by remember { mutableStateOf("") }
        var digest by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { downloadDialog = false },
            title = { Text("Descargar modelo local") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hugging Face exige una revisión fija de 40 dígitos. Otras URL HTTPS necesitan SHA-256.")
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL HTTPS") }, singleLine = true)
                    OutlinedTextField(value = fileName, onValueChange = { fileName = it }, label = { Text("Nombre archivo .task, .gguf o .mmproj") }, singleLine = true)
                    OutlinedTextField(value = digest, onValueChange = { digest = it }, label = { Text("SHA-256 opcional para Hugging Face fijo") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val request = try {
                        ModelDownloadRequest(url.trim(), fileName.trim(), digest.trim().ifBlank { null })
                    } catch (failure: IllegalArgumentException) {
                        message = failure.localizedMessage ?: "Revisá la dirección y el hash."
                        null
                    }
                    if (request != null) {
                        downloadDialog = false
                        downloadJob = scope.launch {
                            busy = true
                            message = null
                            try {
                                if (request.fileName.endsWith(".mmproj", ignoreCase = true)) {
                                    val file = store.downloadProjector(request)
                                    message = "Proyector descargado: ${file.name}"
                                } else {
                                    val model = store.download(request)
                                    message = "Modelo descargado: ${store.displayName(model)}"
                                }
                            } catch (_: CancellationException) {
                                message = "Descarga cancelada; podés reanudarla después."
                            } catch (failure: Exception) {
                                message = failure.localizedMessage ?: "No se pudo descargar el modelo."
                            } finally {
                                busy = false
                                downloadJob = null
                            }
                        }
                    }
                }) { Text("Descargar") }
            },
            dismissButton = { TextButton(onClick = { downloadDialog = false }) { Text("Cancelar") } },
        )
    }

    removeTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("¿Quitar este modelo?") },
            text = { Text("Se borrará la copia local de ${store.displayName(model)}. El archivo original no cambia.") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch {
                        runCatching { store.remove(model.id) }
                            .onFailure { message = it.localizedMessage ?: "No se pudo quitar el modelo." }
                    }
                }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancelar") } },
        )
    }

    projectorTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { projectorTarget = null },
            title = { Text("Proyector de imagen") },
            text = {
                if (projectors.isEmpty()) Text("Importá un archivo .mmproj para habilitar imágenes con este modelo GGUF.")
                else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    projectors.forEach { file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    store.attachProjector(model.id, file)
                                    projectorTarget = null
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            IconButton(onClick = {
                                projectorTarget = null
                                removeProjectorTarget = file
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Quitar ${file.name}") }
                        }
                    }
                    TextButton(onClick = {
                        store.attachProjector(model.id, null)
                        projectorTarget = null
                    }) { Text("Sin proyector") }
                }
            },
            confirmButton = { TextButton(onClick = { projectorTarget = null }) { Text("Cerrar") } },
        )
    }

    removeProjectorTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { removeProjectorTarget = null },
            title = { Text("¿Quitar este proyector?") },
            text = { Text("Se borrará la copia local de ${file.name}. Los modelos originales no cambian.") },
            confirmButton = {
                TextButton(onClick = {
                    removeProjectorTarget = null
                    scope.launch {
                        runCatching { store.removeProjector(file) }
                            .onFailure { message = it.localizedMessage ?: "No se pudo quitar el proyector." }
                    }
                }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { removeProjectorTarget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LocalModelRow(
    model: LocalModel,
    name: String,
    selected: Boolean,
    projectorName: String?,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onProjector: () -> Unit,
    onUse: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 8.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AiStarsIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${model.modelFile.extension.uppercase()} · ${model.modelFile.length().formatBytes()}" + if (selected) " · Seleccionado" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Quitar $name")
                }
            }
            TextButton(onClick = onUse, modifier = Modifier.align(Alignment.End)) { Text("Usar") }
            if (model.modelFile.extension.equals("gguf", ignoreCase = true)) {
                TextButton(onClick = onProjector, modifier = Modifier.align(Alignment.End)) {
                    Text(projectorName?.let { "Proyector: $it" } ?: "Asociar proyector .mmproj")
                }
            }
        }
    }
}

private fun Long.formatBytes(): String = when {
    this >= 1024L * 1024L * 1024L -> "%.1f GB".format(this / (1024.0 * 1024.0 * 1024.0))
    this >= 1024L * 1024L -> "%.0f MB".format(this / (1024.0 * 1024.0))
    else -> "%.0f KB".format(this / 1024.0)
}
