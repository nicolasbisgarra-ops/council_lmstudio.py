package io.trazo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.trazo.app.data.ToolbarButtonSize
import io.trazo.app.data.TrazoSettings
import io.trazo.app.data.WheelEntry
import io.trazo.core.moveItem
import io.trazo.core.positionLabel
import io.trazo.core.settings.ToolPreferences
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarPreferences(s: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit) {
    Text("Tamaño de los botones", style = MaterialTheme.typography.bodyMedium)
    ChoiceRow(ToolbarButtonSize.entries, s.toolbarButtonSize, { "${it.label} (${it.dp} dp)" }, { size -> update { it.copy(toolbarButtonSize = size) } })
    Text("Vista previa de la barra", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        s.toolbarTools.forEach { tool ->
            PreviewButton(tool.preferenceIcon(), tool.preferenceLabel(), s.toolbarButtonSize.dp.dp, tool == EditorTool.PEN)
        }
    }
    Text("Elegí qué mostrar. Mantené apretado el ícono de líneas y arrastrá, o usá las flechas. La pluma queda siempre visible.", style = MaterialTheme.typography.bodySmall)
    OrderedChoices(
        options = EditorTool.entries, selected = s.toolbarTools, key = { it.name }, label = { it.preferenceLabel() },
        canToggle = { it != EditorTool.PEN },
        onChange = { tools -> update { it.copy(toolbarTools = tools) } },
    )
}

@Composable
internal fun WheelPreferences(s: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit) {
    Text("Vista previa de la ruleta", style = MaterialTheme.typography.labelLarge)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val scale = (maxWidth / 240.dp).coerceAtMost(1f)
        val ring = 92.dp * scale
        Box(Modifier.size(240.dp * scale).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            s.wheelItems.forEachIndexed { index, entry ->
                val angle = -PI / 2 + index * 2 * PI / s.wheelItems.size
                Box(Modifier.align(Alignment.Center).absoluteOffset(x = ring * cos(angle).toFloat(), y = ring * sin(angle).toFloat())) {
                    PreviewButton(entry.preferenceIcon(), entry.preferenceLabel(), 44.dp * scale, entry == WheelEntry.PEN)
                }
            }
            s.quickColors.forEachIndexed { index, color ->
                val angle = -PI / 2 + index * 2 * PI / s.quickColors.size
                Box(
                    Modifier.align(Alignment.Center)
                        .absoluteOffset(x = 40.dp * scale * cos(angle).toFloat(), y = 40.dp * scale * sin(angle).toFloat())
                        .size(20.dp * scale).clip(CircleShape).background(Color(color)),
                )
            }
            Icon(TrazoIcons.Palette, null, Modifier.align(Alignment.Center).size(20.dp * scale))
        }
    }
    Text(
        "${s.wheelItems.size} entradas elegidas. Dejá al menos ${ToolPreferences.MIN_WHEEL_ITEMS}; el límite es ${ToolPreferences.MAX_WHEEL_ITEMS}.",
        style = MaterialTheme.typography.bodySmall,
    )
    OrderedChoices(
        options = WheelEntry.entries, selected = s.wheelItems, key = { it.name }, label = { it.preferenceLabel() },
        canToggle = { entry ->
            if (entry in s.wheelItems) s.wheelItems.size > ToolPreferences.MIN_WHEEL_ITEMS
            else s.wheelItems.size < ToolPreferences.MAX_WHEEL_ITEMS
        },
        onChange = { items -> update { it.copy(wheelItems = items) } },
    )
}

/** Las entradas visibles van primero, en su orden real; las ocultas quedan al final para recuperarlas. */
@Composable
private fun <T> OrderedChoices(
    options: List<T>,
    selected: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    canToggle: (T) -> Boolean,
    onChange: (List<T>) -> Unit,
) {
    // Las preferencias tienen como máximo diez entradas: guardar por cruce es barato.
    var ordered by remember(selected) { mutableStateOf(selected) }
    val list = rememberLazyListState()
    fun change(next: List<T>) { ordered = next; onChange(next) }
    val reorder = rememberReorderableState(list) { from, to ->
        if (from in ordered.indices && to in ordered.indices) change(moveItem(ordered, from, to))
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        itemsIndexed(ordered, key = { _, option -> key(option) }) { index, option ->
            ReorderableItem(reorder, key(option)) { _ ->
                val name = label(option)
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = true, enabled = reorder.draggingKey == null && canToggle(option),
                        onCheckedChange = { change(ordered - option) },
                        modifier = Modifier.semantics { contentDescription = "Mostrar $name" },
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(positionLabel(index, ordered.size), style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(Icons.Filled.Menu, "Mover $name", Modifier.dragHandle(reorder, key(option)).padding(12.dp).size(24.dp))
                    IconButton(onClick = { change(moveItem(ordered, index, index - 1)) }, enabled = reorder.draggingKey == null && index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Subir $name")
                    }
                    IconButton(onClick = { change(moveItem(ordered, index, index + 1)) }, enabled = reorder.draggingKey == null && index < ordered.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Bajar $name")
                    }
                }
            }
        }
        item(key = "hidden-header") {
            Text("Ocultas (activá una para agregarla al final)", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
        }
        items(options.filter { it !in ordered }, key = key) { option ->
            val name = label(option)
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = false, enabled = reorder.draggingKey == null && canToggle(option),
                    onCheckedChange = { change(ordered + option) },
                    modifier = Modifier.semantics { contentDescription = "Mostrar $name" },
                )
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PreviewButton(icon: ImageVector, label: String, size: Dp, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (selected) scheme.primary else Color.Transparent)
            .semantics { contentDescription = "Vista previa: $label" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) scheme.onPrimary else scheme.onSurface)
    }
}
