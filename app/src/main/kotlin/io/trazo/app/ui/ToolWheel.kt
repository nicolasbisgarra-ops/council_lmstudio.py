package io.trazo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.trazo.app.data.TrazoSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class WheelItem(val icon: ImageVector, val label: String, val selected: Boolean, val enabled: Boolean, val action: () -> Unit)

/**
 * Ruleta que abre un clic del botón del lápiz en el aire, centrada donde estaba la punta: las
 * herramientas, deshacer y rehacer en el aro; los colores rápidos al centro, con el arcoíris en el medio.
 * Tocar fuera la cierra.
 */
@Composable
fun ToolWheel(
    vm: EditorViewModel,
    settings: TrazoSettings,
    center: Offset,
    onColor: (Int) -> Unit,
    onCustomColor: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tool = vm.editorTool
    val items = listOf(
        WheelItem(TrazoIcons.Pen, "Pluma", tool == EditorTool.PEN, true) { vm.selectTool(EditorTool.PEN) },
        WheelItem(TrazoIcons.Highlighter, "Resaltador", tool == EditorTool.HIGHLIGHTER, true) { vm.selectTool(EditorTool.HIGHLIGHTER) },
        WheelItem(TrazoIcons.Eraser, "Goma", tool == EditorTool.ERASER, true) { vm.selectTool(EditorTool.ERASER) },
        WheelItem(TrazoIcons.Lasso, "Lazo", tool == EditorTool.LASSO, true) { vm.selectTool(EditorTool.LASSO) },
        WheelItem(TrazoIcons.Shapes, "Formas", tool == EditorTool.SHAPE, true) { vm.selectTool(EditorTool.SHAPE) },
        WheelItem(TrazoIcons.Pan, "Mano: desplazar la hoja", tool == EditorTool.PAN, true) { vm.selectTool(EditorTool.PAN) },
        WheelItem(TrazoIcons.Undo, "Deshacer", false, vm.canUndo) { vm.undo() },
        WheelItem(TrazoIcons.Redo, "Rehacer", false, vm.canRedo) { vm.redo() },
    )
    val highlighter = tool == EditorTool.HIGHLIGHTER
    val colors = if (highlighter) HighlighterPalette.take(settings.quickColors.size) else settings.quickColors
    val current = if (highlighter) settings.highlighterColor else settings.penColor

    val ring = 92.dp
    val button = 44.dp
    val inner = 40.dp
    val half = ring + button / 2 + 6.dp
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        val halfPx = with(density) { half.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Que entre entera en la vista aunque el clic haya sido cerca de un borde.
        val cx = center.x.coerceIn(halfPx, max(halfPx, wPx - halfPx))
        val cy = center.y.coerceIn(halfPx, max(halfPx, hPx - halfPx))
        Box(
            Modifier
                .offset { IntOffset((cx - halfPx).roundToInt(), (cy - halfPx).roundToInt()) }
                .size(half * 2)
                .shadow(8.dp, CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            items.forEachIndexed { i, item ->
                val a = -PI / 2 + i * 2 * PI / items.size
                Box(Modifier.align(Alignment.Center).offset(x = ring * cos(a).toFloat(), y = ring * sin(a).toFloat())) {
                    ToolButton(
                        item.icon, item.label,
                        onClick = { item.action(); onDismiss() },
                        selected = item.selected, enabled = item.enabled, size = button,
                    )
                }
            }
            colors.forEachIndexed { i, c ->
                val a = -PI / 2 + i * 2 * PI / colors.size
                Box(Modifier.align(Alignment.Center).offset(x = inner * cos(a).toFloat(), y = inner * sin(a).toFloat())) {
                    ColorDot(c, selected = c == current, onClick = { onColor(c); onDismiss() }, size = 20.dp)
                }
            }
            Box(Modifier.align(Alignment.Center)) {
                RainbowDot(onClick = { onDismiss(); onCustomColor() }, size = 20.dp, description = "Otro color")
            }
        }
    }
}
