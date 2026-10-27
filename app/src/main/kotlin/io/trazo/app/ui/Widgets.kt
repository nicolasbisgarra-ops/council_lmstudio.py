package io.trazo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.trazo.app.ink.InkLook
import io.trazo.app.ink.StrokePainter
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import io.trazo.core.Brush as InkBrush

/**
 * Botón redondo de la barra: la herramienta elegida se rellena con el color de acento.
 * Un toque largo (o un segundo toque si ya está elegida) abre sus ajustes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    size: Dp = 40.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary else Color.Transparent
    val fg = if (selected) scheme.onPrimary else scheme.onSurface
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/** Separador vertical fino entre grupos de la barra. */
@Composable
fun BarDivider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Muestra de color de tinta. Las tintas muy oscuras llevan un borde para verse sobre la barra oscura. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorDot(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    description: String = "Color ${hexOf(color)}",
    onLongClick: (() -> Unit)? = null,
) {
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(size + 8.dp)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(size + 7.dp).border(2.dp, ring, CircleShape))
        Box(
            Modifier
                .size(if (selected) size - 4.dp else size)
                .background(Color(color), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
        )
    }
}

/** Círculo arcoíris: abre el selector de color libre. */
@Composable
fun RainbowDot(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 26.dp, description: String = "Más colores") {
    Box(
        modifier
            .size(size + 8.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(Brush.sweepGradient(HueStops), CircleShape))
    }
}

private val HueStops = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF),
    Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** Título de sección con el color de acento (como en las preferencias de xnotes). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** Opción tipo píldora: elegida = fondo de acento oscuro con borde de acento. */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, scheme.primary) else null,
        modifier = modifier.semantics { this.selected = selected },
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Fila de opciones excluyentes que se acomoda en varias líneas si no entra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> ChoiceChip(label(o), o == selected, onClick = { onSelect(o) }) }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(inactiveTrackColor = scheme.primaryContainer),
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(
        modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Panel de ajustes de una herramienta: título de acento y contenido con el ancho de un menú. */
@Composable
fun ToolPanel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title)
        content()
    }
}

/** Grilla de colores con la muestra elegida marcada, más el arcoíris para un color libre. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorGrid(colors: List<Int>, selected: Int, onPick: (Int) -> Unit, onCustom: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { c -> ColorDot(c, selected = c == selected, onClick = { onPick(c) }) }
        RainbowDot(onCustom)
    }
}

/** Muestra de una punta: una onda dibujada con el mismo ancho que pinta la hoja. */
@Composable
fun BrushSample(brush: InkBrush, color: Int, width: Float, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val stroke = remember(brush, width) {
        val pts = (0..40).map { i ->
            val t = i / 40f
            InkPoint(t * 100f, 20f - 12f * sin(t * 2f * PI.toFloat()), pressure = 0.35f + 0.6f * sin(t * PI.toFloat()))
        }
        InkStroke("muestra", pts, color = color, width = width, brush = brush)
    }
    Canvas(modifier) {
        val s = min(size.width / 110f, size.height / 40f)
        val ox = (size.width - 100f * s) / 2f
        val oy = (size.height - 40f * s) / 2f
        val shown = InkLook.displayColor(color, dark, brush)
        val c = if (brush == InkBrush.HIGHLIGHTER) Color(InkLook.withAlpha(shown, InkLook.HIGHLIGHTER_ALPHA)) else Color(shown)
        val pts = stroke.points
        for (i in 1 until pts.size) {
            val w = max(1f, StrokePainter.widthAt(stroke, i) * s * 0.9f)
            drawLine(
                c.copy(alpha = if (brush == InkBrush.PENCIL) 0.8f else c.alpha),
                Offset(ox + pts[i - 1].x * s, oy + pts[i - 1].y * s),
                Offset(ox + pts[i].x * s, oy + pts[i].y * s),
                strokeWidth = w,
                cap = if (brush == InkBrush.HIGHLIGHTER) StrokeCap.Butt else StrokeCap.Round,
            )
        }
    }
}

/**
 * Selector de color libre: cuadro de saturación y brillo, barra de tono, código hexadecimal y
 * una fila de colores sugeridos.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    suggestions: List<Int> = PenPalette,
) {
    val start = remember(initial) { argbToHsv(initial) }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var sat by remember(initial) { mutableFloatStateOf(start[1]) }
    var value by remember(initial) { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(hue, sat, value)
    var hex by remember(initial) { mutableStateOf(hexOf(initial)) }
    fun syncHex() { hex = hexOf(color.toArgb()) }
    fun setFrom(argb: Int) {
        val h = argbToHsv(argb)
        hue = h[0]; sat = h[1]; value = h[2]
        hex = hexOf(argb)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            fun at(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures { at(it); hex = hexOf(Color.hsv(hue, sat, value).toArgb()) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { hex = hexOf(Color.hsv(hue, sat, value).toArgb()) }) { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val c = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
                        drawCircle(Color.Black.copy(alpha = 0.4f), 10.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 360f; hex = hexOf(Color.hsv(hue, sat, value).toArgb()) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { hex = hexOf(Color.hsv(hue, sat, value).toArgb()) }) { change, _ ->
                                hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            }
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(Brush.horizontalGradient(HueStops))
                        val x = hue / 360f * size.width
                        drawCircle(Color.White, size.height / 2f - 2.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.5.dp.toPx()))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t
                            parseHex(t)?.let { argb ->
                                val h = argbToHsv(argb)
                                hue = h[0]; sat = h[1]; value = h[2]
                            }
                        },
                        singleLine = true,
                        label = { Text("Código") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                }
                ColorGrid(suggestions, selected = color.toArgb(), onPick = ::setFrom, onCustom = { syncHex() })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color.toArgb()) }) { Text("Usar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Matiz (0..360), saturación y brillo (0..1) de un color ARGB. */
fun argbToHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val d = mx - mn
    val h = when {
        d < 1e-6f -> 0f
        mx == r -> 60f * ((g - b) / d).mod(6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return floatArrayOf(h.mod(360f), if (mx <= 0f) 0f else d / mx, mx)
}

/** "#RRGGBB" (con o sin #) a ARGB opaco; null si no es un código válido. */
fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 || t.any { it.uppercaseChar() !in "0123456789ABCDEF" }) return null
    return (0xFF000000.toInt()) or t.toInt(16)
}

/** Atenúa un ícono deshabilitado igual que los botones de Material. */
@Composable
fun Dimmed(enabled: Boolean, content: @Composable () -> Unit) {
    val c = LocalContentColor.current
    CompositionLocalProvider(LocalContentColor provides if (enabled) c else c.copy(alpha = abs(0.38f))) { content() }
}
