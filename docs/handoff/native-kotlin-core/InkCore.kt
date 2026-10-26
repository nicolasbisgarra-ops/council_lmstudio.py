package io.trazo.core

import kotlin.math.*

/** Portable Kotlin logic, not an Android app. All page marks are stored as editable ink points. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)
data class InkStroke(val points: List<InkPoint>, val width: Float = 2.8f, val color: Int = 0xFF263B34.toInt()) {
    init { require(points.isNotEmpty()) { "A stroke needs at least one point" } }
}
data class InkPattern(
    val id: String,
    val gesture: List<InkStroke>,
    val outputInk: List<InkStroke>,
    val outputWidth: Float = 100f,
    val outputHeight: Float = 150f,
    val minScore: Float = 0.78f
) {
    init { require(gesture.isNotEmpty() && outputInk.isNotEmpty()) }
}
data class Match(val pattern: InkPattern, val score: Float)
sealed interface MatchResult {
    data class Found(val match: Match) : MatchResult
    data class Ambiguous(val first: Match, val second: Match) : MatchResult
    data object NoMatch : MatchResult
}

class GestureMatcher(
    private val sampleCount: Int = 32,
    private val ambiguityMargin: Float = 0.06f
) {
    init { require(sampleCount >= 2) }

    fun recognize(gesture: List<InkStroke>, patterns: List<InkPattern>): MatchResult {
        if (gesture.isEmpty()) return MatchResult.NoMatch
        val candidates = patterns.filter { it.gesture.size == gesture.size }
            .map { Match(it, score(gesture, it.gesture)) }
            .sortedByDescending { it.score }
        val best = candidates.firstOrNull() ?: return MatchResult.NoMatch
        if (best.score < best.pattern.minScore) return MatchResult.NoMatch
        val runner = candidates.getOrNull(1)
        if (runner != null && best.score - runner.score < ambiguityMargin && runner.score >= runner.pattern.minScore) {
            return MatchResult.Ambiguous(best, runner)
        }
        return MatchResult.Found(best)
    }

    fun score(a: List<InkStroke>, b: List<InkStroke>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val na = normalize(a)
        val nb = normalize(b)
        var distance = 0f
        for (s in na.indices) {
            val direct = na[s].indices.sumOf { k -> hypot2(na[s][k], nb[s][k]).toDouble() }
            val reverse = na[s].indices.sumOf { k -> hypot2(na[s][k], nb[s][sampleCount - 1 - k]).toDouble() }
            distance += min(direct, reverse).toFloat()
        }
        val avg = distance / (a.size * sampleCount)
        return (1f - avg / 0.55f).coerceIn(0f, 1f)
    }

    private fun normalize(strokes: List<InkStroke>): List<List<Pair<Float, Float>>> {
        val points = strokes.flatMap { it.points }
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val w = (points.maxOf { it.x } - minX).coerceAtLeast(0f)
        val h = (points.maxOf { it.y } - minY).coerceAtLeast(0f)
        val side = max(max(w, h), 0.0001f)
        val ox = (1f - w / side) * 0.5f
        val oy = (1f - h / side) * 0.5f
        return strokes.map { stroke ->
            resample(stroke.points.map { Pair((it.x - minX) / side + ox, (it.y - minY) / side + oy) }, sampleCount)
        }
    }

    private fun resample(raw: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>> {
        if (raw.size == 1) return List(n) { raw[0] }
        val path = FloatArray(raw.size)
        for (i in 1 until raw.size) path[i] = path[i - 1] + hypot2(raw[i], raw[i - 1])
        val total = path.last()
        if (total < 1e-6f) return List(n) { raw[0] }
        var index = 1
        return List(n) { i ->
            val at = total * i / (n - 1)
            while (index < raw.lastIndex && path[index] < at) index++
            val portion = ((at - path[index - 1]) / (path[index] - path[index - 1]).coerceAtLeast(1e-6f)).coerceIn(0f,1f)
            val a = raw[index-1]; val b = raw[index]
            Pair(a.first + (b.first-a.first)*portion, a.second + (b.second-a.second)*portion)
        }
    }

    private fun hypot2(a: Pair<Float, Float>, b: Pair<Float, Float>): Float = hypot(a.first-b.first,a.second-b.second)
}

/** Pure state machine. The Android UI must schedule a non-blocking timer and call check(). */
class DwellDetector(val durationMs: Long = 1000L, val radius: Float = 8f, val minInkLength: Float = 20f) {
    private var touching = false
    private var fired = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var stableFromMs = 0L

    fun down(x: Float, y: Float, timeMs: Long) {
        touching = true; fired = false; anchorX = x; anchorY = y; stableFromMs = timeMs
    }
    fun move(x: Float, y: Float, timeMs: Long) {
        if (!touching || fired) return
        if (hypot(x - anchorX, y - anchorY) > radius) {
            anchorX = x; anchorY = y; stableFromMs = timeMs
        }
    }
    fun check(nowMs: Long, inkLength: Float): Boolean {
        if (!touching || fired || inkLength < minInkLength || nowMs - stableFromMs < durationMs) return false
        fired = true
        return true
    }
    fun cancel() { touching = false; fired = false }
    fun up() { touching = false }
    fun isTouching(): Boolean = touching
}

/** Copies an output drawing into the page; it never creates a text glyph or raster image. */
object InkReplacement {
    fun stamp(pattern: InkPattern, left: Float, top: Float): List<InkStroke> {
        val input = pattern.outputInk.flatMap { it.points }
        val x0 = input.minOf { it.x }; val y0 = input.minOf { it.y }
        val w = (input.maxOf { it.x } - x0).coerceAtLeast(0.0001f)
        val h = (input.maxOf { it.y } - y0).coerceAtLeast(0.0001f)
        return pattern.outputInk.map { stroke ->
            stroke.copy(points = stroke.points.map { p ->
                p.copy(x = left + (p.x - x0) / w * pattern.outputWidth,
                       y = top + (p.y - y0) / h * pattern.outputHeight)
            })
        }
    }
}
