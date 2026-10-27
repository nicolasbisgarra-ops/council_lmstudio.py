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
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * ¿Se confunden los patrones de la biblioteca de partida? Se mide con el comparador real:
 *  - choques entre ejemplos de patrones activos (todo debe quedar bajo [PresetPatterns.CONFLICT_SCORE]);
 *  - gestos deformados como a mano (escala, inclinación, giro, ondulación): nunca se convierten en otro patrón;
 *  - los ejemplos reales del usuario, dejando cada uno afuera: nunca se convierten en otro patrón;
 *  - un número escrito justo antes del gesto (integral, raíz, fracción): ni se lo traga ni se confunde.
 * Escribe un informe (`presets-report.txt`) y una hoja con todos los signos (`presets.png`) en la evidencia.
 */
class PresetAnalysisTest {
    private val evidenceDir = File(System.getProperty("trazo.evidenceDir") ?: "build/reports/trazo-evidence")
    private val matcher = GestureMatcher()
    private val lib = PresetPatterns.install(emptyList(), 0, matcher)
    private val active = lib.filter { it.enabled }

    private enum class Outcome { RIGHT, AMBIGUOUS, MISSED, WRONG }
    private class Trial(val pattern: InkPattern, val outcome: Outcome, val detail: String)

    @Test fun `no two active patterns of the starting library are confusable`() {
        val pairs = mutableListOf<Triple<InkPattern, InkPattern, Float>>()
        for (i in active.indices) for (j in i + 1 until active.size) {
            val s = PresetPatterns.conflictScore(active[i], active[j], matcher)
            if (s > 0f) pairs += Triple(active[i], active[j], s)
        }
        pairs.sortByDescending { it.third }
        val report = StringBuilder("Biblioteca de partida: ${lib.size} patrones, ${active.size} activos\n\n")
        report.appendLine("Signos que cedieron ante un patrón anterior (umbral ${fmt(PresetPatterns.CONFLICT_SCORE)}):")
        for (original in PresetPatterns.signs()) {
            val installed = lib.single { it.id == original.id }
            if (installed == original) continue
            val earlier = lib.takeWhile { it.id != original.id }.filter { it.enabled }
            val namesake = PresetPatterns.sameNameAs(original, earlier)
            val culprit = namesake ?: earlier.maxByOrNull { PresetPatterns.conflictScore(original, it, matcher) }!!
            val what = if (!installed.enabled) "DESACTIVADO" else "ejemplos ${original.gestureExamples.size} → ${installed.gestureExamples.size}"
            val why = if (namesake != null) "mismo nombre" else fmt(PresetPatterns.conflictScore(original, culprit, matcher))
            report.appendLine("  ${original.name} (${original.id}): $what por ${culprit.name} ($why)")
        }
        report.appendLine("\nDesactivados de fábrica: " + PresetPatterns.signs().filter { !it.enabled }.joinToString { it.name })
        report.appendLine("\nPares activos más parecidos:")
        pairs.take(30).forEach { (a, b, s) -> report.appendLine("  ${fmt(s)}  ${a.name} (${a.id}) ~ ${b.name} (${b.id})") }
        writeReport("presets-report.txt", report.toString())
        println(report)

        assertTrue("${pairs.firstOrNull()?.let { "${it.first.id} ~ ${it.second.id} ${it.third}" }}", pairs.all { it.third < PresetPatterns.CONFLICT_SCORE })
        // Siguen activos el núcleo, los patrones del usuario y la gran mayoría de los signos.
        assertTrue(lib.filterNot { PresetPatterns.isSign(it) }.all { it.enabled })
        assertTrue("${active.size} activos", active.count { PresetPatterns.isSign(it) } >= 70)
    }

    @Test fun `gestures distorted like a hand would draw them are never converted into another pattern`() {
        val trials = mutableListOf<Trial>()
        for (p in active) for ((k, example) in p.gestureExamples.withIndex()) {
            val random = Random(p.id.hashCode() * 31 + k)
            repeat(DISTORTIONS) { trials += trial(p, distort(example, random), lib) }
        }
        summarize("Gestos deformados ($DISTORTIONS por ejemplo)", trials, "presets-distorted.txt")
        val wrong = trials.filter { it.outcome == Outcome.WRONG }
        assertTrue(wrong.joinToString("\n") { "${it.pattern.id}: ${it.detail}" }, wrong.isEmpty())
        assertTrue(rate(trials, Outcome.RIGHT) >= 0.85f)
    }

    @Test fun `the user's own samples are never converted into another pattern`() {
        val trials = mutableListOf<Trial>()
        val userIds = PresetPatterns.bundled().map { it.id } + DefaultPatterns.ROOT
        for (p in lib.filter { it.id in userIds && it.gestureExamples.size > 1 }) {
            for ((k, example) in p.gestureExamples.withIndex()) {
                val without = p.copy(gestureExamples = p.gestureExamples.filterIndexed { i, _ -> i != k })
                trials += trial(p, example, lib.map { if (it.id == p.id) without else it })
            }
        }
        summarize("Ejemplos del usuario, dejando cada uno afuera", trials, "presets-leave-one-out.txt")
        val wrong = trials.filter { it.outcome == Outcome.WRONG }
        assertTrue(wrong.joinToString("\n") { "${it.pattern.id}: ${it.detail}" }, wrong.isEmpty())
        // Con la alineación elástica la gran mayoría de su letra se reconoce (antes, 12 de 59).
        assertTrue("${rate(trials, Outcome.RIGHT)}", rate(trials, Outcome.RIGHT) >= 0.8f)
    }

    @Test fun `a number written right before a gesture is neither swallowed nor mistaken`() {
        val recognizer = GestureRecognizer(matcher)
        val failures = mutableListOf<String>()
        for (digit in "0123456789x") {
            for ((expected, gestureAt) in gestures) {
                var t = 1_000L
                val (paths, end) = HandScript.write("$digit", 100f, 100f, 60f, seed = digit.code)
                val written = paths.mapIndexed { i, path -> ink("w$i", path, t).also { t = it.endTime + 140 } }
                val gesture = ink("g", gestureAt(end), t + 110)
                when (val r = recognizer.recognize(gesture, written, lib)) {
                    is Recognition.Convert -> if (r.match.pattern.id != expected || r.gesture != listOf(gesture)) {
                        failures += "'$digit' + $expected → ${r.match.pattern.id} (${fmt(r.match.score)}) con ${r.gesture.size} trazos"
                    }
                    is Recognition.Ambiguous -> failures += "'$digit' + $expected → ambiguo ${r.options.joinToString { "${it.pattern.id} ${fmt(it.score)}" }}"
                    Recognition.None -> failures += "'$digit' + $expected → nada"
                }
            }
        }
        writeReport("presets-after-digit.txt", "Número y enseguida un gesto (${11 * gestures.size} casos)\n" + failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `contact sheet of the starting library`() {
        val cols = 10
        val cell = 132
        val rows = lib.groupBy { PatternCategory.of(it) }.let { groups -> groups.values.sumOf { (it.size + cols - 1) / cols } + groups.size }
        val img = BufferedImage(cols * cell, rows * cell + 10, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xFBFAF5); g.fillRect(0, 0, img.width, img.height)
        var row = 0
        for (category in PatternCategory.ORDER) {
            val section = lib.filter { PatternCategory.of(it) == category }
            if (section.isEmpty()) continue
            g.color = Color(0x2B2F2E); g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
            g.drawString("$category (${section.size})", 12, row * cell + cell / 2 + 8)
            row++
            for ((i, p) in section.withIndex()) {
                val x0 = (i % cols) * cell
                val y0 = (row + i / cols) * cell
                draw(g, p.outputInk, x0 + 14f, y0 + 8f, cell - 28f, cell - 38f, if (p.enabled) Color(DEFAULT_INK_COLOR, true) else Color(0xB0B0B0))
                g.color = if (p.enabled) Color(0x55605C) else Color(0xB0B0B0); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
                g.drawString(p.name.take(18), x0 + 8, y0 + cell - 10)
            }
            row += (section.size + cols - 1) / cols
        }
        g.dispose()
        evidenceDir.mkdirs()
        val file = File(evidenceDir, "presets.png")
        ImageIO.write(img, "png", file)
        assertTrue(file.length() > 0)
        assertEquals(lib.size, PatternCategory.ORDER.sumOf { c -> lib.count { PatternCategory.of(it) == c } })
    }

    // ------------------------------------------------------------------ utilidades

    /** Integral, raíz y fracción dibujadas junto a un número que termina en x = [end]. */
    private val gestures: List<Pair<String, (Float) -> List<Pair<Float, Float>>>> = listOf(
        DefaultPatterns.INTEGRAL to { end -> HandScript.integralGesture(end + 14f, 80f, 110f) },
        DefaultPatterns.ROOT to { end -> HandScript.rootGesture(end + 14f, 92f, 76f, 110f) },
        DefaultPatterns.FRACTION to { _ -> HandScript.fractionGesture(88f, 174f, 70f) },
    )

    private fun ink(id: String, path: List<Pair<Float, Float>>, start: Long): InkStroke {
        val pts = SyntheticPen.resample(path, 3f)
        return InkStroke(id, pts.mapIndexed { i, (x, y) -> InkPoint(x, y, 0.75f, i * 8L) }, startTime = start)
    }

    private fun trial(p: InkPattern, gesture: List<InkStroke>, patterns: List<InkPattern>): Trial =
        when (val r = matcher.recognize(gesture, patterns)) {
            is MatchResult.Found -> Trial(p, if (r.match.pattern.id == p.id) Outcome.RIGHT else Outcome.WRONG, "→ ${r.match.pattern.id} ${fmt(r.match.score)}")
            is MatchResult.Ambiguous -> {
                val ids = listOf(r.first.pattern.id, r.second.pattern.id)
                Trial(p, if (p.id in ids) Outcome.AMBIGUOUS else Outcome.WRONG, "→ ambiguo ${r.first.pattern.id} ${fmt(r.first.score)} / ${r.second.pattern.id} ${fmt(r.second.score)}")
            }
            MatchResult.NoMatch -> Trial(p, Outcome.MISSED, "→ nada (propio ${fmt(matcher.bestScore(gesture, patterns.single { it.id == p.id }) ?: 0f)})")
        }

    /**
     * Copia del gesto como la dibujaría otra mano: escala distinta en x e y, inclinación, giro,
     * cada trazo un poco corrido y una ondulación suave a lo largo del recorrido.
     */
    private fun distort(gesture: List<InkStroke>, random: Random): List<InkStroke> {
        fun r(a: Float) = (random.nextFloat() * 2 - 1) * a
        val b = gesture.bounds()
        val size = max(b.maxSide, 1f)
        val t = Affine.translate(-b.centerX, -b.centerY)
            .then(Affine(a = 1f + r(0.12f), d = 1f + r(0.12f), c = r(0.12f)))
            .then(Affine.rotate(r(7f), 0f, 0f))
            .then(Affine.translate(b.centerX, b.centerY))
        val amp = 0.018f * size
        val k1 = 1f + random.nextFloat() * 1.5f
        val k2 = 1f + random.nextFloat() * 1.5f
        val ph1 = random.nextFloat() * 2 * PI.toFloat()
        val ph2 = random.nextFloat() * 2 * PI.toFloat()
        val total = max(gesture.sumOf { it.length.toDouble() }.toFloat(), 1f)
        var along = 0f
        return gesture.map { s ->
            val dx = r(0.035f * size)
            val dy = r(0.035f * size)
            val moved = s.transformed(t, scaleWidth = false)
            moved.copy(points = moved.points.mapIndexed { i, p ->
                if (i > 0) along += dist(s.points[i - 1], s.points[i])
                val u = along / total
                p.copy(x = p.x + dx + amp * sin(2 * PI.toFloat() * k1 * u + ph1), y = p.y + dy + amp * sin(2 * PI.toFloat() * k2 * u + ph2))
            })
        }
    }

    private fun summarize(title: String, trials: List<Trial>, file: String) {
        val out = StringBuilder("$title: ${trials.size} intentos\n")
        for (o in Outcome.entries) out.appendLine("  $o: ${trials.count { it.outcome == o }} (${fmt(rate(trials, o))})")
        out.appendLine("\nPor patrón (bien / ambiguo / nada / MAL):")
        for ((p, list) in trials.groupBy { it.pattern }) {
            val c = Outcome.entries.map { o -> list.count { it.outcome == o } }
            if (c[0] == list.size) continue
            out.appendLine("  ${p.name} (${p.id}): ${c.joinToString(" / ")}  " +
                list.filter { it.outcome != Outcome.RIGHT }.map { it.detail }.distinct().take(4).joinToString("; "))
        }
        writeReport(file, out.toString())
        println(out)
    }

    private fun rate(trials: List<Trial>, o: Outcome) = trials.count { it.outcome == o }.toFloat() / max(trials.size, 1)

    private fun writeReport(name: String, text: String) {
        evidenceDir.mkdirs()
        File(evidenceDir, name).writeText(text)
    }

    private fun draw(g: java.awt.Graphics2D, ink: List<InkStroke>, x: Float, y: Float, w: Float, h: Float, color: Color) {
        val b = ink.bounds()
        val k = min(w / max(b.width, 1f), h / max(b.height, 1f))
        val ox = x + (w - b.width * k) / 2 - b.left * k
        val oy = y + (h - b.height * k) / 2 - b.top * k
        g.color = color
        for (s in ink) {
            val sw = max(1.2f, s.width * min(k, 1.6f))
            if (s.points.size == 1) {
                val p = s.points[0]
                g.fill(Ellipse2D.Float(ox + p.x * k - sw, oy + p.y * k - sw, 2 * sw, 2 * sw))
            }
            for (i in 1 until s.points.size) {
                val a = s.points[i - 1]
                val c = s.points[i]
                g.stroke = BasicStroke(sw * (0.45f + 0.55f * c.pressure), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Float(ox + a.x * k, oy + a.y * k, ox + c.x * k, oy + c.y * k))
            }
        }
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    private companion object {
        const val DISTORTIONS = 8
    }
}
