package io.trazo.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.trazo.app.ink.Viewport
import io.trazo.core.MathNotes
import kotlinx.coroutines.delay

/**
 * Burbuja bajo un resultado tocado (como en Samsung Notes): la cuenta completa, su valor aproximado si
 * es una fracción y de dónde sale cada variable. Se cierra sola, al editar la hoja o con «Cerrar».
 */
@Composable
fun MathBubble(e: MathNotes.Explanation, viewport: Viewport, onDismiss: () -> Unit) {
    LaunchedEffect(e) { delay(BUBBLE_MS); onDismiss() }
    val clipboard = LocalClipboardManager.current
    val scale = viewport.scale.takeIf { it > 0f } ?: 1f
    val left = e.bounds.left * scale + viewport.tx
    val below = e.bounds.bottom * scale + viewport.ty
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Debajo del resultado, sin salirse de la vista (si no entra abajo, va arriba).
                    val gap = 8.dp.roundToPx()
                    val x = left.toInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                    val top = e.bounds.top * scale + viewport.ty
                    val y = if (below + gap + p.height <= constraints.maxHeight) below.toInt() + gap
                    else (top.toInt() - gap - p.height).coerceAtLeast(0)
                    p.place(x, y)
                }
            }
            .widthIn(max = 360.dp),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            e.steps.forEachIndexed { i, step ->
                Text(
                    step,
                    style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (i == 0) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(end = 12.dp, bottom = 2.dp),
                )
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(e.steps.first())); onDismiss() }) {
                    Text("Copiar", color = MaterialTheme.colorScheme.inversePrimary)
                }
                TextButton(onClick = onDismiss) { Text("Cerrar", color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

private const val BUBBLE_MS = 8000L
