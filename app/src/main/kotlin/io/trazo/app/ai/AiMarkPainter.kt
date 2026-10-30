package io.trazo.app.ai

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import io.trazo.ai.MarkKind
import io.trazo.core.AiNote
import io.trazo.core.AiNotes
import io.trazo.core.AiReviewRecord
import io.trazo.core.Brush
import io.trazo.core.IdGenerator
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * La capa del Modo IA: un óvalo alrededor del renglón, una flecha y la nota en letra manuscrita, en el
 * color de su tipo; y el tilde verde con el puntaje si la revisión no encontró errores. Dibuja en
 * coordenadas de documento con [AiNotes.layout], la misma disposición que usan el toque y la goma.
 */
object AiMarkPainter {
    const val TICK_COLOR = 0xFF2E7D32.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("casual", Typeface.NORMAL)
        textSize = AiNotes.TEXT_SIZE
    }
    private val dash = DashPathEffect(floatArrayOf(7f, 6f), 0f)
    private val path = Path()
    private val oval = RectF()

    fun colorOf(note: AiNote): Int = runCatching { MarkKind.valueOf(note.kind).color }.getOrDefault(MarkKind.CALCULATION.color)

    fun draw(canvas: Canvas, notes: List<AiNote>, verdict: AiReviewRecord?) {
        for (p in AiNotes.layout(notes)) {
            val color = colorOf(p.note)
            line.color = color
            line.pathEffect = if (p.note.disputed) dash else null
            oval.set(p.ring.left, p.ring.top, p.ring.right, p.ring.bottom)
            canvas.drawOval(oval, line)
            line.pathEffect = null
            arrow(p)
            canvas.drawPath(path, line)
            text.color = color
            p.textLines.forEachIndexed { i, s -> canvas.drawText(s, p.label.left + 2f, baseline(p, i), text) }
        }
        verdict?.area?.let { tick(canvas, it.right + 10f, it.top, verdict.score) }
    }

    private fun baseline(p: AiNotes.Placed, i: Int) = p.label.top + AiNotes.TEXT_SIZE * (1.25f * i + 1f)

    /** La flecha va del óvalo a la nota (al costado o debajo). */
    private fun arrow(p: AiNotes.Placed) {
        path.reset()
        val beside = p.label.left > p.ring.right
        val sx = if (beside) p.ring.right else p.ring.left + 24f
        val sy = if (beside) p.ring.centerY else p.ring.bottom
        val ex = if (beside) p.label.left - 3f else p.label.left + 8f
        val ey = if (beside) p.label.top + AiNotes.TEXT_SIZE * 0.6f else p.label.top - 2f
        path.moveTo(sx, sy)
        path.lineTo(ex, ey)
        val a = atan2(ey - sy, ex - sx)
        val head = 7f
        path.moveTo(ex - head * cos(a - 0.45f), ey - head * sin(a - 0.45f))
        path.lineTo(ex, ey)
        path.lineTo(ex - head * cos(a + 0.45f), ey - head * sin(a + 0.45f))
    }

    private fun tick(canvas: Canvas, x: Float, y: Float, score: Double?) {
        line.color = TICK_COLOR
        line.strokeWidth = 4f
        path.reset()
        path.moveTo(x, y + 18f)
        path.lineTo(x + 9f, y + 28f)
        path.lineTo(x + 28f, y + 2f)
        canvas.drawPath(path, line)
        line.strokeWidth = 2.2f
        if (score != null) {
            text.color = TICK_COLOR
            canvas.drawText("${formatScore(score)}/10", x + 32f, y + 24f, text)
        }
    }

    fun formatScore(score: Double): String = if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(score)

    /**
     * La marca como tinta común (11): el óvalo, la flecha y el contorno de cada letra de la nota, en su color.
     * Así queda en la hoja aunque se oculte la capa o se borre la marca.
     */
    fun toInk(note: AiNote, ids: IdGenerator, width: Float = 1.6f, all: List<AiNote> = listOf(note)): List<InkStroke> {
        // Con todas las notas de la hoja: la tinta queda donde se veía la marca (QA53).
        val p = AiNotes.placed(all, note)
        val color = colorOf(note)
        val out = mutableListOf<InkStroke>()
        val group = ids.next()
        fun add(pts: List<InkPoint>, w: Float = width) { if (pts.size > 1) out += InkStroke(ids.next(), pts, color, w, groupId = group, brush = Brush.BALLPOINT) }
        val r = p.ring
        add((0..48).map { i ->
            val t = 2 * Math.PI * i / 48
            InkPoint(r.centerX + r.width / 2f * cos(t).toFloat(), r.centerY + r.height / 2f * sin(t).toFloat())
        }, 2.2f)
        arrow(p)
        contours(path).forEach { add(it, 2.2f) }
        p.textLines.forEachIndexed { i, s ->
            val glyphs = Path()
            text.getTextPath(s, 0, s.length, p.label.left + 2f, baseline(p, i), glyphs)
            contours(glyphs).forEach { add(it, width * 0.6f) }
        }
        return out
    }

    /** Recorre cada contorno del trayecto con un punto cada unidad de hoja. */
    private fun contours(src: Path): List<List<InkPoint>> {
        val m = PathMeasure(src, false)
        val pos = FloatArray(2)
        val out = mutableListOf<List<InkPoint>>()
        do {
            val len = m.length
            if (len <= 0f) continue
            val n = (len / 1f).toInt().coerceIn(2, 400)
            out += (0..n).map { i -> m.getPosTan(len * i / n, pos, null); InkPoint(pos[0], pos[1]) }
        } while (m.nextContour())
        return out
    }
}
