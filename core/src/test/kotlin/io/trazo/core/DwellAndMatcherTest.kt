package io.trazo.core

import io.trazo.core.fixtures.HandScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Pruebas portadas del core de referencia del handoff + casos nuevos del matcher. */
class DwellDetectorTest {
    @Test fun `dwell triggers after 1000ms while down`() {
        val d = DwellDetector(); d.down(1f, 1f, 0); d.move(50f, 50f, 100)
        assertFalse(d.check(1099, 25f)); assertTrue(d.check(1100, 25f))
    }

    @Test fun `T03 up at 900ms prevents conversion`() {
        val d = DwellDetector(); d.down(0f, 0f, 0); d.up(); assertFalse(d.check(1500, 25f))
    }

    @Test fun `T04 zero-length ink never converts`() {
        val d = DwellDetector(); d.down(0f, 0f, 0); assertFalse(d.check(2000, 0f))
    }

    @Test fun `T05 small pen jitter tolerated`() {
        val d = DwellDetector(); d.down(0f, 0f, 0); d.move(3f, 4f, 500); assertTrue(d.check(1000, 25f))
    }

    @Test fun `T06 significant motion resets hold`() {
        val d = DwellDetector(); d.down(0f, 0f, 0); d.move(20f, 0f, 600)
        assertFalse(d.check(1000, 25f)); assertTrue(d.check(1600, 25f))
    }

    @Test fun `canceled contact never converts`() {
        val d = DwellDetector(); d.down(0f, 0f, 0); d.cancel(); assertFalse(d.check(2000, 25f))
    }

    @Test fun `only one conversion per contact`() {
        val d = DwellDetector(); d.down(0f, 0f, 0); assertTrue(d.check(1000, 25f)); assertFalse(d.check(5000, 25f))
    }

    @Test fun `progress grows while still`() {
        val d = DwellDetector(); d.down(0f, 0f, 0)
        assertEquals(0.5f, d.progress(500), 0.001f)
        assertEquals(1f, d.progress(1500), 0.001f)
    }
}

class GestureMatcherTest {
    private val matcher = GestureMatcher()
    private fun s(vararg p: Pair<Float, Float>, id: String = "x") =
        InkStroke(id, DefaultPatterns.curve(*p, perSegment = 4).map { InkPoint(it.first, it.second) })
    private fun line() = s(0f to 0f, 50f to 50f, 100f to 100f)
    private fun horizontal() = s(0f to 0f, 50f to 0f, 100f to 0f)
    private fun vertical() = s(0f to 0f, 0f to 50f, 0f to 100f)
    private fun pattern(id: String, vararg gesture: InkStroke) = InkPattern(id, id, listOf(gesture.toList()), listOf(line()))
    private fun fromPath(path: List<Pair<Float, Float>>, id: String = "g") = InkStroke(id, path.map { InkPoint(it.first, it.second) })

    @Test fun `exact shape recognized`() {
        assertTrue(matcher.recognize(listOf(line()), listOf(pattern("a", line()))) is MatchResult.Found)
    }

    @Test fun `translation and uniform scale invariant`() {
        val moved = line().let { l -> l.copy(points = l.points.map { it.copy(x = it.x * 2 + 300, y = it.y * 2 + 500) }) }
        assertTrue(matcher.recognize(listOf(moved), listOf(pattern("a", line()))) is MatchResult.Found)
    }

    @Test fun `stroke reversal allowed`() {
        val reversed = line().let { it.copy(points = it.points.reversed()) }
        assertTrue(matcher.recognize(listOf(reversed), listOf(pattern("a", line()))) is MatchResult.Found)
    }

    @Test fun `different gesture not matched`() {
        assertEquals(MatchResult.NoMatch, matcher.recognize(listOf(vertical()), listOf(pattern("h", horizontal()))))
    }

    @Test fun `stroke count respected`() {
        assertEquals(MatchResult.NoMatch, matcher.recognize(listOf(line(), line()), listOf(pattern("a", line()))))
    }

    @Test fun `T08 ambiguous templates are not auto-converted`() {
        val r = matcher.recognize(listOf(line()), listOf(pattern("a", line()), pattern("b", line())))
        assertTrue(r is MatchResult.Ambiguous)
    }

    @Test fun `best score picks corresponding template`() {
        val r = matcher.recognize(listOf(line()), listOf(pattern("diag", line()), pattern("h", horizontal())))
        assertTrue(r is MatchResult.Found && r.match.pattern.id == "diag")
    }

    @Test fun `multi-stroke gesture matches in any stroke order`() {
        val a = s(0f to 0f, 100f to 0f, id = "a")
        val b = s(50f to 0f, 50f to 100f, id = "b")
        val p = pattern("T", a, b)
        assertTrue(matcher.recognize(listOf(b, a), listOf(p)) is MatchResult.Found)
    }

    @Test fun `straight vertical line is not an integral`() {
        val r = matcher.recognize(listOf(fromPath(listOf(50f to 0f, 50f to 100f))), DefaultPatterns.all())
        assertEquals(MatchResult.NoMatch, r)
    }

    @Test fun `hand-drawn integral with noise matches integral`() {
        val rnd = Random(3)
        repeat(20) { k ->
            val path = HandScript.integralGesture(100f + k, 200f, 120f + k * 4).map { (x, y) ->
                (x + (rnd.nextFloat() - 0.5f) * 3f) to (y + (rnd.nextFloat() - 0.5f) * 3f)
            }
            val r = matcher.recognize(listOf(fromPath(path)), DefaultPatterns.all())
            assertTrue("intento $k: $r", r is MatchResult.Found && r.match.pattern.id == DefaultPatterns.INTEGRAL)
        }
    }

    @Test fun `a part drawn longer than in the example still matches`() {
        // Sin alineación elástica las muestras del codo caen sobre otro tramo del ejemplo y no alcanza el umbral.
        val cases = listOf(
            listOf(0f to 0f, 0f to 100f, 60f to 100f) to listOf(0f to 0f, 0f to 100f, 90f to 100f),
            listOf(0f to 100f, 0f to 0f, 60f to 100f, 60f to 0f) to listOf(0f to 100f, 0f to 0f, 60f to 70f, 60f to 0f),
        )
        for ((example, drawn) in cases) {
            val p = pattern("p", fromPath(example))
            assertEquals(MatchResult.NoMatch, GestureMatcher(warpBand = 0).recognize(listOf(fromPath(drawn)), listOf(p)))
            val r = matcher.recognize(listOf(fromPath(drawn)), listOf(p))
            assertTrue("$r", r is MatchResult.Found && r.match.score > 0.85f)
        }
    }

    @Test fun `every built-in gesture example matches only its own pattern`() {
        val all = DefaultPatterns.all()
        for (p in all) for (example in p.gestureExamples) {
            val r = matcher.recognize(example, all)
            assertTrue("${p.id}: $r", r is MatchResult.Found && r.match.pattern.id == p.id)
        }
    }

    @Test fun `common handwriting does not trigger built-in patterns`() {
        val all = DefaultPatterns.all()
        for (text in listOf("0", "2", "3", "5", "6", "8", "9", "(", ")")) {
            val (strokes, _) = HandScript.write(text, 0f, 0f, 60f)
            val r = matcher.recognize(strokes.map { fromPath(it) }, all)
            assertEquals("'$text' no debe convertirse", MatchResult.NoMatch, r)
        }
    }

    @Test fun `disabled patterns are ignored`() {
        val p = pattern("a", line()).copy(enabled = false)
        assertEquals(MatchResult.NoMatch, matcher.recognize(listOf(line()), listOf(p)))
    }
}

class InkReplacementTest {
    private fun pattern(output: List<InkStroke>, sizing: OutputSizing = OutputSizing.FIXED) = InkPattern(
        "custom", "custom", listOf(listOf(InkStroke("g", listOf(InkPoint(0f, 0f), InkPoint(1f, 1f))))), output,
        sizing = sizing, fixedWidth = 140f, fixedHeight = 200f,
    )

    @Test fun `output retains native vector points and pressure`() {
        val source = InkStroke("o", listOf(InkPoint(1f, 2f, 0.2f), InkPoint(8f, 17f, 0.9f)))
        val out = InkReplacement.place(pattern(listOf(source)), Bounds(300f, 500f, 400f, 600f), IdGenerator.sequential())
        assertEquals(1, out.size)
        assertEquals(2, out[0].points.size)
        assertEquals(0.9f, out[0].points.last().pressure, 1e-4f)
        // FIXED: escala uniforme min(140/7, 200/15) = 13.33; centrada en (350, 550).
        val b = out.bounds()
        assertEquals(350f, b.centerX, 0.01f)
        assertEquals(550f, b.centerY, 0.01f)
        assertEquals(200f, b.height, 0.05f)
        assertEquals(b.height / b.width, 15f / 7f, 0.01f)
    }

    @Test fun `output is deep-copied, source geometry never changed`() {
        val src = InkStroke("o", listOf(InkPoint(0f, 0f), InkPoint(50f, 0f), InkPoint(100f, 10f)))
        val p = pattern(listOf(src))
        InkReplacement.place(p, Bounds(100f, 100f, 200f, 200f), IdGenerator.sequential())
        assertEquals(0f, p.outputInk[0].points[0].x)
        assertEquals("o", p.outputInk[0].id)
    }

    @Test fun `match height keeps proportions and shares group`() {
        val src = listOf(
            InkStroke("a", listOf(InkPoint(0f, 0f), InkPoint(0f, 100f))),
            InkStroke("b", listOf(InkPoint(0f, 0f), InkPoint(50f, 0f))),
        )
        val out = InkReplacement.place(pattern(src, OutputSizing.MATCH_HEIGHT), Bounds(0f, 0f, 20f, 300f), IdGenerator.sequential("n"))
        assertEquals(300f, out.bounds().height, 0.01f)
        assertEquals(150f, out.bounds().width, 0.01f)
        assertEquals(1, out.map { it.groupId }.toSet().size)
        assertTrue(out.all { it.patternId == "custom" && it.id.startsWith("n") })
    }

    @Test fun `flat gesture never shrinks output to nothing`() {
        val src = listOf(InkStroke("a", listOf(InkPoint(0f, 0f), InkPoint(0f, 100f), InkPoint(40f, 100f))))
        val out = InkReplacement.place(pattern(src, OutputSizing.MATCH_HEIGHT), Bounds(0f, 0f, 200f, 2f), IdGenerator.sequential())
        assertTrue(out.bounds().maxSide >= 50f)
    }
}

class GeometryTest {
    @Test fun `rotation about center keeps center`() {
        val t = Affine.rotate(90f, 10f, 10f)
        assertEquals(10f, t.mapX(10f, 10f), 1e-4f)
        assertEquals(20f, t.mapX(10f, 0f), 1e-4f)
        assertEquals(10f, t.mapY(10f, 0f), 1e-4f)
    }

    @Test fun `compose then`() {
        val t = Affine.translate(5f, 0f).then(Affine.scale(2f, 0f, 0f))
        assertEquals(12f, t.mapX(1f, 0f), 1e-4f)
        assertTrue(abs(t.scale - 2f) < 1e-4f)
    }
}
