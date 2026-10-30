package io.trazo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.trazo.ai.MarkKind
import io.trazo.core.AiNote
import io.trazo.core.AiNotes
import kotlinx.coroutines.delay

/**
 * Lo que flota sobre la hoja en el Modo IA: arriba, el progreso de la revisión; a la derecha, el ojo que
 * oculta las marcas y la leyenda de colores.
 */
@Composable
fun AiOverlay(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val progress = vm.aiProgress
    val hasNotes = vm.aiNotes.isNotEmpty()
    if (progress == null && !hasNotes) return
    var legend by remember { mutableStateOf(false) }
    Column(modifier.padding(8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        progress?.let { AiProgressCard(vm, it) }
        if (hasNotes) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::toggleAiVisible) {
                        Icon(
                            if (vm.aiVisible) TrazoIcons.Eye else TrazoIcons.EyeOff,
                            contentDescription = if (vm.aiVisible) "Ocultar las marcas de la IA" else "Mostrar las marcas de la IA",
                        )
                    }
                    TextButton(onClick = { legend = !legend }) { Text("Colores") }
                }
            }
            if (legend) AiLegend()
        }
    }
}

@Composable
private fun AiProgressCard(vm: EditorViewModel, p: EditorViewModel.AiProgress) {
    if (!p.running) LaunchedEffect(p) { delay(if (p.error) 9000 else 6000); vm.dismissAiProgress() }
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (p.error) scheme.errorContainer else scheme.surfaceContainerHighest,
        contentColor = if (p.error) scheme.onErrorContainer else scheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AiStarsIcon, null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
                Text(
                    when {
                        p.running && p.marks == 0 -> "Revisando ${p.lines} renglones…"
                        p.running -> "Revisando… ${if (p.marks == 1) "1 marca" else "${p.marks} marcas"}"
                        else -> p.message.orEmpty()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                )
                TextButton(onClick = { if (p.running) vm.cancelAiReview() else vm.dismissAiProgress() }) {
                    Text(if (p.running) "Cancelar" else "Cerrar")
                }
            }
            if (p.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun AiLegend() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in MarkKind.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(k.color)))
                    Text(k.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text("Línea punteada: el motor de Trazo no coincide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * La burbuja de una marca tocada (16, 17): el motivo corto, «Ver más», la corrección solo si se pide,
 * discutirla con el modelo, convertirla en tinta o quitarla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiNoteBubble(vm: EditorViewModel, note: AiNote) {
    val notes = vm.controller.page.ai?.notes.orEmpty()
    val placed = remember(note, notes) { AiNotes.placed(notes, note) }
    val viewport = vm.viewport
    vm.viewportTick
    val scale = viewport.scale.takeIf { it > 0f } ?: 1f
    val anchor = placed.label
    var more by remember(note.id) { mutableStateOf(false) }
    var fix by remember(note.id) { mutableStateOf(false) }
    var discussing by remember(note.id) { mutableStateOf(false) }
    var argument by remember(note.id) { mutableStateOf("") }
    val kind = runCatching { MarkKind.valueOf(note.kind) }.getOrDefault(MarkKind.CALCULATION)
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        modifier = Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val gap = 8.dp.roundToPx()
                    val left = anchor.left * scale + viewport.tx
                    val below = anchor.bottom * scale + viewport.ty
                    val top = anchor.top * scale + viewport.ty
                    val x = left.toInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                    val y = if (below + gap + p.height <= constraints.maxHeight) below.toInt() + gap
                    else (top.toInt() - gap - p.height).coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0))
                    p.place(x, y)
                }
            }
            .widthIn(max = 380.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(kind.color)))
                Text(
                    "${kind.label} · renglón ${note.line}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(kind.color),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(note.short, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            when {
                note.fromEngine -> NoteHint("La marcó el motor de Trazo, no el modelo.")
                note.disputed -> NoteHint("Dudosa: según el motor de Trazo esta cuenta está bien.")
            }
            if (more && note.detail.isNotBlank()) Text(note.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
            if (fix && note.fix.isNotBlank()) {
                Surface(shape = RoundedCornerShape(10.dp), color = scheme.secondaryContainer, modifier = Modifier.padding(top = 6.dp, end = 8.dp)) {
                    Text(note.fix, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
            }
            vm.aiAnswer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.primary, modifier = Modifier.padding(top = 6.dp, end = 8.dp)) }
            if (discussing && !note.fromEngine) {
                OutlinedTextField(
                    argument, { argument = it },
                    label = { Text("¿Por qué no está de acuerdo?") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
                    minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.aiDiscussing) CircularProgressIndicator(Modifier.size(18.dp).padding(start = 4.dp), strokeWidth = 2.dp)
                    TextButton(enabled = !vm.aiDiscussing && argument.isNotBlank(), onClick = { vm.discussAiNote(note, argument); argument = "" }) {
                        Text("Enviar al modelo")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (!more && note.detail.isNotBlank()) TextButton(onClick = { more = true }) { Text("Ver más") }
                if (!fix && note.fix.isNotBlank()) TextButton(onClick = { fix = true }) { Text("Mostrar corrección") }
                if (!discussing && !note.fromEngine) TextButton(onClick = { discussing = true }) { Text("Discutir") }
                TextButton(onClick = { vm.aiNoteToInk(note) }) { Text("Convertir en tinta") }
                TextButton(onClick = { vm.removeAiNote(note) }) { Text("Quitar") }
                TextButton(onClick = { vm.aiNote = null }) { Text("Cerrar") }
            }
        }
    }
}

@Composable
private fun NoteHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
}
