package io.trazo.app.ruler

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.trazo.core.Notebook
import io.trazo.core.Ruler

/**
 * Regla y transportador flotantes: una capa aparte sobre la tinta, fuera de `ink/` porque lleva
 * números en las marcas. No es tinta: no se guarda ni se exporta.
 */
object RulerPainter {
    private val rulerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rulerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rulerRect = RectF()

    /** Dibuja [r] en coordenadas de la hoja (el lienzo ya está transformado a ellas). */
    fun draw(canvas: Canvas, r: Ruler, fill: Int, ink: Int, dpPerUnit: Float) {
        rulerFill.color = fill
        rulerLine.color = withAlpha(ink, 190)
        rulerLine.strokeWidth = 1f * dpPerUnit
        rulerText.color = withAlpha(ink, 210)
        rulerText.textSize = 10f * dpPerUnit
        canvas.save()
        canvas.translate(r.cx, r.cy)
        canvas.rotate(Math.toDegrees(r.angle.toDouble()).toFloat())
        // Una hoja mide 1000 unidades de ancho ≈ 21 cm: marcas cada milímetro.
        val mm = Notebook.PAGE_WIDTH / 210f
        when (r.kind) {
            Ruler.Kind.RULER -> {
                val h = r.thickness / 2; val half = r.length / 2
                rulerRect.set(-half, -h, half, h)
                canvas.drawRect(rulerRect, rulerFill)
                canvas.drawRect(rulerRect, rulerLine)
                val count = (r.length / mm).toInt()
                for (i in 0..count) {
                    val x = -half + i * mm
                    val tick = when { i % 10 == 0 -> 3f; i % 5 == 0 -> 2f; else -> 1.2f } * mm
                    canvas.drawLine(x, -h, x, -h + tick, rulerLine)
                    canvas.drawLine(x, h, x, h - tick, rulerLine)
                    if (i % 10 == 0 && i > 0 && i < count) canvas.drawText((i / 10).toString(), x, -h + 3f * mm + rulerText.textSize, rulerText)
                }
                canvas.drawText(String.format(java.util.Locale.ROOT, "%.0f°", r.degrees), 0f, h - 3.5f * mm, rulerText)
            }
            Ruler.Kind.PROTRACTOR -> {
                val rad = r.radius
                rulerRect.set(-rad, -rad, rad, rad)
                canvas.drawArc(rulerRect, 180f, 180f, true, rulerFill)
                canvas.drawArc(rulerRect, 180f, 180f, true, rulerLine)
                for (d in 0..180) {
                    val t = Math.toRadians(d.toDouble())
                    val ux = kotlin.math.cos(t).toFloat(); val uy = -kotlin.math.sin(t).toFloat()
                    val tick = when { d % 10 == 0 -> 5f; d % 5 == 0 -> 3f; else -> 1.5f } * mm
                    canvas.drawLine(ux * rad, uy * rad, ux * (rad - tick), uy * (rad - tick), rulerLine)
                    if (d % 30 == 0) {
                        val lr = rad - 5f * mm - rulerText.textSize
                        canvas.drawText(d.toString(), ux * lr, uy * lr + rulerText.textSize / 3f, rulerText)
                    }
                }
                canvas.drawCircle(0f, 0f, 1.5f * mm, rulerLine)
            }
        }
        canvas.restore()
    }


    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)
}
