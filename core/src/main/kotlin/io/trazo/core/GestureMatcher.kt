package io.trazo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Match(val pattern: InkPattern, val score: Float)

sealed interface MatchResult {
    data class Found(val match: Match) : MatchResult
    data class Ambiguous(val first: Match, val second: Match) : MatchResult
    data object NoMatch : MatchResult
}

/**
 * Comparador geométrico de gestos multi-trazo.
 *
 * 1. Normaliza el conjunto completo (traslación + escala uniforme, conserva proporciones y
 *    posiciones relativas entre trazos).
 * 2. Remuestrea cada trazo a [sampleCount] puntos equiespaciados.
 * 3. Distancia = mezcla de distancia posicional media y diferencia media de dirección
 *    (la dirección evita confundir, p. ej., una línea recta con una integral estrecha). En trazos
 *    muy cortos (puntos, como los de ÷) la dirección es ruido y pesa menos.
 * 4. Admite trazos dibujados al revés y, hasta [maxPermutedStrokes] trazos, en otro orden.
 * 5. Devuelve [MatchResult.NoMatch] bajo el umbral del patrón y [MatchResult.Ambiguous] si el
 *    segundo candidato está a menos de [ambiguityMargin].
 *
 * Los ejemplos normalizados se guardan en caché (por identidad: los patrones son inmutables), así
 * que comparar contra decenas de patrones en cada pausa es barato.
 */
class GestureMatcher(
    private val sampleCount: Int = 32,
    private val ambiguityMargin: Float = 0.06f,
    private val maxPermutedStrokes: Int = 4,
) {
    init { require(sampleCount >= 3) }

    fun recognize(gesture: List<InkStroke>, patterns: List<InkPattern>): MatchResult {
        if (gesture.isEmpty()) return MatchResult.NoMatch
        val g = normalize(gesture)
        val candidates = patterns.asSequence()
            .filter { it.enabled }
            .mapNotNull { p -> bestNormalizedScore(g, p)?.let { Match(p, it) } }
            .sortedByDescending { it.score }
            .toList()
        val best = candidates.firstOrNull() ?: return MatchResult.NoMatch
        if (best.score < best.pattern.minScore) return MatchResult.NoMatch
        val runner = candidates.getOrNull(1)
        if (runner != null && runner.score >= runner.pattern.minScore && best.score - runner.score < ambiguityMargin) {
            return MatchResult.Ambiguous(best, runner)
        }
        return MatchResult.Found(best)
    }

    /** Mejor puntaje entre los ejemplos del patrón con la misma cantidad de trazos; null si ninguno. */
    fun bestScore(gesture: List<InkStroke>, pattern: InkPattern): Float? =
        if (gesture.isEmpty()) null else bestNormalizedScore(normalize(gesture), pattern)

    fun score(a: List<InkStroke>, b: List<InkStroke>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        return scoreNormalized(normalize(a), normalize(b))
    }

    private fun bestNormalizedScore(g: List<Resampled>, pattern: InkPattern): Float? {
        var best: Float? = null
        for (example in pattern.gestureExamples) {
            if (example.size != g.size) continue
            val s = scoreNormalized(g, prepared(example))
            if (best == null || s > best) best = s
        }
        return best
    }

    private fun scoreNormalized(a: List<Resampled>, b: List<Resampled>): Float {
        val n = a.size
        val total = when {
            n == 1 -> strokeDistance(a[0], b[0])
            n <= maxPermutedStrokes -> {
                // Matriz de distancias una sola vez; cada orden posible solo suma.
                val d = Array(n) { i -> FloatArray(n) { j -> strokeDistance(a[i], b[j]) } }
                var best = Float.MAX_VALUE
                for (order in permutations(n)) {
                    var t = 0f
                    for (i in 0 until n) t += d[i][order[i]]
                    best = min(best, t)
                }
                best
            }
            else -> {
                var t = 0f
                for (i in 0 until n) t += strokeDistance(a[i], b[i])
                t
            }
        }
        return (1f - total / n).coerceIn(0f, 1f)
    }

    /** Distancia normalizada (≈0 idéntico, ≥1 muy distinto) entre dos trazos remuestreados. */
    private fun strokeDistance(a: Resampled, b: Resampled): Float =
        min(pairDistance(a, b, reversed = false), pairDistance(a, b, reversed = true))

    private fun pairDistance(a: Resampled, b: Resampled, reversed: Boolean): Float {
        val m = sampleCount
        var pos = 0f
        for (k in 0 until m) {
            val j = if (reversed) m - 1 - k else k
            pos += hypot(a.x[k] - b.x[j], a.y[k] - b.y[j])
        }
        pos /= m
        // La dirección pesa menos cuanto más corto es el trazo: un punto no tiene dirección.
        val w = ((min(a.length, b.length) - DOT_LENGTH) / (FULL_DIRECTION_LENGTH - DOT_LENGTH)).coerceIn(0f, 1f)
        if (w == 0f) return pos / POSITION_SCALE
        val bAng = if (reversed) b.revAng else b.ang
        var dir = 0f
        for (k in 0 until m - 1) dir += angularDiff(a.ang[k], bAng[k])
        dir /= (m - 1) * PI.toFloat()
        return (1f - DIRECTION_WEIGHT * w) * pos / POSITION_SCALE + DIRECTION_WEIGHT * w * dir / DIRECTION_SCALE
    }

    /** Trazo remuestreado, con las direcciones de cada tramo (y las del recorrido al revés) precalculadas. */
    private class Resampled(val x: FloatArray, val y: FloatArray, val length: Float) {
        val ang = FloatArray(x.size - 1) { k -> atan2(y[k + 1] - y[k], x[k + 1] - x[k]) }
        val revAng = FloatArray(x.size - 1) { k -> val i = x.size - 2 - k; atan2(y[i] - y[i + 1], x[i] - x[i + 1]) }
    }

    private class IdentityKey(val ref: Any) {
        override fun equals(other: Any?) = other is IdentityKey && other.ref === ref
        override fun hashCode() = System.identityHashCode(ref)
    }

    private val cache = object : LinkedHashMap<IdentityKey, List<Resampled>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdentityKey, List<Resampled>>?) = size > CACHE_SIZE
    }

    private fun prepared(example: List<InkStroke>): List<Resampled> = synchronized(cache) {
        cache.getOrPut(IdentityKey(example)) { normalize(example) }
    }

    private fun normalize(strokes: List<InkStroke>): List<Resampled> {
        val b = strokes.bounds()
        val side = max(b.maxSide, 0.0001f)
        val ox = (1f - b.width / side) * 0.5f
        val oy = (1f - b.height / side) * 0.5f
        return strokes.map { s ->
            val xs = FloatArray(s.points.size) { (s.points[it].x - b.left) / side + ox }
            val ys = FloatArray(s.points.size) { (s.points[it].y - b.top) / side + oy }
            resample(xs, ys)
        }
    }

    private fun resample(xs: FloatArray, ys: FloatArray): Resampled {
        val n = sampleCount
        val path = FloatArray(xs.size)
        for (i in 1 until xs.size) path[i] = path[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = path.last()
        if (xs.size == 1 || total < 1e-4f) return Resampled(FloatArray(n) { xs[0] }, FloatArray(n) { ys[0] }, 0f)
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        var idx = 1
        for (i in 0 until n) {
            val at = total * i / (n - 1)
            while (idx < xs.lastIndex && path[idx] < at) idx++
            val seg = (path[idx] - path[idx - 1]).coerceAtLeast(1e-6f)
            val t = ((at - path[idx - 1]) / seg).coerceIn(0f, 1f)
            ox[i] = xs[idx - 1] + (xs[idx] - xs[idx - 1]) * t
            oy[i] = ys[idx - 1] + (ys[idx] - ys[idx - 1]) * t
        }
        return Resampled(ox, oy, total)
    }

    private fun angularDiff(a: Float, b: Float): Float {
        var d = abs(a - b) % (2 * PI.toFloat())
        if (d > PI) d = 2 * PI.toFloat() - d
        return d
    }

    companion object {
        // Pesos iniciales calibrados con las pruebas JVM (no con usuarios reales; ver DECISIONES.md).
        private const val POSITION_SCALE = 0.45f
        private const val DIRECTION_WEIGHT = 0.4f
        private const val DIRECTION_SCALE = 0.45f
        /** Largo (relativo al gesto) bajo el cual un trazo es un punto y su dirección no cuenta. */
        private const val DOT_LENGTH = 0.02f
        /** Largo desde el que la dirección pesa completa. */
        private const val FULL_DIRECTION_LENGTH = 0.10f
        private const val CACHE_SIZE = 2048

        private val permutationCache = HashMap<Int, List<IntArray>>()

        private fun permutations(n: Int): List<IntArray> = synchronized(permutationCache) {
            permutationCache.getOrPut(n) {
                val out = mutableListOf<IntArray>()
                fun go(prefix: List<Int>, rest: List<Int>) {
                    if (rest.isEmpty()) { out += prefix.toIntArray(); return }
                    for (r in rest) go(prefix + r, rest - r)
                }
                go(emptyList(), List(n) { it })
                out
            }
        }
    }
}

/** Resultado de intentar reconocer en el momento de la pausa. */
sealed interface Recognition {
    data class Convert(val match: Match, val gesture: List<InkStroke>) : Recognition
    data class Ambiguous(val options: List<Match>, val gesture: List<InkStroke>) : Recognition
    data object None : Recognition
}

/**
 * Elige qué trazos forman el gesto: el trazo actual más hasta [maxStrokes]-1 trazos anteriores
 * escritos a mano, consecutivos, cercanos en tiempo ([maxGapMs]) y espacio ([proximity] × tamaño).
 * Nunca incluye tinta que ya proviene de un patrón, formas ni resaltador ([InkStroke.isHandwriting]).
 */
class GestureRecognizer(
    val matcher: GestureMatcher = GestureMatcher(),
    private val maxStrokes: Int = 4,
    private val maxGapMs: Long = 1500L,
    private val proximity: Float = 0.6f,
) {
    fun candidateGroups(current: InkStroke, previous: List<InkStroke>): List<List<InkStroke>> {
        val groups = mutableListOf(listOf(current))
        val chronological = previous.filter { it.isHandwriting && it.id != current.id }
            .sortedBy { it.endTime }
        var group = listOf(current)
        var nextStart = current.startTime
        for (s in chronological.asReversed()) {
            if (group.size >= maxStrokes) break
            if (nextStart - s.endTime > maxGapMs || nextStart < s.endTime - 5) break
            val gb = group.bounds()
            val size = max(max(gb.maxSide, s.bounds.maxSide), 24f)
            if (gb.gapTo(s.bounds) > proximity * size) break
            group = listOf(s) + group
            nextStart = s.startTime
            groups += group
        }
        return groups
    }

    /**
     * Prueba de los grupos más grandes a los más chicos y se queda con el primero reconocido.
     * Una ambigüedad nunca convierte automáticamente.
     */
    fun recognize(current: InkStroke, previous: List<InkStroke>, patterns: List<InkPattern>): Recognition {
        for (group in candidateGroups(current, previous).asReversed()) {
            when (val r = matcher.recognize(group, patterns)) {
                is MatchResult.Found -> return Recognition.Convert(r.match, group)
                is MatchResult.Ambiguous -> return Recognition.Ambiguous(listOf(r.first, r.second), group)
                MatchResult.NoMatch -> Unit
            }
        }
        return Recognition.None
    }
}
