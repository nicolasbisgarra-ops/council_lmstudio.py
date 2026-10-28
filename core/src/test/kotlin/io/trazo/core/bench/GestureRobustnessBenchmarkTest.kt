package io.trazo.core.bench

import io.trazo.core.DefaultPatterns
import io.trazo.core.GestureMatcher
import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.MatchResult
import io.trazo.core.bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Prueba cuantitativa, reproducible y sin archivos externos.
 * Reejecutar con: ./gradlew -Ptrazo.coreOnly=true :core:test --tests '*GestureRobustnessBenchmarkTest'
 * No modifica gestos ni plantillas; imprime una matriz para copiar a docs/RECONOCIMIENTO.md.
 */
class GestureRobustnessBenchmarkTest {
    @Test fun benchmarkIsDeterministicAndBounded() {
        val begin = System.nanoTime()
        val a = RecognitionBenchmark.run()
        val b = RecognitionBenchmark.run()
        assertEquals(a.toReport(), b.toReport())
        assertEquals(RecognitionBenchmark.SEED, a.seed)
        assertTrue(a.positiveCount > 0)
        assertTrue(a.negativeCount > 0)
        assertTrue(a.top3Hits >= a.top1Hits)
        assertTrue("Benchmark superó 60 s", (System.nanoTime() - begin) / 1_000_000 < 60_000)
        println(a.toReport())
    }
}

/** Métrica operativa: `Found` convierte; `Ambiguous` y `NoMatch` no convierten. */
object RecognitionBenchmark {
    const val SEED = 0x3A20_2026
    const val REPETITIONS = 5
    const val SCRIBBLES = 128
    val SCENARIOS = listOf("jitter", "rotation", "scale", "speed-sampling", "split", "reverse-order", "combined")

    data class Case(val truth: String, val scenario: String, val strokes: List<InkStroke>)

    data class Result(
        val seed: Int,
        val positiveCount: Int,
        val negativeCount: Int,
        val top1Hits: Int,
        val top3Hits: Int,
        val acceptedCorrect: Int,
        val falsePositive: Int,
        val ambiguousNegatives: Int,
        val confusion: Map<Pair<String, String>, Int>,
        val scenarioCount: Map<String, Int>,
        val scenarioTop1: Map<String, Int>,
        val scenarioAccepted: Map<String, Int>,
        val names: List<String>,
    ) {
        private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.2f%%".format(java.util.Locale.US, 100.0 * n / d)
        fun toReport(): String = buildString {
            appendLine("RECOGNITION_BENCH seed=$seed patterns=${names.size} positives=$positiveCount negatives=$negativeCount")
            appendLine("top1=${pct(top1Hits, positiveCount)} ($top1Hits/$positiveCount)")
            appendLine("top3=${pct(top3Hits, positiveCount)} ($top3Hits/$positiveCount)")
            appendLine("correct_auto_conversions=${pct(acceptedCorrect, positiveCount)} ($acceptedCorrect/$positiveCount)")
            appendLine("scribble_false_positives=${pct(falsePositive, negativeCount)} ($falsePositive/$negativeCount); ambiguous=$ambiguousNegatives")
            appendLine("SCENARIOS scenario,total,top1,accepted")
            for (s in SCENARIOS) appendLine("$s,${scenarioCount[s] ?: 0},${scenarioTop1[s] ?: 0},${scenarioAccepted[s] ?: 0}")
            appendLine("CONFUSION actual\\predicted,${(names + "AMBIGUOUS" + "NONE").joinToString(",")}")
            for (actual in names) appendLine(actual + "," + (names + "AMBIGUOUS" + "NONE").joinToString(",") { predicted ->
                (confusion[actual to predicted] ?: 0).toString()
            })
            appendLine("TOP_CONFUSIONS actual,predicted,count")
            val allPairs = names.flatMap { actual -> (names + "AMBIGUOUS" + "NONE").filter { it != actual }.map { predicted ->
                (actual to predicted) to (confusion[actual to predicted] ?: 0)
            } }
            allPairs.asSequence()
                .sortedWith(compareByDescending<Pair<Pair<String, String>, Int>> { it.second }
                    .thenBy { it.first.first }.thenBy { it.first.second }).take(10)
                .forEach { (pair, count) -> appendLine("${pair.first},${pair.second},$count") }
        }
    }

    /** `realCases` permite añadir fixtures verificados y etiquetados sin depender de Drive en CI. */
    fun run(
        patterns: List<InkPattern> = DefaultPatterns.all(),
        realCases: List<Case> = emptyList(),
        repetitions: Int = REPETITIONS,
        negatives: Int = SCRIBBLES,
    ): Result {
        require(repetitions > 0 && negatives >= 0)
        val matcher = GestureMatcher()
        val ordered = patterns.filter { it.enabled }.sortedBy { it.id }
        val names = ordered.map { it.id }
        val positives = generatedCases(ordered, repetitions) + realCases
        val confusion = mutableMapOf<Pair<String, String>, Int>()
        val scenarioCount = mutableMapOf<String, Int>()
        val scenarioTop1 = mutableMapOf<String, Int>()
        val scenarioAccepted = mutableMapOf<String, Int>()
        var top1 = 0; var top3 = 0; var accepted = 0
        for (c in positives) {
            require(c.truth in names) { "Etiqueta de fixture desconocida: ${c.truth}" }
            val rank = ordered.mapNotNull { p -> matcher.bestScore(c.strokes, p)?.let { p.id to it } }
                .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            val isTop1 = rank.firstOrNull()?.first == c.truth
            val isTop3 = rank.take(3).any { it.first == c.truth }
            val predicted = when (val match = matcher.recognize(c.strokes, ordered)) {
                is MatchResult.Found -> match.match.pattern.id
                is MatchResult.Ambiguous -> "AMBIGUOUS"
                MatchResult.NoMatch -> "NONE"
            }
            if (isTop1) top1++
            if (isTop3) top3++
            if (predicted == c.truth) accepted++
            confusion[c.truth to predicted] = (confusion[c.truth to predicted] ?: 0) + 1
            scenarioCount[c.scenario] = (scenarioCount[c.scenario] ?: 0) + 1
            if (isTop1) scenarioTop1[c.scenario] = (scenarioTop1[c.scenario] ?: 0) + 1
            if (predicted == c.truth) scenarioAccepted[c.scenario] = (scenarioAccepted[c.scenario] ?: 0) + 1
        }
        var falsePositive = 0; var ambiguousNegative = 0
        for (i in 0 until negatives) {
            val candidate = scribble(Random(SEED xor i * 7919))
            when (matcher.recognize(candidate, ordered)) {
                is MatchResult.Found -> falsePositive++
                is MatchResult.Ambiguous -> ambiguousNegative++
                MatchResult.NoMatch -> Unit
            }
        }
        return Result(SEED, positives.size, negatives, top1, top3, accepted,
            falsePositive, ambiguousNegative, confusion, scenarioCount, scenarioTop1, scenarioAccepted, names)
    }

    fun generatedCases(patterns: List<InkPattern> = DefaultPatterns.all(), repetitions: Int = REPETITIONS): List<Case> = buildList {
        for (pattern in patterns.sortedBy { it.id }) for ((exampleIndex, original) in pattern.gestureExamples.withIndex()) {
            for (scenario in SCENARIOS) for (attempt in 0 until repetitions) {
                val random = Random(SEED xor pattern.id.hashCode() xor (exampleIndex + 1) * 1009 xor scenario.hashCode() xor attempt * 65537)
                val result = when (scenario) {
                    "jitter" -> noisy(original, random, 0.006f + attempt * 0.003f)
                    "rotation" -> geometric(original, if (attempt % 2 == 0) 15f else -15f, 1f)
                    "scale" -> geometric(original, 0f, 0.5f + attempt * 0.375f)
                    "speed-sampling" -> resampled(original, random, attempt)
                    "split" -> split(original)
                    "reverse-order" -> if (original.size > 1) original.reversed() else original.map { it.copy(points = it.points.reversed()) }
                    else -> resampled(noisy(geometric(original, if (attempt % 2 == 0) 15f else -15f,
                        0.5f + attempt * 0.375f), random, 0.008f), random, attempt)
                }
                add(Case(pattern.id, scenario, result))
            }
        }
    }

    private fun geometric(strokes: List<InkStroke>, degrees: Float, scale: Float): List<InkStroke> {
        val box = strokes.bounds()
        val theta = degrees.toDouble() * PI / 180.0
        val c = cos(theta).toFloat(); val s = sin(theta).toFloat()
        return strokes.map { stroke -> stroke.copy(points = stroke.points.map { p ->
            val x = (p.x - box.centerX) * scale
            val y = (p.y - box.centerY) * scale
            p.copy(x = box.centerX + c * x - s * y + 31f, y = box.centerY + s * x + c * y - 17f)
        }) }
    }

    private fun noisy(strokes: List<InkStroke>, rnd: Random, relative: Float): List<InkStroke> {
        val amount = max(strokes.bounds().maxSide, 1f) * relative
        return strokes.map { stroke -> stroke.copy(points = stroke.points.map { p ->
            p.copy(x = p.x + (rnd.nextFloat() * 2f - 1f) * amount,
                y = p.y + (rnd.nextFloat() * 2f - 1f) * amount)
        }) }
    }

    /** Muestra por distancia recorrida y tiempo no uniforme (simula acelerar/frenar y tasas distintas). */
    private fun resampled(strokes: List<InkStroke>, rnd: Random, attempt: Int): List<InkStroke> = strokes.map { stroke ->
        val points = stroke.points
        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        val total = cumulative.last()
        val count = 8 + (attempt * 17 + rnd.nextInt(11))
        val exponent = when (attempt % 4) { 0 -> 0.50; 1 -> 0.75; 2 -> 1.50; else -> 2.20 }
        var k = 1
        stroke.copy(points = List(count) { j ->
            val u = j.toDouble() / (count - 1)
            val distance = (u.pow(exponent) * total).toFloat()
            while (k < points.lastIndex && cumulative[k] < distance) k++
            val denom = (cumulative[k] - cumulative[k - 1]).coerceAtLeast(1e-6f)
            val t = ((distance - cumulative[k - 1]) / denom).coerceIn(0f, 1f)
            InkPoint(points[k - 1].x + (points[k].x - points[k - 1].x) * t,
                points[k - 1].y + (points[k].y - points[k - 1].y) * t,
                points[k - 1].pressure + (points[k].pressure - points[k - 1].pressure) * t,
                (j * (3 + attempt * 5)).toLong())
        })
    }

    /** Dividir un trazo real cambia la cardinalidad: el matcher actual no une fragmentos. */
    private fun split(strokes: List<InkStroke>): List<InkStroke> {
        val index = strokes.indices.maxByOrNull { strokes[it].points.size } ?: return strokes
        val source = strokes[index]
        if (source.points.size < 4) return strokes
        val cut = source.points.size / 2
        val first = source.copy(id = source.id + "-a", points = source.points.take(cut + 1))
        val second = source.copy(id = source.id + "-b", points = source.points.drop(cut))
        return strokes.take(index) + first + second + strokes.drop(index + 1)
    }

    private fun scribble(random: Random): List<InkStroke> = List(1 + random.nextInt(3)) { strokeNo ->
        val count = 12 + random.nextInt(24)
        var x = random.nextFloat() * 80f; var y = random.nextFloat() * 80f
        var angle = random.nextDouble() * PI * 2
        val pts = List(count) { index ->
            if (index > 0) {
                angle += (random.nextDouble() - 0.5) * 2.9
                val distance = 2f + random.nextFloat() * 9f
                x += (cos(angle) * distance).toFloat()
                y += (sin(angle) * distance).toFloat()
            }
            InkPoint(x, y, 0.8f, index * 11L)
        }
        InkStroke("scribble-$strokeNo", pts)
    }
}
