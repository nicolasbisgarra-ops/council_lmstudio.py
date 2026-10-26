package io.trazo.core

import io.trazo.core.fixtures.HandScript
import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * T22 (nivel motor): un lápiz SINTÉTICO resuelve el ejercicio completo del handoff usando la
 * herramienta (integral, raíz y fracción por gesto + pausa de 1 s), y se verifica que todo lo que
 * queda en la página son trazos. Se generan renders PNG del modelo como evidencia.
 *
 * Esto NO es la app Android ni un S Pen: es el motor puro en JVM. Ver docs/test-results.md.
 */
class ExerciseEvidenceTest {
    private val evidenceDir = File(System.getProperty("trazo.evidenceDir") ?: "build/reports/trazo-evidence")
    private val recordFrames = System.getProperty("trazo.frames") == "true"

    @Test fun `T22 engine solves the full integral exercise by hand with gesture conversions`() {
        val controller = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential())
        var frame = 0
        var sampleCount = 0
        val framesDir = File(evidenceDir, "frames").apply { if (recordFrames) { deleteRecursively(); mkdirs() } }
        lateinit var pen: SyntheticPen
        pen = SyntheticPen(controller, onSample = {
            if (recordFrames && sampleCount++ % 4 == 0) {
                ImageIO.write(render(controller, pen.nowMs, 0.62f), "png", File(framesDir, "f%05d.png".format(frame++)))
            }
        })
        val log = StringBuilder()
        controller.addListener { e -> if (e is InkEvent.Converted) log.appendLine("t=${pen.nowMs} convertido: ${e.pattern.id} (${"%.2f".format(e.score)})") }

        fun write(text: String, x: Float, y: Float, size: Float): Float {
            val (strokes, end) = HandScript.write(text, x, y, size, seed = text.hashCode())
            for (s in strokes) { pen.stroke(s); pen.pause(140) }
            pen.pause(200)
            return end
        }
        fun lastOutput(): Bounds = controller.page.strokes.filter { it.patternId != null }.let { all ->
            val g = all.last().groupId; all.filter { it.groupId == g }.bounds()
        }

        // ∫₀² (3x² + 2x) dx
        pen.stroke(HandScript.integralGesture(70f, 60f, 170f), holdMs = 1100, tremor = 1.5f)
        pen.pause(400)
        val integral = lastOutput()
        write("2", integral.right + 2f, integral.top - 8f, 34f)
        write("0", integral.centerX + 4f, integral.bottom - 24f, 30f)
        var x = write("(3x^2+2x)", integral.right + 40f, integral.centerY - 34f, 62f)
        write("dx", x + 22f, integral.centerY - 34f, 62f)
        pen.pause(1500)

        // = [x³ + x²]₀²
        x = write("=[x^3+x^2]", 120f, 300f, 62f)
        write("2", x + 4f, 285f, 32f)
        write("0", x + 4f, 352f, 32f)
        pen.pause(1500)
        write("=(8+4)-0", 120f, 430f, 62f)
        pen.pause(1500)
        write("=12", 120f, 560f, 62f)
        pen.pause(2000)

        // Segundo ejemplo: √16 = 4
        pen.stroke(HandScript.rootGesture(120f, 720f, 95f, 70f), holdMs = 1100, tremor = 1.5f)
        pen.pause(400)
        val root = lastOutput()
        x = write("16", root.left + root.width * 0.42f, root.top + root.height * 0.3f, root.height * 0.58f)
        write("=4", maxOf(x, root.right) + 25f, root.top + root.height * 0.3f, root.height * 0.58f)
        pen.pause(2000)

        // Tercer ejemplo: 6 / 3 = 2 con barra de fracción
        pen.stroke(HandScript.fractionGesture(100f, 1010f, 110f), holdMs = 1100, tremor = 1f)
        pen.pause(400)
        val bar = lastOutput()
        write("6", bar.centerX - 18f, bar.top - 72f, 58f)
        write("3", bar.centerX - 18f, bar.bottom + 14f, 58f)
        write("=2", bar.right + 30f, bar.centerY - 34f, 62f)
        pen.pause(1000)

        val conversions = pen.events.filterIsInstance<InkEvent.Converted>().map { it.pattern.id }
        assertEquals(listOf(DefaultPatterns.INTEGRAL, DefaultPatterns.ROOT, DefaultPatterns.FRACTION), conversions)
        val strokes = controller.page.strokes
        assertTrue(strokes.size > 40)
        assertTrue("todo son trazos con puntos", strokes.all { it.points.isNotEmpty() })
        assertEquals(3, strokes.mapNotNull { it.groupId }.toSet().size)
        assertTrue(pen.events.none { it is InkEvent.Ambiguous || it is InkEvent.NotRecognized })

        evidenceDir.mkdirs()
        ImageIO.write(render(controller, pen.nowMs, 1f), "png", File(evidenceDir, "exercise-final.png"))
        File(evidenceDir, "exercise-log.txt").writeText(
            "Simulación JVM del motor (lápiz sintético, sin Android ni S Pen)\n" +
                "Trazos en la página: ${strokes.size}\n" + log,
        )
        // Persistencia del resultado: ida y vuelta sin pérdidas.
        val nb = Notebook("demo", "Demo", listOf(Page("p", strokes)))
        assertEquals(nb, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(nb)))
    }

    /** Render del modelo (solo líneas vectoriales). La cabecera identifica la simulación; la hoja es solo tinta. */
    private fun render(c: InkController, now: Long, scale: Float): BufferedImage {
        val header = (56 * scale).toInt()
        val w = (Notebook.PAGE_WIDTH * scale).toInt()
        val h = (Notebook.PAGE_HEIGHT * scale).toInt() + header
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color(0x2B2F2E); g.fillRect(0, 0, w, header)
        g.color = Color(0xF4F1E8); g.font = Font(Font.SANS_SERIF, Font.BOLD, (17 * scale).toInt())
        g.drawString("SIMULACIÓN JVM DEL MOTOR TRAZO — lápiz sintético, no es la app Android ni un S Pen", (14 * scale).toInt(), (34 * scale).toInt())
        g.translate(0, header)
        g.color = Color(0xFBFAF5); g.fillRect(0, 0, w, h - header)
        g.color = Color(0xD7E3EA); g.stroke = BasicStroke(1f)
        var y = 120f
        while (y < Notebook.PAGE_HEIGHT) { g.draw(Line2D.Float(0f, y * scale, w.toFloat(), y * scale)); y += 64f }
        g.color = Color(0xF0C8C8); g.draw(Line2D.Float(60f * scale, 0f, 60f * scale, (h - header).toFloat()))
        val all = c.page.strokes + listOfNotNull(c.inProgress)
        for (s in all) {
            g.color = Color(s.color, true)
            if (s.points.size == 1) {
                val r = s.width * scale / 2
                g.fill(Ellipse2D.Float(s.points[0].x * scale - r, s.points[0].y * scale - r, 2 * r, 2 * r))
            }
            for (i in 1 until s.points.size) {
                val a = s.points[i - 1]; val b = s.points[i]
                g.stroke = BasicStroke(s.width * scale * (0.45f + 0.55f * b.pressure), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Float(a.x * scale, a.y * scale, b.x * scale, b.y * scale))
            }
        }
        c.holdAnchor?.let { p ->
            val progress = c.dwellProgress(now)
            if (progress > 0.15f) {
                val r = 22f * scale
                g.color = Color(0x3F7D6B); g.stroke = BasicStroke(3f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Arc2D.Float(p.x * scale - r, p.y * scale - r, 2 * r, 2 * r, 90f, -360f * progress, Arc2D.OPEN))
            }
            g.color = Color(0x55, 0x55, 0x55, 0x66)
            g.fill(Ellipse2D.Float(p.x * scale - 6, p.y * scale - 6, 12f, 12f))
        }
        g.dispose()
        return img
    }
}
