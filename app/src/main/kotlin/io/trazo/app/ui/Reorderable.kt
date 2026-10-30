package io.trazo.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.min

/**
 * Reordena una LazyColumn con keys estables. [onMove] recibe índices de la LazyColumn y tiene
 * que actualizar la lista en el momento; al soltar no se vuelve a llamar. Si agregás una cabecera,
 * restá ese índice en el callback. Solamente las filas envueltas en [ReorderableItem] son destinos;
 * una fila fija separa dos grupos que no se pueden cruzar.
 *
 * Ejemplo mínimo (las funciones de core se importan donde se usa el componente):
 * ```kotlin
 * var filas by remember { mutableStateOf(listOf("Uno", "Dos", "Tres")) }
 * val lista = rememberLazyListState()
 * val orden = rememberReorderableState(lista) { desde, hasta ->
 *     filas = moveItem(filas, desde - 1, hasta - 1)
 * }
 * LazyColumn(state = lista) {
 *     item { Text("Mis filas") }
 *     items(filas, key = { it }) { fila ->
 *         ReorderableItem(orden, fila) { _ ->
 *             Row {
 *                 Text("↕", Modifier.dragHandle(orden, fila).padding(16.dp))
 *                 Text(fila)
 *             }
 *         }
 *     }
 * }
 * ```
 * El dueño de la lista conserva selección, contenido y persistencia por key, nunca por índice.
 */
@Composable
fun rememberReorderableState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderableState {
    val latestOnMove = rememberUpdatedState(onMove)
    val density = LocalDensity.current
    val state = remember(listState, density) {
        ReorderableState(
            listState,
            with(density) { 72.dp.toPx() },
            with(density) { 900.dp.toPx() },
        ) { from, to -> latestOnMove.value(from, to) }
    }
    LaunchedEffect(state, state.draggingKey) {
        if (state.draggingKey != null) state.dragLoop()
    }
    DisposableEffect(state) {
        onDispose { state.stop() }
    }
    return state
}

/** Estado de un arrastre; la lista y su persistencia quedan a cargo de quien lo usa. */
class ReorderableState internal constructor(
    private val listState: LazyListState,
    private val edgeSizePx: Float,
    private val maxScrollPxPerSecond: Float,
    private val onMove: (Int, Int) -> Unit,
) {
    var draggingKey: Any? by mutableStateOf(null)
        private set

    // Solo contiene filas compuestas (visibles, prefetched o fijadas), no toda la lista.
    internal class Row {
        var coordinates: LayoutCoordinates? = null
    }

    private val rows = mutableMapOf<Any, Row>()
    private var draggedTop by mutableFloatStateOf(0f)
    private var fingerY = 0f
    private var lastOffset = 0f
    private var pendingIndex: Int? = null

    fun isDragging(key: Any): Boolean = draggingKey == key

    internal fun register(key: Any, row: Row) {
        rows[key] = row
    }

    internal fun unregister(key: Any, row: Row) {
        if (rows[key] === row) {
            rows.remove(key)
            if (isDragging(key)) stop()
        }
    }

    private fun item(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    internal fun start(key: Any, handle: LayoutCoordinates?, position: Offset) {
        if (draggingKey != null) return
        val info = item(key) ?: return
        val row = rows[key]?.coordinates ?: return
        if (!row.isAttached || handle?.isAttached != true) return
        draggedTop = info.offset.toFloat()
        lastOffset = draggedTop
        // El handle puede estar adentro de una fila con padding y no tiene por qué ocuparla entera.
        val localY = row.localPositionOf(handle, position).y
        fingerY = draggedTop + if (listState.layoutInfo.reverseLayout) info.size - localY else localY
        pendingIndex = null
        draggingKey = key
    }

    internal fun drag(key: Any, deltaY: Float) {
        if (!isDragging(key)) return
        val delta = if (listState.layoutInfo.reverseLayout) -deltaY else deltaY
        draggedTop += delta
        fingerY += delta
        moveIfNeeded()
    }

    internal fun stop() {
        draggingKey = null
        pendingIndex = null
        draggedTop = 0f
    }

    internal fun translation(key: Any): Float {
        if (!isDragging(key)) return 0f
        val offset = draggedTop - (item(key)?.offset?.toFloat() ?: lastOffset)
        return if (listState.layoutInfo.reverseLayout) -offset else offset
    }

    private fun canAutoScroll(step: Int): Boolean {
        val key = draggingKey ?: return false
        val layout = listState.layoutInfo
        val current = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        val next = current.index + step
        if (next < 0 || next >= layout.totalItemsCount) return false
        val neighbor = layout.visibleItemsInfo.firstOrNull { it.index == next }
        // Se puede revelar la próxima fila; una cabecera visible, en cambio, frena el arrastre.
        return neighbor == null || rows.containsKey(neighbor.key)
    }

    private fun moveIfNeeded() {
        val key = draggingKey ?: return
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        lastOffset = current.offset.toFloat()
        if (pendingIndex == current.index) pendingIndex = null
        val modelIndex = pendingIndex ?: current.index
        val modelSlot = visible.binarySearchBy(modelIndex) { it.index }
        if (modelSlot < 0) return
        val modelItem = visible[modelSlot]
        val modelOffset = when {
            modelIndex > current.index -> modelItem.offset + modelItem.size - current.size
            modelIndex < current.index -> modelItem.offset
            else -> current.offset
        }
        val center = draggedTop + current.size / 2f
        val step = if (center > modelOffset + current.size / 2f) 1 else -1
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        val extent = current.size + layout.mainAxisItemSpacing
        var from = modelIndex
        // El modelo se actualiza antes que el layout. Proyectá ese único movimiento sobre los
        // offsets visibles: así otro evento en el mismo frame no repite ni pierde cruces.
        // El resto de las filas siempre conserva su orden relativo.
        var crossings = 0
        while (crossings < visible.size) {
            val originalIndex = when {
                step < 0 && from > current.index -> from
                step > 0 && from < current.index -> from
                else -> from + step
            }
            val slot = visible.binarySearchBy(originalIndex) { it.index }
            if (slot < 0) break
            val neighbor = visible[slot]
            if (!rows.containsKey(neighbor.key)) break
            val shift = when {
                from > current.index && neighbor.index > current.index && neighbor.index <= from -> -extent
                from < current.index && neighbor.index >= from && neighbor.index < current.index -> extent
                else -> 0
            }
            val neighborCenter = neighbor.offset + shift + neighbor.size / 2f
            val crossed = if (step > 0) center > neighborCenter else center < neighborCenter
            if (!crossed) break
            val to = from + step
            pendingIndex = if (to == current.index) null else to
            onMove(from, to)
            from = to
            crossings++
        }
        if (from == modelIndex) return
        // Evita que LazyColumn mantenga la key del primer ítem y desplace el viewport al moverlo.
        if (min(current.index, from) <= firstIndex && firstIndex <= maxOf(current.index, from)) {
            listState.requestScrollToItem(firstIndex, firstOffset)
        }
    }

    internal suspend fun dragLoop() {
        var previousFrame = withFrameNanos { it }
        while (currentCoroutineContext().isActive && draggingKey != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            moveIfNeeded()
            val layout = listState.layoutInfo
            val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
            val edge = min(edgeSizePx, viewport * 0.2f)
            if (edge > 0f && pendingIndex == null) {
                val top = layout.viewportStartOffset.toFloat()
                val bottom = layout.viewportEndOffset.toFloat()
                val speed = when {
                    fingerY < top + edge -> -((top + edge - fingerY) / edge).coerceIn(0f, 1f)
                    fingerY > bottom - edge -> ((fingerY - bottom + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f && canAutoScroll(if (speed > 0f) 1 else -1)) {
                    listState.scrollBy(speed * maxScrollPxPerSecond * seconds)
                    // draggedTop queda en coordenadas del viewport: el scroll mueve la base,
                    // y translation() compensa ese cambio sin mover la fila respecto del dedo.
                    moveIfNeeded()
                }
            }
        }
    }
}

/** Envuelve toda la fila; usá la misma [key] en items(..., key = ...) y en [dragHandle]. */
@Composable
fun LazyItemScope.ReorderableItem(
    state: ReorderableState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val dragging = state.isDragging(key)
    val row = remember(state, key) { ReorderableState.Row() }
    val pinnable = LocalPinnableContainer.current
    DisposableEffect(state, key, row) {
        state.register(key, row)
        onDispose { state.unregister(key, row) }
    }
    DisposableEffect(pinnable, dragging) {
        val pin = if (dragging) pinnable?.pin() else null
        onDispose { pin?.release() }
    }
    val placement = if (dragging) Modifier else Modifier.animateItem()
    Box(
        modifier
            .then(placement)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = state.translation(key)
                shadowElevation = if (dragging) 4.dp.toPx() else 0f
            }
            .onGloballyPositioned { row.coordinates = it },
    ) {
        content(dragging)
    }
}

/** Mantené apretado este handle y arrastrá; el resto de la fila conserva sus propios gestos. */
fun Modifier.dragHandle(state: ReorderableState, key: Any): Modifier = composed {
    val handle = remember(state, key) { ReorderableState.Row() }
    this
        .onGloballyPositioned { handle.coordinates = it }
        .pointerInput(state, key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.start(key, handle.coordinates, it) },
                onDrag = { change, amount ->
                    change.consume()
                    state.drag(key, amount.y)
                },
                onDragEnd = { if (state.isDragging(key)) state.stop() },
                onDragCancel = { if (state.isDragging(key)) state.stop() },
            )
        }
}
