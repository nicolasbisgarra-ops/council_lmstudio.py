package io.trazo.app.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Paper
import kotlin.math.abs

/**
 * Dibuja trazos vectoriales sobre un [Canvas] ya transformado a coordenadas de documento,
 * así el zoom nunca pixela. Ancho variable según la presión: los tramos se agrupan por ancho
 * cuantizado para dibujar pocos Path con uniones redondas.
 */
object StrokePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun widthAt(stroke: InkStroke, pressure: Float) = stroke.width * (0.45f + 0.55f * pressure)

    fun draw(canvas: Canvas, stroke: InkStroke, colorOverride: Int? = null) {
        val color = colorOverride ?: stroke.color
        val pts = stroke.points
        if (pts.size == 1) {
            dot.color = color
            canvas.drawCircle(pts[0].x, pts[0].y, widthAt(stroke, pts[0].pressure) / 2f, dot)
            return
        }
        paint.color = color
        path.rewind()
        var bucket = quantize(widthAt(stroke, pts[1].pressure))
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val w = quantize(widthAt(stroke, pts[i].pressure))
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
}

/** Fondo de papel (renglones, cuadrícula, puntos) en coordenadas de documento. */
object PaperPainter {
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun draw(canvas: Canvas, paper: Paper, width: Float = Notebook.PAGE_WIDTH, height: Float = Notebook.PAGE_HEIGHT, dark: Boolean = false) {
        fill.color = if (dark) Color.rgb(0x26, 0x2A, 0x29) else Color.rgb(0xFB, 0xFA, 0xF5)
        canvas.drawRect(0f, 0f, width, height, fill)
        line.strokeWidth = 1.2f
        line.color = if (dark) Color.rgb(0x3A, 0x45, 0x4A) else Color.rgb(0xD7, 0xE3, 0xEA)
        val step = 64f
        when (paper) {
            Paper.PLAIN -> Unit
            Paper.LINES -> {
                var y = 120f
                while (y < height) { canvas.drawLine(0f, y, width, y, line); y += step }
                line.color = if (dark) Color.rgb(0x5A, 0x3A, 0x3A) else Color.rgb(0xF0, 0xC8, 0xC8)
                canvas.drawLine(60f, 0f, 60f, height, line)
            }
            Paper.GRID -> {
                val g = step / 2
                var x = g; while (x < width) { canvas.drawLine(x, 0f, x, height, line); x += g }
                var y = g; while (y < height) { canvas.drawLine(0f, y, width, y, line); y += g }
            }
            Paper.DOTS -> {
                fill.color = line.color
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
