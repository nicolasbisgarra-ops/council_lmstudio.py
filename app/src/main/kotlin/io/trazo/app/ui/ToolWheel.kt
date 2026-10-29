package io.trazo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.trazo.app.data.TrazoSettings
import io.trazo.app.data.WheelEntry
import io.trazo.app.ink.InkCanvasView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class WheelItem(val icon: ImageVector, val label: String, val selected: Boolean, val enabled: Boolean, val action: () -> Unit)

/**
 * Ruleta que abre un clic del botón del lápiz en el aire, centrada donde estaba la punta: las
 * herramientas, deshacer y rehacer en el aro; los colores rápidos al centro, con el arcoíris en el medio.
 * Tocar fuera la cierra. Como tapa el lienzo, cuenta ella misma los clics del botón del lápiz en el
 * aire ([onPenButtonClicks]): así un clic la cierra y 2 o 3 hacen lo mismo que sobre la hoja.
 */
@Composable
fun ToolWheel(
    vm: EditorViewModel,
    settings: TrazoSettings,
    center: Offset,
    onColor: (Int) -> Unit,
    onCustomColor: () -> Unit,
    onDismiss: () -> Unit,
    onPenButtonClicks: (Int) -> Unit,
) {
    val tool = vm.editorTool
    val items = settings.wheelItems.map { entry ->
        when (entry) {
            WheelEntry.UNDO -> WheelItem(TrazoIcons.Undo, "Deshacer", false, vm.canUndo) { vm.undo() }
            WheelEntry.REDO -> WheelItem(TrazoIcons.Redo, "Rehacer", false, vm.canRedo) { vm.redo() }
            else -> {
                val target = requireNotNull(entry.tool)
                WheelItem(target.preferenceIcon(), target.preferenceLabel(), tool == target, true) { vm.selectTool(target) }
            }
        }
    }
    val highlighter = tool == EditorTool.HIGHLIGHTER
    val colors = if (highlighter) HighlighterPalette.take(settings.quickColors.size) else settings.quickColors
    val current = if (highlighter) settings.highlighterColor else settings.penColor

    val density = LocalDensity.current
    val clicks by rememberUpdatedState(onPenButtonClicks)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) {
                // Mismo criterio que el lienzo: cuenta al soltar el botón apretado con la punta en el aire.
                var down = false
                var inAir = false
                var count = 0
                var pending: Job? = null
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pen = event.changes.firstOrNull { it.type == PointerType.Stylus } ?: continue
                            val pressed = event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed
                            if (pen.pressed) {
                                // Apoyado: el botón es la goma, no un clic; la cuenta se corta.
                                down = pressed; inAir = false; count = 0; pending?.cancel()
                            } else if (event.type == PointerEventType.Exit) {
                                // Salió del alcance: un botón soltado fuera no es un clic.
                                down = false; inAir = false
                            } else if (pressed && !down) {
                                down = true; inAir = true
                                pending?.cancel()
                            } else if (!pressed && down) {
                                down = false
                                if (inAir) {
                                    count++
                                    pending?.cancel()
                                    pending = launch {
                                        delay(InkCanvasView.PEN_CLICK_WAIT_MS)
                                        val n = count
                                        count = 0
                                        clicks(minOf(n, 3))
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        // En ventanas chicas (pantalla dividida, ventana flotante) la ruleta se achica para entrar entera.
        val scale = ((min(maxWidth, maxHeight) / 2 - 8.dp) / 120.dp).coerceIn(0.6f, 1f)
        val ring = 92.dp * scale
        val button = 44.dp * scale
        val inner = 40.dp * scale
        val dot = 20.dp * scale
        val half = ring + button / 2 + 6.dp * scale
        val halfPx = with(density) { half.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Que entre entera en la vista aunque el clic haya sido cerca de un borde.
        val cx = center.x.coerceIn(halfPx, max(halfPx, wPx - halfPx))
        val cy = center.y.coerceIn(halfPx, max(halfPx, hPx - halfPx))
        Box(
            Modifier
                .absoluteOffset { IntOffset((cx - halfPx).roundToInt(), (cy - halfPx).roundToInt()) }
                .size(half * 2)
                .shadow(8.dp, CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            items.forEachIndexed { i, item ->
                val a = -PI / 2 + i * 2 * PI / items.size
                Box(Modifier.align(Alignment.Center).absoluteOffset(x = ring * cos(a).toFloat(), y = ring * sin(a).toFloat())) {
                    ToolButton(
                        item.icon, item.label,
                        onClick = { item.action(); onDismiss() },
                        selected = item.selected, enabled = item.enabled, size = button,
                    )
                }
            }
            colors.forEachIndexed { i, c ->
                val a = -PI / 2 + i * 2 * PI / colors.size
                Box(Modifier.align(Alignment.Center).absoluteOffset(x = inner * cos(a).toFloat(), y = inner * sin(a).toFloat())) {
                    ColorDot(c, selected = c == current, onClick = { onColor(c); onDismiss() }, size = dot)
                }
            }
            Box(Modifier.align(Alignment.Center)) {
                RainbowDot(onClick = { onDismiss(); onCustomColor() }, size = dot, description = "Otro color")
            }
        }
    }
}
