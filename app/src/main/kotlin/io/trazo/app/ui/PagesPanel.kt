package io.trazo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.trazo.app.ink.PaperColors
import io.trazo.core.Notebook
import kotlin.math.roundToInt

/**
 * Panel de páginas (como en Samsung Notes): miniaturas numeradas y «página/total». Tocar una página
 * la abre; mantenerla apretada y arrastrarla cambia el orden; su ⋮ duplica, agrega una hoja después
 * o la borra. Las miniaturas se dibujan de a una, solo las que están a la vista.
 */
@Composable
fun PagesPanel(
    vm: EditorViewModel,
    colors: PaperColors,
    onDelete: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = vm.pageCount
    val list = rememberLazyListState(initialFirstVisibleItemIndex = vm.pageIndex)
    LaunchedEffect(vm.pageIndex) { if (vm.pageIndex !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.animateScrollToItem(vm.pageIndex) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(1f) }
    val dragTo = dragFrom?.let { (it + (dragDy / itemHeight).roundToInt()).coerceIn(0, count - 1) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.width(200.dp).fillMaxHeight()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.pageIndex + 1}/$count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ToolButton(Icons.Filled.Add, "Nueva página al final", onClick = { vm.addPage() }, size = 36.dp)
                ToolButton(Icons.Filled.Close, "Cerrar páginas", onClick = onClose, size = 36.dp)
            }
            LazyColumn(
                state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(vm.notebook?.slots.orEmpty(), key = { _, s -> s.id }) { i, _ ->
                    val dragging = dragFrom == i
                    PageItem(
                        vm, i, colors,
                        current = i == vm.pageIndex,
                        dropHere = dragTo == i && dragFrom != null && !dragging,
                        modifier = Modifier
                            .onSizeChanged { itemHeight = it.height.toFloat() + 14f * 2.6f }
                            .graphicsLayer {
                                if (dragging) { translationY = dragDy; scaleX = 1.05f; scaleY = 1.05f; shadowElevation = 12f }
                            }
                            .pointerInput(i) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragFrom = i; dragDy = 0f },
                                    onDrag = { change, d -> change.consume(); dragDy += d.y },
                                    onDragEnd = {
                                        val to = dragTo
                                        if (to != null && to != i) vm.movePage(i, to)
                                        dragFrom = null; dragDy = 0f
                                    },
                                    onDragCancel = { dragFrom = null; dragDy = 0f },
                                )
                            },
                        onOpen = { vm.goToPage(i) },
                        onDelete = { onDelete(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageItem(
    vm: EditorViewModel,
    index: Int,
    colors: PaperColors,
    current: Boolean,
    dropHere: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val widthPx = with(LocalDensity.current) { 160.dp.roundToPx() }
    val key = vm.thumbKey(index)
    var thumb by remember { mutableStateOf<ImageBitmap?>(vm.cachedThumb(index)) }
    LaunchedEffect(key, colors) {
        // La página abierta se redibuja cuando dejás de escribir un momento.
        if (vm.cachedThumb(index) == null && index == vm.pageIndex) kotlinx.coroutines.delay(400)
        thumb = vm.thumbnail(index, widthPx, colors)
    }
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(Notebook.PAGE_WIDTH / Notebook.PAGE_HEIGHT)
                .shadow(3.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(androidx.compose.ui.graphics.Color(colors.sheet))
                .border(
                    if (current || dropHere) 3.dp else 0.5.dp,
                    if (dropHere) scheme.tertiary else if (current) scheme.primary else scheme.outlineVariant,
                    RoundedCornerShape(4.dp),
                )
                .clickable(onClick = onOpen),
        ) {
            thumb?.let { Image(it, contentDescription = "Página ${index + 1}", modifier = Modifier.fillMaxWidth().aspectRatio(Notebook.PAGE_WIDTH / Notebook.PAGE_HEIGHT)) }
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                Surface(shape = RoundedCornerShape(50), color = scheme.surfaceContainerHighest.copy(alpha = 0.85f)) {
                    ToolButton(Icons.Filled.MoreVert, "Opciones de la página ${index + 1}", onClick = { menu = true }, size = 30.dp)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Duplicar") },
                        leadingIcon = { Icon(TrazoIcons.Duplicate, null) },
                        onClick = { menu = false; vm.duplicatePage(index) },
                    )
                    DropdownMenuItem(
                        text = { Text("Nueva página después") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menu = false; vm.addPage(after = index) },
                    )
                    DropdownMenuItem(
                        text = { Text("Borrar página") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        enabled = vm.pageCount > 1,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = if (current) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
