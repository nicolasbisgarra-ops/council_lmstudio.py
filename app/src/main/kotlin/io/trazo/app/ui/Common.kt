package io.trazo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.trazo.app.ink.InkLook
import io.trazo.app.ink.PaperColors
import io.trazo.core.Bounds
import io.trazo.core.Brush
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Paper
import io.trazo.core.bounds
import kotlin.math.max
import kotlin.math.min

/**
 * Vista previa de tinta: dibuja los trazos como líneas vectoriales (nunca texto ni imagen).
 * @param frame si se indica, encaja ese rectángulo (p. ej. la hoja completa) en lugar del de los trazos.
 * @param color si se indica, pinta todo con ese color (p. ej. el gesto superpuesto en una tarjeta).
 */
@Composable
fun InkPreview(strokes: List<InkStroke>, modifier: Modifier = Modifier, frame: Bounds? = null, color: Color? = null) {
    val dark = LocalDarkTheme.current
    Canvas(modifier) {
        if (strokes.isEmpty() && frame == null) return@Canvas
        val b = frame ?: strokes.bounds()
        val pad = 6.dp.toPx()
        val s = min((size.width - 2 * pad) / max(b.width, 1f), (size.height - 2 * pad) / max(b.height, 1f))
        val ox = (size.width - b.width * s) / 2f - b.left * s
        val oy = (size.height - b.height * s) / 2f - b.top * s
        drawInk(strokes, s, ox, oy, dark, color)
    }
}

/** Miniatura de una página: el papel (renglones, cuadrícula o puntos) y su tinta, sin márgenes. */
@Composable
fun PageThumbnail(strokes: List<InkStroke>, paper: Paper, colors: PaperColors, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = min(size.width / Notebook.PAGE_WIDTH, size.height / Notebook.PAGE_HEIGHT)
        drawRect(Color(colors.sheet))
        val line = Color(colors.line)
        val w = max(1f, 1.2f * s)
        val step = 64f
        when (paper) {
            Paper.PLAIN -> Unit
            Paper.LINES -> {
                var y = 120f
                while (y < Notebook.PAGE_HEIGHT) { drawLine(line, Offset(0f, y * s), Offset(size.width, y * s), w); y += step }
                drawLine(Color(colors.margin), Offset(60f * s, 0f), Offset(60f * s, size.height), w)
            }
            Paper.GRID -> {
                val g = step / 2
                var x = g; while (x < Notebook.PAGE_WIDTH) { drawLine(line, Offset(x * s, 0f), Offset(x * s, size.height), w); x += g }
                var y = g; while (y < Notebook.PAGE_HEIGHT) { drawLine(line, Offset(0f, y * s), Offset(size.width, y * s), w); y += g }
            }
            Paper.DOTS -> {
                val g = step / 2
                var y = g
                while (y < Notebook.PAGE_HEIGHT) {
                    var x = g
                    while (x < Notebook.PAGE_WIDTH) { drawCircle(line, max(0.8f, 1.6f * s), Offset(x * s, y * s)); x += g }
                    y += g
                }
            }
        }
        drawInk(strokes, s, 0f, 0f, colors.dark, null)
    }
}

/** Pinta [strokes] escalados por [s] y desplazados (ox, oy); el resaltador va debajo de la tinta. */
private fun DrawScope.drawInk(strokes: List<InkStroke>, s: Float, ox: Float, oy: Float, dark: Boolean, color: Color?) {
    val ordered = strokes.filter { it.brush == Brush.HIGHLIGHTER } + strokes.filter { it.brush != Brush.HIGHLIGHTER }
    for (st in ordered) {
        val shown = InkLook.displayColor(st.color, dark, st.brush)
        val c = color ?: if (st.brush == Brush.HIGHLIGHTER) Color(InkLook.highlighterPaint(shown)) else Color(shown)
        val w = (st.width * s).coerceIn(1.2f, if (st.brush == Brush.HIGHLIGHTER) 10.dp.toPx() else 3.5.dp.toPx())
        if (st.points.size == 1) {
            drawCircle(c, w / 2f, Offset(ox + st.points[0].x * s, oy + st.points[0].y * s))
            continue
        }
        val path = Path()
        st.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(ox + p.x * s, oy + p.y * s) else path.lineTo(ox + p.x * s, oy + p.y * s)
        }
        drawPath(path, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** ¿El dispositivo tiene lápiz? Solo informativo para los ajustes. */
fun hasStylusDevice(): Boolean = android.view.InputDevice.getDeviceIds().any { id ->
    android.view.InputDevice.getDevice(id)?.supportsSource(android.view.InputDevice.SOURCE_STYLUS) == true
}
