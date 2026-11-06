package io.trazo.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.trazo.core.math.FunctionPlot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Colores de las curvas, en orden: se distinguen bien en hoja clara y oscura. */
private val GRAPH_COLORS = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFFD81B60),
)

/** Ventana visible del plano: centro y unidades por píxel en cada eje. */
private data class GraphView(val cx: Double, val cy: Double, val ux: Double, val uy: Double)

/**
 * Modo gráfica: una lista de funciones de x (se pueden agregar y quitar) dibujadas juntas sobre unos ejes
 * con grilla. Se mueve arrastrando, se acerca con dos dedos y «Ajustar» busca un alto que muestre todas.
 * «Poner en la hoja» inserta la gráfica como imagen debajo de la tinta.
 */
@Composable
fun GraphDialog(vm: EditorViewModel, darkPaper: Boolean, onDismiss: () -> Unit) {
    val saved = rememberSaveable { arrayListOf("x^2 - 2", "sin(x)") }
    val texts = remember { mutableStateListOf<String>().apply { addAll(saved) } }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var view by remember { mutableStateOf<GraphView?>(null) }
    val compiled = texts.map { FunctionPlot.compile(it) }
    val fns = compiled.mapIndexedNotNull { i, c -> (c as? FunctionPlot.Compiled.Ok)?.let { i to it.fn } }

    fun sync() { saved.clear(); saved.addAll(texts) }
    fun fit() {
        if (size.width <= 0 || size.height <= 0) return
        val ux = 20.0 / size.width
        val a = -10.0
        val b = 10.0
        val (lo, hi) = FunctionPlot.fitY(fns.map { it.second }, a, b)
        val uy = (hi - lo) / size.height
        // Si las escalas se parecen, se usa la misma: los círculos se ven redondos.
        val same = abs(uy / ux - 1) < 0.6
        view = GraphView(0.0, (lo + hi) / 2, ux, if (same) ux else uy)
    }
    LaunchedEffect(size) { if (view == null && size.width > 0) fit() }
    val density = LocalDensity.current.density

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gráfica", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::fit) { Text("Ajustar") }
                    TextButton(enabled = fns.isNotEmpty() && size.width > 0, onClick = {
                        val v = view ?: return@TextButton
                        val png = renderPng(size, density, v, fns.map { (i, f) -> GRAPH_COLORS[i % GRAPH_COLORS.size] to f }, darkPaper)
                        vm.insertImageDirect({ ByteArrayInputStream(png) }, darkPaper)
                        onDismiss()
                    }) { Text("Poner en la hoja") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cerrar") }
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f).clipToBounds()
                        .background(if (darkPaper) Color(0xFF15171A) else Color.White)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val v = view ?: return@detectTransformGestures
                                val z = zoom.toDouble().coerceIn(0.5, 2.0)
                                // El punto bajo los dedos queda fijo al acercar.
                                val px = v.cx + (centroid.x - size.width / 2.0) * v.ux
                                val py = v.cy - (centroid.y - size.height / 2.0) * v.uy
                                val ux = (v.ux / z).coerceIn(1e-7, 1e5)
                                val uy = (v.uy / z).coerceIn(1e-7, 1e5)
                                view = GraphView(
                                    px - (centroid.x - size.width / 2.0) * ux - pan.x * ux,
                                    py + (centroid.y - size.height / 2.0) * uy + pan.y * uy,
                                    ux, uy,
                                )
                            }
                        }
                        .pointerInput(texts.joinToString("\n")) { detectTapGestures(onDoubleTap = { fit() }) },
                ) {
                    val v = view
                    if (v != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawGraph(v, fns.map { (i, f) -> GRAPH_COLORS[i % GRAPH_COLORS.size] to f }, darkPaper)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(texts) { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).background(GRAPH_COLORS[i % GRAPH_COLORS.size], CircleShape))
                            val err = (compiled.getOrNull(i) as? FunctionPlot.Compiled.Error)?.message
                            OutlinedTextField(
                                value = t,
                                onValueChange = { texts[i] = it; sync() },
                                singleLine = true,
                                label = { Text("y =") },
                                isError = err != null && t.isNotBlank(),
                                supportingText = if (err != null && t.isNotBlank()) {
                                    { Text(err) }
                                } else null,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            IconButton(onClick = { texts.removeAt(i); sync() }) { Icon(Icons.Filled.Close, "Quitar") }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = texts.size < 8, onClick = { texts += ""; sync() }) {
                                Icon(Icons.Filled.Add, null, Modifier.padding(end = 6.dp)); Text("Agregar función")
                            }
                            Text(
                                "Ej.: 2x^2 - 3, sin x, e^-x, |x|, sqrt(x), ln x. Doble toque para ajustar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(360.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawGraph(v: GraphView, fns: List<Pair<Color, FunctionPlot.Fn>>, dark: Boolean) {
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    val a = v.cx - w / 2 * v.ux
    val b = v.cx + w / 2 * v.ux
    val yLo = v.cy - h / 2 * v.uy
    val yHi = v.cy + h / 2 * v.uy
    fun sx(x: Double) = ((x - a) / v.ux).toFloat()
    fun sy(y: Double) = ((yHi - y) / v.uy).toFloat()
    val grid = if (dark) Color(0x22FFFFFF) else Color(0x18000000)
    val axis = if (dark) Color(0xAAFFFFFF) else Color(0x99000000)
    val label = (if (dark) Color(0xCCFFFFFF) else Color(0xAA000000)).toArgb()
    val stepX = FunctionPlot.niceStep(b - a, (w / 90).toInt().coerceAtLeast(2))
    val stepY = FunctionPlot.niceStep(yHi - yLo, (h / 70).toInt().coerceAtLeast(2))
    val xs = FunctionPlot.ticks(a, b, stepX)
    val ys = FunctionPlot.ticks(yLo, yHi, stepY)
    for (x in xs) drawLine(grid, Offset(sx(x), 0f), Offset(sx(x), size.height), 1f)
    for (y in ys) drawLine(grid, Offset(0f, sy(y)), Offset(size.width, sy(y)), 1f)
    val x0 = sx(0.0).coerceIn(0f, size.width)
    val y0 = sy(0.0).coerceIn(0f, size.height)
    drawLine(axis, Offset(0f, y0), Offset(size.width, y0), 2f)
    drawLine(axis, Offset(x0, 0f), Offset(x0, size.height), 2f)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = label; textSize = 13.dp.toPx()
    }
    val nc = drawContext.canvas.nativeCanvas
    for (x in xs) if (x != 0.0) nc.drawText(fmt(x), sx(x) + 3f, (y0 + paint.textSize + 2f).coerceAtMost(size.height - 4f), paint)
    for (y in ys) if (y != 0.0) nc.drawText(fmt(y), (x0 + 4f).coerceAtMost(size.width - 60f), sy(y) - 3f, paint)
    val samples = (size.width / 1.5f).toInt().coerceIn(200, 4000)
    val stroke = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    for ((color, fn) in fns) {
        val path = Path()
        for (seg in FunctionPlot.sample(fn, a, b, samples, yLo, yHi)) {
            var k = 0
            while (k < seg.size) {
                val px = sx(seg[k].toDouble())
                val py = sy(seg[k + 1].toDouble()).coerceIn(-4 * size.height, 5 * size.height)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                k += 2
            }
        }
        drawPath(path, color, style = stroke)
    }
}

private fun fmt(v: Double): String {
    val r = if (abs(v) >= 1e5 || abs(v) < 1e-3) String.format(java.util.Locale.ROOT, "%.1e", v)
    else String.format(java.util.Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')
    return r.replace('.', ',')
}

/** La gráfica visible como PNG transparente (sin fondo) para ponerla en la hoja. */
private fun renderPng(size: IntSize, density: Float, v: GraphView, fns: List<Pair<Color, FunctionPlot.Fn>>, dark: Boolean): ByteArray {
    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp.asImageBitmap())
    val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
    scope.draw(
        androidx.compose.ui.unit.Density(density), androidx.compose.ui.unit.LayoutDirection.Ltr, canvas,
        androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()),
    ) { drawGraph(v, fns, dark) }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}
