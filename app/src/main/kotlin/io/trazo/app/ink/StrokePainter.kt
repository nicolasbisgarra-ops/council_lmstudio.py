package io.trazo.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.trazo.core.Brush
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Paper
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja trazos vectoriales sobre un [Canvas] ya transformado a coordenadas de documento,
 * así el zoom nunca pixela. El ancho de cada muestra depende de la punta ([Brush]): los tramos se
 * agrupan por ancho cuantizado para dibujar pocos Path con uniones redondas.
 */
object StrokePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    /** Ángulo del bisel de la punta caligráfica (sube hacia la derecha). */
    private const val NIB_ANGLE = -Math.PI.toFloat() / 4f
    /** Opacidad del lápiz de grafito. */
    private const val PENCIL_ALPHA = 0xD0

    /** Ancho de la muestra [i] según la punta del trazo. */
    fun widthAt(stroke: InkStroke, i: Int): Float {
        val pts = stroke.points
        val p = pts[i].pressure
        return when (stroke.brush) {
            Brush.PEN -> stroke.width * (0.45f + 0.55f * p)
            Brush.BALLPOINT -> stroke.width * (0.75f + 0.15f * p)
            Brush.PENCIL -> stroke.width * (0.4f + 0.45f * p)
            Brush.HIGHLIGHTER -> stroke.width
            Brush.CALLIGRAPHY -> {
                val a = pts[max(i - 1, 0)]
                val b = pts[min(i + 1, pts.lastIndex)]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val f = if (dx == 0f && dy == 0f) 0.6f else abs(sin(atan2(dy, dx) - NIB_ANGLE))
                stroke.width * (0.3f + 1.25f * f) * (0.75f + 0.25f * p)
            }
        }
    }

    /** @param darkPaper la hoja es oscura: las tintas muy oscuras se muestran claras ([InkLook]). */
    fun draw(canvas: Canvas, stroke: InkStroke, darkPaper: Boolean = false) {
        val color = InkLook.displayColor(stroke.color, darkPaper, stroke.brush)
        when (stroke.brush) {
            Brush.HIGHLIGHTER -> {
                // En una capa opaca que se compone con su opacidad: al repasar no se oscurece.
                val b = stroke.bounds
                val pad = stroke.width
                val saved = canvas.saveLayerAlpha(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, InkLook.highlighterAlpha(color))
                drawPath(canvas, stroke, color or 0xFF000000.toInt(), uniform = true)
                canvas.restoreToCount(saved)
            }
            Brush.PENCIL -> {
                // En una capa: los tramos que se superponen no se ven más oscuros.
                val b = stroke.bounds
                val pad = stroke.width * 2f
                val saved = canvas.saveLayerAlpha(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, PENCIL_ALPHA)
                drawPath(canvas, stroke, color, uniform = false)
                canvas.restoreToCount(saved)
            }
            else -> drawPath(canvas, stroke, color, uniform = false)
        }
    }

    private fun drawPath(canvas: Canvas, stroke: InkStroke, color: Int, uniform: Boolean) {
        val pts = stroke.points
        if (pts.size == 1) {
            dot.color = color
            canvas.drawCircle(pts[0].x, pts[0].y, widthAt(stroke, 0) / 2f, dot)
            return
        }
        paint.color = color
        path.rewind()
        var bucket = quantize(widthAt(stroke, 1))
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val w = if (uniform) bucket else quantize(widthAt(stroke, i))
            if (abs(w - bucket) > 1e-3f) {
                paint.strokeWidth = bucket
                canvas.drawPath(path, paint)
                path.rewind()
                path.moveTo(pts[i - 1].x, pts[i - 1].y)
                bucket = w
            }
            // Curva por puntos medios: suaviza sin alejarse de las muestras.
            if (i < pts.size - 1) {
                val mx = (pts[i].x + pts[i + 1].x) / 2f
                val my = (pts[i].y + pts[i + 1].y) / 2f
                path.quadTo(pts[i].x, pts[i].y, mx, my)
            } else {
                path.lineTo(pts[i].x, pts[i].y)
            }
        }
        paint.strokeWidth = bucket
        canvas.drawPath(path, paint)
    }

    private fun quantize(w: Float) = (w * 4f).toInt().coerceAtLeast(1) / 4f

    /** Dibuja los trazos de una página: primero los resaltadores, así quedan debajo de la tinta. */
    fun drawAll(canvas: Canvas, strokes: List<InkStroke>, darkPaper: Boolean = false) {
        drawHighlighters(canvas, strokes, darkPaper)
        drawInkOnly(canvas, strokes, darkPaper)
    }

    fun drawHighlighters(canvas: Canvas, strokes: List<InkStroke>, darkPaper: Boolean = false) {
        for (s in strokes) if (s.brush == Brush.HIGHLIGHTER) draw(canvas, s, darkPaper)
    }

    /** Solo la tinta (sin resaltadores). */
    fun drawInkOnly(canvas: Canvas, strokes: List<InkStroke>, darkPaper: Boolean = false) {
        for (s in strokes) if (s.brush != Brush.HIGHLIGHTER) draw(canvas, s, darkPaper)
    }
}

/** Fondo de papel (renglones, cuadrícula, puntos) en coordenadas de documento. */
object PaperPainter {
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun draw(
        canvas: Canvas,
        paper: Paper,
        width: Float = Notebook.PAGE_WIDTH,
        height: Float = Notebook.PAGE_HEIGHT,
        colors: PaperColors = PaperColors.LIGHT,
    ) {
        fill.color = colors.sheet
        canvas.drawRect(0f, 0f, width, height, fill)
        line.strokeWidth = 1.2f
        line.color = colors.line
        val step = 64f
        when (paper) {
            Paper.PLAIN -> Unit
            Paper.LINES -> {
                var y = 120f
                while (y < height) { canvas.drawLine(0f, y, width, y, line); y += step }
                line.color = colors.margin
                canvas.drawLine(60f, 0f, 60f, height, line)
            }
            Paper.GRID -> {
                val g = step / 2
                var x = g; while (x < width) { canvas.drawLine(x, 0f, x, height, line); x += g }
                var y = g; while (y < height) { canvas.drawLine(0f, y, width, y, line); y += g }
            }
            Paper.DOTS -> {
                fill.color = colors.line
                val g = step / 2
                var y = g
                while (y < height) {
                    var x = g
                    while (x < width) { canvas.drawCircle(x, y, 1.6f, fill); x += g }
                    y += g
                }
            }
        }
    }
}
