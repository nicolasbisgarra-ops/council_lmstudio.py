package io.trazo.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.trazo.app.ink.Viewport
import io.trazo.core.InkEvent
import io.trazo.core.InkPattern
import kotlinx.coroutines.delay

/**
 * Dos patrones parecidos, justo encima del lápiz: uno a la izquierda y otro a la derecha. Con el lápiz en
 * el aire sobre uno, se va llenando; quieto [DWELL_MS] se marca con un tilde verde y se reemplaza. Tocar
 * también elige. Seguir escribiendo o la × la cierran y queda la escritura.
 */
@Composable
fun AmbiguityBubble(
    amb: InkEvent.Ambiguous,
    pen: Pair<Float, Float>,
    viewport: Viewport,
    onChoose: (InkPattern?) -> Unit,
) {
    val scale = viewport.scale.takeIf { it > 0f } ?: 1f
    val penX = pen.first * scale + viewport.tx
    val penY = pen.second * scale + viewport.ty
    var hovered by remember(amb) { mutableStateOf<InkPattern?>(null) }
    var picked by remember(amb) { mutableStateOf<InkPattern?>(null) }
    val progress = remember(amb) { Animatable(0f) }
    LaunchedEffect(hovered, picked) {
        if (picked != null) return@LaunchedEffect
        progress.snapTo(0f)
        val h = hovered ?: return@LaunchedEffect
        progress.animateTo(1f, tween(DWELL_MS, easing = LinearEasing))
        picked = h
    }
    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        delay(CHECK_MS) // que se vea el tilde antes de reemplazar
        onChoose(p)
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Centrada sobre la punta, con aire para que el lápiz no caiga encima al levantarlo.
                val gap = 28.dp.roundToPx()
                val x = (penX - p.width / 2f).toInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                val above = penY.toInt() - gap - p.height
                val y = if (above >= 0) above else (penY.toInt() + gap).coerceAtMost((constraints.maxHeight - p.height).coerceAtLeast(0))
                p.place(x, y)
            }
        },
    ) {
        Box {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                amb.options.take(2).forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.width(1.dp).height(72.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Option(
                        m.pattern,
                        progress = if (hovered == m.pattern) progress.value else 0f,
                        checked = picked == m.pattern,
                        onHover = { inside -> if (picked == null) hovered = if (inside) m.pattern else if (hovered == m.pattern) null else hovered },
                        onClick = { if (picked == null) picked = m.pattern },
                    )
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(CircleShape)
                    .clickable { onChoose(null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Conservar mi escritura", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Option(p: InkPattern, progress: Float, checked: Boolean, onHover: (Boolean) -> Unit, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val green = Color(0xFF2E7D32)
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (checked) green else if (progress > 0f) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .drawBehind {
                // El relleno avanza mientras el lápiz se queda quieto encima.
                if (progress > 0f) drawRect(accent.copy(alpha = 0.18f), size = Size(size.width * progress, size.height))
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent(PointerEventPass.Initial).type) {
                            PointerEventType.Enter -> onHover(true)
                            PointerEventType.Exit -> onHover(false)
                        }
                    }
                }
            }
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            InkPreview(p.outputInk, Modifier.fillMaxWidth().height(64.dp))
            Text(
                p.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        if (checked) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(green),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Elegido", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Cuánto hay que dejar el lápiz quieto sobre un patrón para elegirlo. */
private const val DWELL_MS = 500

/** Cuánto se ve el tilde verde antes de reemplazar. */
private const val CHECK_MS = 300L
