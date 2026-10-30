@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.trazo.app.data.NotebookSummary
import io.trazo.app.ink.PaperColors
import io.trazo.app.trazo
import io.trazo.core.Folder
import io.trazo.core.FolderCatalog
import io.trazo.core.FolderNotEmptyException
import io.trazo.core.Notebook
import io.trazo.core.NotebookOrder
import io.trazo.core.Paper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Orden de la lista de cuadernos. */
private enum class NotebookSort(val label: String) { RECENT("Recientes"), NAME("Nombre"), MANUAL("Manual") }

/** Filtros de la fila de carpetas que no son una carpeta. */
private const val FILTER_ALL = "\u0000todos"
private const val FILTER_NONE = "\u0000sin-carpeta"

/** Colores para las carpetas. */
private val FOLDER_COLORS = listOf(
    0xFF607D8BL, 0xFFE53935L, 0xFFFB8C00L, 0xFFFDD835L, 0xFF43A047L, 0xFF00ACC1L, 0xFF1E88E5L, 0xFF8E24AAL, 0xFFD81B60L,
)

@Composable
fun NotebookListScreen(onOpen: (String) -> Unit, onPatterns: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = context.trazo.repository
    val settings by context.trazo.settings.settings.collectAsState()
    var items by remember { mutableStateOf(emptyList<NotebookSummary>()) }
    var loaded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NotebookSummary?>(null) }
    var deleting by remember { mutableStateOf<NotebookSummary?>(null) }
    var folders by remember { mutableStateOf(FolderCatalog()) }
    var order by remember { mutableStateOf(emptyList<String>()) }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var addingFolder by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var recoloringFolder by remember { mutableStateOf<Folder?>(null) }
    var moving by remember { mutableStateOf<NotebookSummary?>(null) }
    var coverFor by remember { mutableStateOf<NotebookSummary?>(null) }
    var coverId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(NotebookSort.RECENT) }
    var menu by remember { mutableStateOf(false) }
    // Sobrevive a una recreación mientras el selector de archivos está abierto.
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val colors = paperColors(settings.paperTone)
    fun refresh() {
        scope.launch {
            val (list, cat, ord) = withContext(Dispatchers.IO) { Triple(repo.listNotebooks(), repo.folders(), repo.manualOrder()) }
            items = list
            folders = cat
            order = ord
            // La carpeta elegida pudo haberse borrado.
            if (filter != FILTER_ALL && filter != FILTER_NONE && cat[filter] == null) filter = FILTER_ALL
            loaded = true
        }
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

    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverId ?: return@rememberLauncherForActivityResult
        coverId = null
        if (uri != null) io("Portada cambiada", "No se pudo usar la imagen") {
            repo.setCoverImage(id) { context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir la imagen") }
        }
    }

    val shown = remember(items, query, sort, filter, order) {
        val q = searchKey(query)
        val inFolder = when (filter) {
            FILTER_ALL -> items
            FILTER_NONE -> items.filter { it.folderId == null || folders[it.folderId] == null }
            else -> items.filter { it.folderId == filter }
        }
        val filtered = if (q.isEmpty()) inFolder else inFolder.filter { searchKey(it.name).contains(q) }
        when (sort) {
            NotebookSort.RECENT -> filtered.sortedByDescending { it.updatedAt }
            NotebookSort.NAME -> filtered.sortedBy { searchKey(it.name) }
            NotebookSort.MANUAL -> NotebookOrder.sorted(filtered, order, { it.id }, { it.updatedAt })
        }
    }

    /** Sube o baja un cuaderno un lugar entre los que se ven, en el orden manual completo. */
    fun shift(nb: NotebookSummary, delta: Int) {
        val i = shown.indexOfFirst { it.id == nb.id }
        val j = i + delta
        if (i < 0 || j !in shown.indices) return
        val full = NotebookOrder.sorted(items, order, { it.id }, { it.updatedAt }).map { it.id }
        // Subir: antes del anterior. Bajar: antes del que sigue al siguiente (o al final).
        val target = if (delta < 0) shown[j].id else shown.getOrNull(j + 1)?.id
        val next = if (target == null && delta > 0) {
            NotebookOrder.moved(full, nb.id, full.getOrNull(full.indexOf(shown[j].id) + 1))
        } else NotebookOrder.moved(full, nb.id, target)
        order = next
        scope.launch { withContext(Dispatchers.IO) { repo.saveManualOrder(next) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(query, { query = it }, "Buscar cuaderno", Modifier.fillMaxWidth().padding(end = 4.dp))
                    } else {
                        Column {
                            Text("Cuadernos")
                            Text(
                                if (items.size == 1) "1 cuaderno" else "${items.size} cuadernos",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Filled.Close, "Cerrar búsqueda") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Buscar") }
                    }
                    Box {
                        // Compacto y pegado al título para que no empuje el texto ni quede debajo.
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "Más opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                            NotebookSort.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text("Ordenar: ${o.label}") },
                                    leadingIcon = { Icon(TrazoIcons.Sort, null) },
                                    trailingIcon = if (sort == o) {
                                        { Icon(Icons.Filled.Check, "Elegido") }
                                    } else null,
                                    onClick = { menu = false; sort = o },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Importar cuaderno…") },
                                leadingIcon = { Icon(TrazoIcons.Import, null) },
                                onClick = { menu = false; importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Biblioteca de patrones") },
                                leadingIcon = { Icon(TrazoIcons.Patterns, null) },
                                onClick = { menu = false; onPatterns() },
                            )
                            DropdownMenuItem(
                                text = { Text("Preferencias") },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { menu = false; onSettings() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
        when {
            loaded && items.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
              FolderRow(
                  folders.ordered(), filter, onSelect = { filter = it }, onAdd = { addingFolder = true },
                  menuOpen = folderMenu, onMenu = { folderMenu = it },
                  onRename = { renamingFolder = it }, onRecolor = { recoloringFolder = it },
                  onDelete = { f ->
                      io("Carpeta eliminada", "No se puede eliminar") {
                          try {
                              repo.deleteFolder(f.id)
                          } catch (e: FolderNotEmptyException) {
                              error("La carpeta tiene ${e.count} ${if (e.count == 1) "cuaderno" else "cuadernos"}. Movelos o elimínalos primero.")
                          }
                      }
                  },
              )
              if (shown.isEmpty()) {
                  Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                      Text(
                          if (query.isNotBlank()) "Ningún cuaderno se llama así." else "No hay cuadernos en esta carpeta.",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                  }
              } else BoxWithConstraints(Modifier.fillMaxSize()) {
                // En pantallas bajas (teléfono horizontal) la tapa a 168 dp de ancho ocupa toda la
                // altura y el nombre queda fuera de la vista: se angosta la columna para que entren
                // la tapa y su nombre.
                val fitWidth = (maxHeight * 0.6f) * (Notebook.PAGE_WIDTH / Notebook.PAGE_HEIGHT)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minOf(168.dp, maxOf(112.dp, fitWidth))),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(shown, key = { it.id }) { nb ->
                        NotebookCard(
                            nb, colors,
                            onOpen = { onOpen(nb.id) },
                            onRename = { renaming = nb },
                            onExport = { exportId = nb.id; exportLauncher.launch("${nb.name}.trazo") },
                            onDelete = { deleting = nb },
                            onMove = { moving = nb },
                            onCover = { coverFor = nb },
                            onShift = if (sort == NotebookSort.MANUAL) { d -> shift(nb, d) } else null,
                        )
                    }
                }
              }
            }
        }
    }

    if (creating) {
        NewNotebookDialog(settings.paper) { name, paper ->
            creating = false
            // Un cuaderno nuevo queda en la carpeta que se está mirando.
            val folderId = filter.takeIf { it != FILTER_ALL && it != FILTER_NONE }
            if (name != null) onOpen(repo.createNotebook(name, paper, folderId).id)
        }
    }
    renaming?.let { nb ->
        NameDialog("Renombrar", nb.name) { name ->
            renaming = null
            if (name != null) { repo.renameNotebook(nb.id, name); refresh() }
        }
    }
    if (addingFolder) {
        NameDialog("Nueva carpeta", "") { name ->
            addingFolder = false
            if (name != null) io("Carpeta creada", "No se pudo crear") { filter = repo.addFolder(name).id }
        }
    }
    renamingFolder?.let { f ->
        NameDialog("Renombrar carpeta", f.name) { name ->
            renamingFolder = null
            if (name != null) io("Carpeta renombrada", "No se pudo renombrar") { repo.renameFolder(f.id, name) }
        }
    }
    recoloringFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { recoloringFolder = null },
            title = { Text("Color de «${f.name}»") },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FOLDER_COLORS.forEach { c ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(c.toInt()))
                                .border(if (c == f.colorArgb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable {
                                    recoloringFolder = null
                                    io("Color cambiado", "No se pudo cambiar") { repo.recolorFolder(f.id, c) }
                                },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { recoloringFolder = null }) { Text("Cerrar") } },
        )
    }
    moving?.let { nb ->
        val options = listOf<Folder?>(null) + folders.ordered()
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Mover «${nb.name}»") },
            text = {
                Column {
                    options.forEach { f ->
                        val selected = (nb.folderId?.takeIf { folders[it] != null }) == f?.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                moving = null
                                io("Cuaderno movido", "No se pudo mover") { repo.moveToFolder(nb.id, f?.id) }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            if (f != null) Box(Modifier.padding(start = 8.dp).size(12.dp).clip(CircleShape).background(Color(f.colorArgb.toInt())))
                            Text(f?.name ?: "Sin carpeta", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Cancelar") } },
        )
    }
    coverFor?.let { nb ->
        AlertDialog(
            onDismissRequest = { coverFor = null },
            title = { Text("Portada de «${nb.name}»") },
            text = {
                Column {
                    DropdownMenuItem(text = { Text("Usar primera hoja") }, leadingIcon = { Icon(TrazoIcons.Notebook, null) }, onClick = {
                        coverFor = null
                        io("Portada cambiada", "No se pudo cambiar") { repo.clearCover(nb.id) }
                    })
                    DropdownMenuItem(text = { Text("Elegir imagen…") }, leadingIcon = { Icon(TrazoIcons.Import, null) }, onClick = {
                        coverFor = null
                        coverId = nb.id
                        coverLauncher.launch(arrayOf("image/*"))
                    })
                    if (nb.coverFile != null) {
                        DropdownMenuItem(text = { Text("Quitar imagen") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                            coverFor = null
                            io("Imagen quitada", "No se pudo quitar") { repo.clearCover(nb.id) }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { coverFor = null }) { Text("Cancelar") } },
        )
    }
    deleting?.let { nb ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar «${nb.name}»?") },
            text = { Text("Se borran sus páginas y trazos de este dispositivo.") },
            confirmButton = { TextButton(onClick = { repo.deleteNotebook(nb.id); deleting = null; refresh() }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Tapa del cuaderno: la primera página como miniatura real (papel y tinta) y su menú. */
@Composable
private fun NotebookCard(
    nb: NotebookSummary,
    colors: PaperColors,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onCover: () -> Unit,
    /** Subir (-1) o bajar (+1) en el orden manual; null fuera de ese orden. */
    onShift: ((Int) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(Notebook.PAGE_WIDTH / Notebook.PAGE_HEIGHT).shadow(4.dp, RoundedCornerShape(10.dp)),
        ) {
            val cover = coverBitmap(nb.coverFile)
            if (cover != null) {
                Image(cover, "Portada de ${nb.name}", Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            } else {
                PageThumbnail(nb.firstPage.allInk, nb.paper, colors, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            // El nombre también abre el cuaderno, no solo la miniatura.
            Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpen).padding(start = 2.dp)) {
                Text(nb.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (nb.pageCount == 1) "1 pág." else "${nb.pageCount} págs.") + " · " + whenText(nb.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                // Compacto y pegado al título para que no empuje el texto ni quede debajo.
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "Opciones de ${nb.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                    DropdownMenuItem(text = { Text("Renombrar") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Mover a carpeta…") }, leadingIcon = { Icon(TrazoIcons.Notebook, null) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Portada…") }, leadingIcon = { Icon(TrazoIcons.Import, null) }, onClick = { menu = false; onCover() })
                    if (onShift != null) {
                        DropdownMenuItem(text = { Text("Subir") }, onClick = { menu = false; onShift(-1) })
                        DropdownMenuItem(text = { Text("Bajar") }, onClick = { menu = false; onShift(1) })
                    }
                    DropdownMenuItem(text = { Text("Exportar…") }, leadingIcon = { Icon(TrazoIcons.Export, null) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Eliminar…") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** La imagen de portada, leída fuera del hilo principal; null mientras carga o si no hay. */
@Composable
private fun coverBitmap(file: File?): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = file?.let { f ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bmp
}

/** Fila de carpetas: Todos, Sin carpeta, cada carpeta y «+ Carpeta»; la elegida tiene su menú. */
@Composable
private fun FolderRow(
    folders: List<Folder>,
    filter: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onRename: (Folder) -> Unit,
    onRecolor: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    val current = folders.firstOrNull { it.id == filter }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == FILTER_ALL, onClick = { onSelect(FILTER_ALL) }, label = { Text("Todos") })
            FilterChip(selected = filter == FILTER_NONE, onClick = { onSelect(FILTER_NONE) }, label = { Text("Sin carpeta") })
            folders.forEach { f ->
                FilterChip(
                    selected = filter == f.id,
                    onClick = { onSelect(f.id) },
                    label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(f.colorArgb.toInt()))) },
                )
            }
            FilterChip(selected = false, onClick = onAdd, label = { Text("Carpeta") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
        }
        if (current != null) {
            Box(Modifier.padding(end = 8.dp)) {
                IconButton(onClick = { onMenu(true) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Opciones de la carpeta ${current.name}") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }, shape = RoundedCornerShape(16.dp)) {
                    DropdownMenuItem(text = { Text("Renombrar") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { onMenu(false); onRename(current) })
                    DropdownMenuItem(text = { Text("Color") }, onClick = { onMenu(false); onRecolor(current) })
                    DropdownMenuItem(text = { Text("Eliminar") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { onMenu(false); onDelete(current) })
                }
            }
        }
    }
}

/** "ahora", "hace 5 min", "ayer"… en castellano, como el resto de la interfaz. */
private fun whenText(time: Long): String {
    val ago = System.currentTimeMillis() - time
    return when {
        ago < DateUtils.MINUTE_IN_MILLIS -> "ahora"
        ago < DateUtils.HOUR_IN_MILLIS -> "hace ${ago / DateUtils.MINUTE_IN_MILLIS} min"
        ago < DateUtils.DAY_IN_MILLIS -> "hace ${ago / DateUtils.HOUR_IN_MILLIS} h"
        ago < 2 * DateUtils.DAY_IN_MILLIS -> "ayer"
        ago < DateUtils.WEEK_IN_MILLIS -> "hace ${ago / DateUtils.DAY_IN_MILLIS} días"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("es")).format(java.util.Date(time))
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(TrazoIcons.Notebook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            "Creá un cuaderno y escribí con el lápiz.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Dibujá una integral y sostené la punta quieta 1 s: se reemplaza por tu propia tinta.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Nombre y papel de un cuaderno nuevo. */
@Composable
private fun NewNotebookDialog(defaultPaper: Paper, onResult: (String?, Paper) -> Unit) {
    var name by remember { mutableStateOf("Cálculo") }
    var paper by remember { mutableStateOf(defaultPaper) }
    AlertDialog(
        onDismissRequest = { onResult(null, paper) },
        title = { Text("Nuevo cuaderno") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") })
                SectionLabel("Papel")
                ChoiceRow(Paper.entries, paper, ::paperName, { paper = it })
            }
        },
        confirmButton = { TextButton(onClick = { onResult(name.trim(), paper) }, enabled = name.isNotBlank()) { Text("Crear") } },
        dismissButton = { TextButton(onClick = { onResult(null, paper) }) { Text("Cancelar") } },
    )
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
