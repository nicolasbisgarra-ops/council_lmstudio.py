package io.trazo.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt

/** Colores propios de cada acción del menú de la selección, para reconocerlas de un vistazo. */
private val SolveColor = Color(0xFF2E7D32)
private val CopyColor = Color(0xFF1565C0)
private val ColorColor = Color(0xFF8E24AA)
private val DeleteColor = Color(0xFFC62828)
private val AiColor = Color(0xFF2F6BFF)

/**
 * Menú justo debajo de lo elegido con el lazo (arriba si no entra): resolver, copiar, color y borrar,
 * cada uno con su color. La barra de selección completa sigue abajo.
 */
@Composable
fun SelectionMenu(vm: EditorViewModel, onCustomColor: () -> Unit) {
    val b = vm.selectionBounds ?: return
    val context = LocalContext.current
    val scale = vm.viewport.scale.takeIf { it > 0f } ?: 1f
    val left = b.left * scale + vm.viewport.tx
    val right = b.right * scale + vm.viewport.tx
    val top = b.top * scale + vm.viewport.ty
    val bottom = b.bottom * scale + vm.viewport.ty
    var colors by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                // Separado de los tiradores de la esquina para no taparlos.
                val gap = 30.dp.roundToPx()
                val x = ((left + right) / 2f - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                val below = bottom.roundToInt() + gap
                val y = if (below + p.height <= constraints.maxHeight) below
                else (top.roundToInt() - gap - p.height).coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0))
                p.place(x, y)
            }
        },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuAction(TrazoIcons.Calculate, "Resolver", SolveColor) {
                vm.calculateSelection()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            MenuAction(TrazoIcons.Copy, "Copiar", CopyColor) {
                vm.copySelection { image ->
                    Toast.makeText(
                        context,
                        if (image) "Copiado: mantené el lazo apretado donde quieras pegarlo" else "Copiado para pegar en Trazo",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Box {
                MenuAction(TrazoIcons.Palette, "Color", ColorColor) { colors = true }
                PanelMenu(colors, { colors = false }) {
                    ToolPanel("Color de la selección") {
                        ColorGrid(
                            PenPalette, selected = vm.selectionColor ?: 0,
                            onPick = { c -> vm.recolorSelection(c); colors = false },
                            onCustom = { colors = false; onCustomColor() },
                        )
                    }
                }
            }
            MenuAction(AiStarsIcon, "Revisar", AiColor) { vm.reviewWithAi(selectionOnly = true) }
            MenuAction(Icons.Filled.Delete, "Borrar", DeleteColor) { vm.controller.deleteSelection() }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** «Pegar» donde se dejó apretado el lazo (punto [at] de la vista). */
@Composable
fun PasteMenu(at: Offset, canPaste: Boolean, onPaste: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.width(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = RoundedCornerShape(16.dp)) {
            DropdownMenuItem(
                text = { Text(if (canPaste) "Pegar" else "Nada copiado todavía") },
                leadingIcon = { Icon(TrazoIcons.Paste, null, tint = CopyColor) },
                enabled = canPaste,
                onClick = onPaste,
            )
        }
    }
}
