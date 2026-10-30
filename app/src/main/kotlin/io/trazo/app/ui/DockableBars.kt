package io.trazo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.trazo.app.data.BarDock
import kotlin.math.roundToInt

/**
 * Arrastre de una barra dentro de su contenedor (la pantalla o la hoja). Mientras se arrastra se ve
 * el cuadrado compacto donde quedaría; al soltar cerca de un borde se pega a ese borde y si no, queda
 * suelta ahí. [onPlace] recibe el borde y la posición suelta como fracción (0..1) del lugar libre.
 */
@Stable
class BarMover(private val onPlace: (BarDock, Pair<Float, Float>) -> Unit) {
    var container by mutableStateOf(IntSize.Zero)
    var containerOrigin = Offset.Zero
    /** Tamaño del cuadrado compacto (se mide al mostrarse; hasta entonces, uno aproximado). */
    var square by mutableStateOf(IntSize(480, 640))
    var dragging by mutableStateOf(false)
        private set
    /** Esquina del cuadrado mientras se arrastra (px del contenedor). */
    var pos by mutableStateOf(Offset.Zero)
        private set
    private var pointer = Offset.Zero
    var edgePx = 150f

    /** Borde al que se pegaría si se suelta ahora. */
    val target: BarDock
        get() = when {
            pointer.y < edgePx -> BarDock.TOP
            pointer.y > container.height - edgePx -> BarDock.BOTTOM
            pointer.x < edgePx -> BarDock.LEFT
            pointer.x > container.width - edgePx -> BarDock.RIGHT
            else -> BarDock.FREE
        }

    /** [rootPointer]: dónde se apoyó el dedo (raíz); [grab]: qué punto del cuadrado queda bajo el dedo. */
    fun start(rootPointer: Offset, grab: Offset) {
        pointer = rootPointer - containerOrigin
        pos = clamp(pointer - grab)
        dragging = true
    }

    fun drag(delta: Offset) {
        pointer += delta
        pos = clamp(pos + delta)
    }

    fun end() {
        if (!dragging) return
        dragging = false
        onPlace(target, fraction(pos))
    }

    /** Dónde va el cuadrado suelto en la fracción [f]. */
    fun placeOf(f: Pair<Float, Float>): Offset = Offset(
        f.first.coerceIn(0f, 1f) * (container.width - square.width).coerceAtLeast(0),
        f.second.coerceIn(0f, 1f) * (container.height - square.height).coerceAtLeast(0),
    )

    private fun fraction(p: Offset): Pair<Float, Float> {
        val w = (container.width - square.width).coerceAtLeast(1)
        val h = (container.height - square.height).coerceAtLeast(1)
        return (p.x / w).coerceIn(0f, 1f) to (p.y / h).coerceIn(0f, 1f)
    }

    private fun clamp(p: Offset) = Offset(
        p.x.coerceIn(0f, (container.width - square.width).coerceAtLeast(0).toFloat()),
        p.y.coerceIn(0f, (container.height - square.height).coerceAtLeast(0).toFloat()),
    )
}

@Composable
fun rememberBarMover(onPlace: (BarDock, Pair<Float, Float>) -> Unit): BarMover {
    val mover = remember { BarMover(onPlace) }
    mover.edgePx = with(LocalDensity.current) { 56.dp.toPx() }
    return mover
}

/** El contenedor dentro del que se mueve la barra. */
fun Modifier.barContainer(mover: BarMover): Modifier = onGloballyPositioned {
    mover.containerOrigin = it.positionInRoot()
    mover.container = it.size
}

/** Agarradera de seis puntos: arrastrarla suelta la barra o la lleva a otro borde. */
@Composable
fun DragHandle(mover: BarMover, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val grab = with(LocalDensity.current) { Offset(24.dp.toPx(), 16.dp.toPx()) }
    Box(
        modifier
            .size(40.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(mover) {
                detectDragGestures(
                    onDragStart = { o -> mover.start(origin + o, grab) },
                    onDrag = { change, d -> change.consume(); mover.drag(d) },
                    onDragEnd = { mover.end() },
                    onDragCancel = { mover.end() },
                )
            }
            .semantics { contentDescription = "Mover la barra: soltala en un borde para pegarla" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(TrazoIcons.Drag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vista previa del arrastre: el borde que se ilumina y el cuadrado donde quedaría suelta. */
@Composable
fun BoxScope.DockPreview(mover: BarMover, showSquare: Boolean) {
    if (!mover.dragging) return
    val accent = MaterialTheme.colorScheme.primary
    val target = mover.target
    val strip = Modifier.background(accent.copy(alpha = 0.35f))
    when (target) {
        BarDock.TOP -> Box(strip.fillMaxWidth().height(8.dp).align(Alignment.TopCenter))
        BarDock.BOTTOM -> Box(strip.fillMaxWidth().height(8.dp).align(Alignment.BottomCenter))
        BarDock.LEFT -> Box(strip.fillMaxHeight().width(8.dp).align(Alignment.CenterStart))
        BarDock.RIGHT -> Box(strip.fillMaxHeight().width(8.dp).align(Alignment.CenterEnd))
        BarDock.FREE -> Unit
    }
    if (showSquare) {
        val density = LocalDensity.current
        Box(
            Modifier
                .offset { IntOffset(mover.pos.x.roundToInt(), mover.pos.y.roundToInt()) }
                .size(with(density) { mover.square.width.toDp() }, with(density) { mover.square.height.toDp() })
                .background(accent.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .border(2.dp, accent, RoundedCornerShape(20.dp)),
        )
    }
}

/** Ubica el cuadrado suelto: donde se lo arrastra o donde quedó. */
fun Modifier.freePlacement(mover: BarMover, free: Pair<Float, Float>): Modifier =
    offset {
        val p = if (mover.dragging) mover.pos else mover.placeOf(free)
        IntOffset(p.x.roundToInt(), p.y.roundToInt())
    }.onGloballyPositioned { mover.square = it.size }
