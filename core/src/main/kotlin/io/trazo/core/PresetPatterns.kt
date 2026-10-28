package io.trazo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Biblioteca de partida: los tres patrones del núcleo, los patrones que el usuario trajo de su tablet
 * (recurso `mis-patrones.json`) y los signos matemáticos (números del 0 al 10, operadores, paréntesis,
 * cálculo, letras griegas, conjuntos y lógica, flechas).
 *
 * Cada signo es TINTA VECTORIAL: trazos con presión dibujados con un mini lenguaje de trazos (ver [Glyphs]),
 * nunca una fuente ni una imagen. La salida y los ejemplos de gesto se pueden redibujar en el editor.
 *
 * [install] agrega lo que falte sin pisar nada: los ids existentes nunca se reemplazan, lo que el usuario
 * borró no vuelve, y si los ejemplos de un signo nuevo se confunden con los de un patrón activo esos
 * ejemplos se descartan (o el signo se agrega desactivado si no le queda ninguno). Un signo que se llama
 * igual que un patrón activo del usuario (su «2», su «Igual») también se agrega desactivado: el del
 * usuario manda y así nunca compiten.
 */
object PresetPatterns {
    /** Versión de la biblioteca de partida: 1 = núcleo; 2 = signos y patrones del usuario; 3 = formas ([GeoPatterns]). */
    const val VERSION = 3
    const val PREFIX = "preset-"

    /** Puntaje entre ejemplos desde el que dos patrones se consideran confundibles. */
    const val CONFLICT_SCORE = 0.84f

    private const val BUNDLE = "/io/trazo/core/mis-patrones.json"

    /** Núcleo, patrones del usuario y signos, en orden de prioridad de instalación. */
    fun all(): List<InkPattern> = entries.map { it.pattern }

    /** Solo los signos (ids con [PREFIX]). */
    fun signs(): List<InkPattern> = signsCache

    /** Patrones del usuario incluidos con la app (sin los del núcleo). */
    fun bundled(): List<InkPattern> = bundledUser

    /** Versión de la biblioteca en que apareció el patrón [id]; null si no es de la biblioteca de partida. */
    fun since(id: String): Int? = entries.firstOrNull { it.pattern.id == id }?.since

    /**
     * Agrega a [existing] los patrones de la biblioteca nuevos desde [installedVersion] (0 = biblioteca vacía).
     * El orden de [existing] se conserva; los nuevos van al final en orden de prioridad. El núcleo y los
     * patrones del usuario entran tal cual (ya convivían en su tablet); solo los signos ceden ante los choques.
     */
    fun install(existing: List<InkPattern>, installedVersion: Int, matcher: GestureMatcher = GestureMatcher()): List<InkPattern> {
        val out = existing.toMutableList()
        val ids = existing.mapTo(HashSet()) { it.id }
        for (e in entries) {
            if (e.since <= installedVersion || e.pattern.id in ids) continue
            out += if (isSign(e.pattern)) yieldTo(e.pattern, out.filter { it.enabled }, matcher) else e.pattern
            ids += e.pattern.id
        }
        return out
    }

    /** Signos y formas de la biblioteca: ceden ante los patrones activos con los que chocan. */
    fun isSign(p: InkPattern): Boolean = p.builtIn && (p.id.startsWith(PREFIX) || GeoPatterns.isGeo(p))

    /**
     * Restaura el núcleo tal como viene (al principio) y vuelve a agregar los signos que falten.
     * Los patrones del usuario y los signos existentes (con sus ejemplos y su estado) no cambian.
     */
    fun restore(existing: List<InkPattern>, matcher: GestureMatcher = GestureMatcher()): List<InkPattern> {
        val core = coreWithUserExamples
        val coreIds = core.mapTo(HashSet()) { it.id }
        val out = (core + existing.filterNot { it.id in coreIds }).toMutableList()
        val ids = out.mapTo(HashSet()) { it.id }
        for (p in signsCache + GeoPatterns.all()) {
            if (p.id in ids) continue
            out += yieldTo(p, out.filter { it.enabled }, matcher)
        }
        return out
    }

    /** El signo [p] tal como entra junto a [active]: desactivado si ya hay uno del usuario con su nombre, si no [withoutConflicts]. */
    private fun yieldTo(p: InkPattern, active: List<InkPattern>, matcher: GestureMatcher): InkPattern =
        if (p.enabled && sameNameAs(p, active) != null) p.copy(enabled = false) else withoutConflicts(p, active, matcher)

    /** Patrón activo que no es un signo y se llama igual que [p] (sin distinguir mayúsculas); null si no hay. */
    fun sameNameAs(p: InkPattern, active: List<InkPattern>): InkPattern? =
        active.firstOrNull { it.id != p.id && !isSign(it) && it.name.trim().equals(p.name.trim(), ignoreCase = true) }

    /**
     * [p] sin los ejemplos que se confunden con algún patrón de [active]; desactivado (con sus ejemplos
     * originales) si se confunden todos.
     */
    fun withoutConflicts(p: InkPattern, active: List<InkPattern>, matcher: GestureMatcher = GestureMatcher()): InkPattern {
        if (!p.enabled) return p
        val others = active.filter { it.id != p.id }
        val kept = p.gestureExamples.filter { ex -> others.none { q -> (matcher.bestScore(ex, q) ?: 0f) >= CONFLICT_SCORE } }
        return when {
            kept.size == p.gestureExamples.size -> p
            kept.isEmpty() -> p.copy(enabled = false)
            else -> p.copy(gestureExamples = kept)
        }
    }

    /** Mayor puntaje entre ejemplos de [a] y [b] con la misma cantidad de trazos (0 si no hay). */
    fun conflictScore(a: InkPattern, b: InkPattern, matcher: GestureMatcher = GestureMatcher()): Float =
        a.gestureExamples.maxOf { ex -> matcher.bestScore(ex, b) ?: 0f }

    // ------------------------------------------------------------------ contenido

    private class Entry(val pattern: InkPattern, val since: Int)

    private val bundle: List<InkPattern> by lazy {
        val text = PresetPatterns::class.java.getResourceAsStream(BUNDLE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (text == null) emptyList() else TrazoFormat.decodePatterns(text)
    }

    /** Núcleo con los ejemplos de gesto que el usuario le agregó (p. ej. su raíz en forma de ⌐). */
    private val coreWithUserExamples: List<InkPattern> by lazy {
        DefaultPatterns.core().map { p ->
            val extra = bundle.firstOrNull { it.id == p.id }?.gestureExamples.orEmpty()
            if (extra.isEmpty()) p else p.copy(gestureExamples = (p.gestureExamples + extra).take(InkPattern.MAX_EXAMPLES))
        }
    }

    private val bundledUser: List<InkPattern> by lazy {
        val coreIds = DefaultPatterns.core().map { it.id }.toSet()
        bundle.filterNot { it.id in coreIds }
    }

    private val signsCache: List<InkPattern> by lazy { Glyphs.parse(Glyphs.SOURCES) }

    private val entries: List<Entry> by lazy {
        // Los patrones del usuario solo se instalan en una biblioteca nueva: si ya los tiene (o los borró) no vuelven.
        coreWithUserExamples.map { Entry(it, 1) } + bundledUser.map { Entry(it, 1) } + signsCache.map { Entry(it, 2) } +
            GeoPatterns.all().map { Entry(it, 3) }
    }

    /**
     * Mini lenguaje de trazos. Caja de 100 de alto (y hacia abajo); las minúsculas griegas van de 40 a 100,
     * con ascendentes desde 0 y descendentes hasta ~135.
     *
     * ```
     * @ clave | nombre | categoría | tamaño | escala | on/off [| zona elástica] [| min=0.85]
     * o trazos de salida
     * g trazos de un ejemplo de gesto (puede repetirse; sin líneas g el gesto es la salida)
     * ```
     * - categoría: N números, O operadores, G paréntesis, C cálculo, K griegas, S conjuntos y lógica, A flechas.
     * - tamaño: H mismo alto, W mismo ancho, F cabe en el gesto.
     * - zona elástica: `h0-0.7` (horizontal) o `v0.3-1` (vertical), ver [Stretch].
     * - trazos separados por `/`; cada trazo une tramos con `+`:
     *   `L x,y …` poligonal · `S x,y …` curva suave (un `*` tras el punto lo vuelve esquina) ·
     *   `O cx,cy,rx,ry,a0,a1` arco de elipse en grados (0 = derecha, 90 = abajo) · `D x,y` punto.
     */
    internal object Glyphs {
        private const val OUTPUT_STEP = 2.2f
        private const val GESTURE_STEP = 4f
        private const val DOT_WIDTH = 4.6f
        private const val SPLINE_STEPS = 8

        fun parse(src: String): List<InkPattern> {
            val out = mutableListOf<InkPattern>()
            var head: List<String>? = null
            var output: String? = null
            val gestures = mutableListOf<String>()
            fun flush() {
                val h = head ?: return
                out += build(h, requireNotNull(output) { "Falta la salida de ${h[0]}" }, gestures.toList())
                head = null; output = null; gestures.clear()
            }
            for (raw in src.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when (line[0]) {
                    '@' -> { flush(); head = line.substring(1).split('|').map { it.trim() } }
                    'o' -> output = line.substring(1).trim()
                    'g' -> gestures += line.substring(1).trim()
                    else -> error("Línea inválida: $line")
                }
            }
            flush()
            return out
        }

        private fun build(h: List<String>, output: String, gestures: List<String>): InkPattern {
            val key = h[0]
            var stretch: Stretch? = null
            var minScore = InkPattern.DEFAULT_MIN_SCORE
            for (extra in h.drop(6)) when {
                extra.startsWith("min=") -> minScore = extra.removePrefix("min=").toFloat()
                extra.startsWith("h") || extra.startsWith("v") -> {
                    val (a, b) = extra.substring(1).split('-').map { it.toFloat() }
                    stretch = Stretch(if (extra[0] == 'h') Axis.HORIZONTAL else Axis.VERTICAL, from = a, to = b)
                }
                else -> error("Opción desconocida en $key: $extra")
            }
            val examples = gestures.ifEmpty { listOf(output) }
            return InkPattern(
                id = PREFIX + key,
                name = h[1],
                gestureExamples = examples.mapIndexed { i, g -> strokes(g, "p-$key-g${i + 1}", gesture = true) },
                outputInk = strokes(output, "p-$key-o", gesture = false),
                sizing = when (h[3]) {
                    "H" -> OutputSizing.MATCH_HEIGHT
                    "W" -> OutputSizing.MATCH_WIDTH
                    "F" -> OutputSizing.FIT_GESTURE
                    else -> error("Tamaño desconocido en $key: ${h[3]}")
                },
                outputScale = h[4].toFloat(),
                minScore = minScore,
                enabled = when (h[5]) { "on" -> true; "off" -> false; else -> error("on/off en $key") },
                builtIn = true,
                category = when (h[2]) {
                    "N" -> PatternCategory.NUMBERS
                    "O" -> PatternCategory.OPERATORS
                    "G" -> PatternCategory.GROUPING
                    "C" -> PatternCategory.CALCULUS
                    "K" -> PatternCategory.GREEK
                    "S" -> PatternCategory.SETS
                    "A" -> PatternCategory.ARROWS
                    "L" -> PatternCategory.LETTERS
                    else -> error("Categoría desconocida en $key: ${h[2]}")
                },
                stretch = stretch,
            )
        }

        private fun strokes(spec: String, idPrefix: String, gesture: Boolean): List<InkStroke> =
            spec.split('/').mapIndexed { i, s ->
                val pts = path(s.trim())
                val dot = pts.size == 1
                val dense = densify(pts, if (gesture) GESTURE_STEP else OUTPUT_STEP)
                val total = dense.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }.sum()
                var along = 0f
                InkStroke(
                    id = "$idPrefix${i + 1}",
                    width = if (dot && !gesture) DOT_WIDTH else DEFAULT_INK_WIDTH,
                    points = dense.mapIndexed { k, (x, y) ->
                        if (k > 0) along += hypot(x - dense[k - 1].first, y - dense[k - 1].second)
                        val u = if (total > 0f) along / total else 0.5f
                        // Salida: presión que crece y se afina en las puntas, como un trazo con pluma.
                        val p = if (gesture || dot) 0.8f else (0.35f + 0.65f * sin(PI * u).toFloat()).coerceIn(0.3f, 1f)
                        InkPoint(round2(x), round2(y), round2(p), k * 8L)
                    },
                )
            }

        /** Recorrido de un trazo: sus tramos unidos en orden. */
        private fun path(stroke: String): List<Pair<Float, Float>> {
            val out = mutableListOf<Pair<Float, Float>>()
            for (seg in stroke.split('+')) {
                val t = seg.trim()
                val args = t.substring(1).trim()
                val pts = when (t[0]) {
                    'L', 'D' -> coords(args).map { it.first }
                    'S' -> spline(coords(args))
                    'O' -> arc(args.split(',').map { it.trim().toFloat() })
                    else -> error("Tramo desconocido: $t")
                }
                for (p in pts) if (out.isEmpty() || out.last() != p) out += p
            }
            return out
        }

        private fun coords(args: String): List<Pair<Pair<Float, Float>, Boolean>> =
            args.split(' ').filter { it.isNotBlank() }.map { tok ->
                val corner = tok.endsWith("*")
                val (x, y) = tok.removeSuffix("*").split(',').map { it.toFloat() }
                (x to y) to corner
            }

        /**
         * Curva Catmull-Rom (como [DefaultPatterns.curve]); un punto marcado como esquina se duplica para que
         * la curva llegue y salga en línea recta, y el tramo nulo entre las copias se omite (si no, haría un rulo).
         */
        private fun spline(c: List<Pair<Pair<Float, Float>, Boolean>>): List<Pair<Float, Float>> {
            val ctrl = c.flatMap { (p, corner) -> if (corner) listOf(p, p) else listOf(p) }
            if (ctrl.size < 2) return ctrl
            val out = mutableListOf<Pair<Float, Float>>()
            for (i in 0 until ctrl.size - 1) {
                val p1 = ctrl[i]
                val p2 = ctrl[i + 1]
                if (p1 == p2) continue
                val p0 = ctrl[maxOf(i - 1, 0)]
                val p3 = ctrl[minOf(i + 2, ctrl.size - 1)]
                for (k in 0 until SPLINE_STEPS) {
                    val t = k.toFloat() / SPLINE_STEPS
                    val t2 = t * t
                    val t3 = t2 * t
                    fun cr(a: Float, b: Float, cc: Float, d: Float) =
                        0.5f * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)
                    out += cr(p0.first, p1.first, p2.first, p3.first) to cr(p0.second, p1.second, p2.second, p3.second)
                }
            }
            out += ctrl.last()
            return out
        }

        private fun arc(a: List<Float>): List<Pair<Float, Float>> {
            require(a.size == 6) { "Arco: cx,cy,rx,ry,a0,a1" }
            val (cx, cy, rx, ry) = a
            val a0 = a[4]
            val a1 = a[5]
            val n = maxOf(2, ceil(abs(a1 - a0) / 6f).toInt())
            return (0..n).map { i ->
                val ang = (a0 + (a1 - a0) * i / n) * PI.toFloat() / 180f
                (cx + rx * cos(ang)) to (cy + ry * sin(ang))
            }
        }

        /** Subdivide los tramos largos para que ningún par de puntos quede a más de [step]. */
        private fun densify(pts: List<Pair<Float, Float>>, step: Float): List<Pair<Float, Float>> {
            if (pts.size < 2) return pts
            val out = mutableListOf(pts[0])
            for (i in 1 until pts.size) {
                val (ax, ay) = pts[i - 1]
                val (bx, by) = pts[i]
                val n = maxOf(1, ceil(hypot(bx - ax, by - ay) / step).toInt())
                for (k in 1..n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
            }
            return out
        }

        private fun round2(v: Float) = (v * 100f).roundToInt() / 100f

        val SOURCES: String get() = NUMBERS + OPERATORS + GROUPING + CALCULUS + GREEK + SETS + ARROWS

        private const val NUMBERS = """
@ 0 | 0 | N | H | 1 | on
o O 30,50,27,49,282,-70
g O 30,50,27,49,282,-70
g O 30,50,27,49,250,-105
g O 30,50,27,49,262,620

@ 1 | 1 | N | H | 1 | on
o L 10,24 32,1 31,100

@ 2 | 2 | N | H | 1 | on
o S 5,26 14,8 32,0 48,8 52,26 44,48 4,98* 58,98
g S 5,26 14,8 32,0 48,8 52,26 44,48 4,98* 58,98

@ 3 | 3 | N | H | 1 | on
o S 6,14 24,1 44,4 50,20 42,36 24,46* 44,50 54,66 52,84 38,98 18,100 4,90

@ 4 | 4 | N | H | 1 | on
o S 34,0* 2,66* 60,64 / L 46,30 45,100
g L 34,0 2,66 60,64 / L 46,30 45,100
g L 44,100 44,0 2,66 60,66

@ 5 | 5 | N | H | 1 | on
o S 14,2* 10,46* 26,38 44,40 56,56 56,78 44,96 24,100 4,92 / L 14,2 54,1
g S 14,2* 10,46* 26,38 44,40 56,56 56,78 44,96 24,100 4,92 / L 14,2 54,1
g S 54,1* 14,2* 10,46* 26,38 44,40 56,56 56,78 44,96 24,100 4,92

@ 6 | 6 | N | H | 1 | on
o S 48,6 30,10 14,32 8,60 12,86 28,100 46,96 54,78 48,60 32,54 16,60 9,74

@ 7 | 7 | N | H | 1 | on
o S 2,2* 56,0* 38,40 26,100
g S 2,2* 56,0* 38,40 26,100
g S 2,2* 56,0* 38,40 26,100 / L 22,52 52,52

@ 8 | 8 | N | H | 1 | on
o S 50,16 38,2 20,2 8,16 14,34 34,48 50,62 54,82 42,98 20,100 6,86 10,66 28,50 46,34 50,16
g S 50,16 38,2 20,2 8,16 14,34 34,48 50,62 54,82 42,98 20,100 6,86 10,66 28,50 46,34 50,16
g S 30,48 8,30 15,5 30,0 48,8 50,28 30,48 5,70 12,95 32,100 52,90 55,70 30,48

@ 9 | 9 | N | H | 1 | on
o S 50,14 34,1 14,6 8,26 20,44 40,42 52,24* 50,56 46,100
g S 50,14 34,1 14,6 8,26 20,44 40,42 52,24* 50,56 46,100
g S 50,30 30,45 10,30 15,5 40,0 52,20 48,100

@ 10 | 10 | N | H | 1 | on
o L 4,22 22,2 22,100 / O 66,50,24,49,282,-70
"""

        private const val OPERATORS = """
@ mas | Más | O | H | 1 | on
o L 2,50 98,50 / L 50,2 50,98

@ menos | Menos | O | W | 1 | off
o S 0,50 50,49 100,50

@ por | Por | O | H | 1 | off
o L 10,10 90,90 / L 90,10 10,90

@ dividido | Dividido | O | W | 1 | on
o D 50,16 / L 4,50 96,50 / D 50,84

@ masmenos | Más o menos | O | H | 1 | on
o L 50,4 50,68 / L 10,36 90,36 / L 10,96 90,96

@ igual | Igual | O | W | 1 | on
o L 6,36 94,36 / L 6,64 94,64

@ distinto | Distinto | O | W | 1 | on
o L 6,38 94,38 / L 6,64 94,64 / L 68,6 32,96

@ aprox | Aproximado | O | W | 1 | on
o S 4,42 18,30 34,30 50,42 66,54 82,54 96,42 / S 4,72 18,60 34,60 50,72 66,84 82,84 96,72

@ equiv | Equivalente | O | W | 1 | on
o L 6,20 94,20 / L 6,50 94,50 / L 6,80 94,80

@ menor | Menor | O | H | 1 | on
o L 86,4 8,50 86,96

@ mayor | Mayor | O | H | 1 | on
o L 14,4 92,50 14,96

@ menorigual | Menor o igual | O | H | 1 | on
o L 86,2 8,40 86,78 / L 8,98 86,98

@ mayorigual | Mayor o igual | O | H | 1 | on
o L 14,2 92,40 14,78 / L 14,98 92,98

@ porcentaje | Porcentaje | O | H | 1 | on
o O 22,20,13,18,270,-90 / L 84,2 16,98 / O 78,80,13,18,270,-90

@ factorial | Factorial | O | H | 1 | on
o L 50,2 48,72 / D 48,96

@ barra | Barra | O | H | 1 | off
o L 80,2 20,98
"""

        private const val GROUPING = """
@ parentesis-abre | Abre paréntesis | G | H | 1 | on
o S 34,0 16,22 9,50 16,78 34,100

@ parentesis-cierra | Cierra paréntesis | G | H | 1 | on
o S 6,0 24,22 31,50 24,78 6,100

@ corchete-abre | Abre corchete | G | H | 1 | on
o L 30,0 8,0 8,100 30,100

@ corchete-cierra | Cierra corchete | G | H | 1 | on
o L 6,0 28,0 28,100 6,100

@ llave-abre | Abre llave | G | H | 1 | on
o S 38,0 26,4 21,16 22,36 18,46 6,50* 18,54 22,64 21,84 26,96 38,100

@ llave-cierra | Cierra llave | G | H | 1 | on
o S 2,0 14,4 19,16 18,36 22,46 34,50* 22,54 18,64 19,84 14,96 2,100

@ valor-absoluto | Valor absoluto | G | H | 1 | off
o L 20,0 20,100
"""

        private const val CALCULUS = """
@ sumatoria | Sumatoria | C | H | 1 | on
o L 88,10 86,0 6,0 50,50 6,100 88,100 90,90

@ productoria | Productoria | C | H | 1 | on
o L 0,4 84,4 / L 16,4 16,100 / L 68,4 68,100

@ parcial | Derivada parcial | C | H | 1 | on
o S 14,20 28,4 46,2 60,18 64,48 56,80 38,98 18,96 8,80 14,62 32,54 50,58 62,70

@ nabla | Nabla | C | H | 1 | on
o L 2,2 98,2 50,98 2,2

@ infinito | Infinito | C | W | 1 | on
o S 50,50 60,34 76,25 92,32 98,50 92,68 76,75 60,66 50,50 40,34 24,25 8,32 2,50 8,68 24,75 40,66 50,50
g S 50,50 60,34 76,25 92,32 98,50 92,68 76,75 60,66 50,50 40,34 24,25 8,32 2,50 8,68 24,75 40,66 50,50
g S 50,50 60,66 76,75 92,68 98,50 92,32 76,25 60,34 50,50 40,66 24,75 8,68 2,50 8,32 24,25 40,34 50,50
g S 2,50 8,32 24,25 40,34 50,50 60,66 76,75 92,68 98,50 92,32 76,25 60,34 50,50 40,66 24,75 8,68 2,50

@ integral-linea | Integral de línea | C | H | 1.1 | on
o S 66,6 60,0 52,1 47,9 45,24 44,50 42,76 39,91 32,99 24,98 20,92 / O 44,50,12,11,270,-90
g S 80,2 64,2 51,11 47,25 49,44 45,60 35,80 20,96 7,95 / O 46,50,12,11,270,-90

@ integral-doble | Integral doble | C | H | 1.1 | on
o S 56,6 50,0 42,1 37,9 35,24 34,50 32,76 29,91 22,99 14,98 10,92 / S 86,6 80,0 72,1 67,9 65,24 64,50 62,76 59,91 52,99 44,98 40,92
g S 60,2 48,2 39,11 36,25 37,44 34,60 26,80 15,96 5,95 / S 92,2 80,2 71,11 68,25 69,44 66,60 58,80 47,96 37,95
"""

        private const val GREEK = """
@ alfa | alfa | K | H | 1 | on
o S 72,42 60,66 44,90 28,100 14,92 10,72 18,52 34,42 50,48 62,70 72,94 82,100

@ beta | beta | K | H | 1 | on
o S 16,135 16,40 20,16 32,2 48,0 60,12 58,30 44,42 32,46* 52,52 64,68 62,88 48,100 30,100 16,90

@ gamma | gamma | K | H | 1 | on
o S 2,50 10,42 20,46 30,62 42,94 46,118 40,134 33,126 37,106 54,70 68,42

@ delta | delta | K | H | 1 | on
o S 58,10 44,1 28,2 22,14 30,26 52,40 64,60 62,84 48,98 30,100 14,88 12,68 24,50 44,40

@ Delta | Delta | K | H | 1 | on
o L 50,0 98,98 2,98 50,0

@ epsilon | épsilon | K | H | 1 | on
o S 62,48 48,40 30,42 22,54 34,68* 20,74 14,88 26,99 46,100 62,92

@ eta | eta | K | H | 1 | on
o S 6,48 14,40 20,52 20,100* 21,70 34,46 50,40 62,50 64,72 64,135

@ theta | theta | K | H | 1 | on
o O 40,50,26,50,270,-90 / L 15,50 65,50

@ lambda | lambda | K | H | 1 | on
o S 8,0 20,4 30,24 68,100 / L 42,46 6,100

@ mu | mu | K | H | 1 | on
o L 16,40 14,135 / S 16,44 18,84 28,98 44,100 58,90 64,70 64,40* 64,88 72,100 82,96
g L 16,40 14,135 / S 16,44 18,84 28,98 44,100 58,90 64,70 64,40* 64,88 72,100 82,96
g S 14,135* 16,40* 18,84 28,98 44,100 58,90 64,70 64,40* 64,88 72,100 82,96

@ pi | pi | K | H | 1 | on
o S 2,52 8,42 20,40 96,40 / S 30,40 28,76 22,100 / S 70,40 68,88 76,100 88,96

@ rho | rho | K | H | 1 | on
o S 18,135 18,76 24,54 40,42 56,44 66,60 62,84 46,98 28,98 18,86

@ sigma | sigma | K | H | 1 | on
o S 96,40 54,40 30,46 16,64 18,88 36,100 54,96 66,78 64,56 52,42
g S 96,40 54,40 30,46 16,64 18,88 36,100 54,96 66,78 64,56 52,42
g S 52,42 30,46 16,64 18,88 36,100 54,96 66,78 64,56 52,42* 96,40

@ tau | tau | K | H | 1 | on
o S 4,52 10,42 22,40 96,40 / S 52,40 48,82 52,98 64,98

@ fi | fi | K | H | 1 | on
o O 50,72,30,28,270,-90 / L 52,28 48,135

@ psi | psi | K | H | 1 | on
o S 4,44 10,40 14,52 14,72 26,92 50,98 74,92 86,72 88,40 / L 50,20 48,135

@ omega | omega | K | H | 1 | on
o S 20,40 8,60 10,86 24,100 40,96 48,74* 56,96 72,100 86,86 88,60 76,40

@ Omega | Omega | K | H | 1 | on
o S 4,100* 34,100* 34,92 20,76 12,50 20,20 40,2 60,2 80,20 88,50 80,76 66,92 66,100* 96,100
"""

        private const val SETS = """
@ pertenece | Pertenece | S | H | 1 | on
o S 90,4 64,0 36,4 16,22 10,50 16,78 36,96 64,100 90,96 / L 12,50 78,50

@ no-pertenece | No pertenece | S | H | 1 | on
o S 90,4 64,0 36,4 16,22 10,50 16,78 36,96 64,100 90,96 / L 12,50 78,50 / L 74,0 30,100

@ subconjunto | Subconjunto | S | H | 1 | on
o S 94,4 46,2 20,12 8,30 6,50 8,70 20,88 46,98 94,96

@ superconjunto | Superconjunto | S | H | 1 | on
o S 6,4 54,2 80,12 92,30 94,50 92,70 80,88 54,98 6,96

@ subconjunto-igual | Subconjunto o igual | S | H | 1 | on
o S 94,4 46,2 20,10 8,24 6,38 8,52 20,66 46,74 94,72 / L 8,98 94,98

@ superconjunto-igual | Superconjunto o igual | S | H | 1 | on
o S 6,4 54,2 80,10 92,24 94,38 92,52 80,66 54,74 6,72 / L 6,98 92,98

@ union | Unión | S | H | 1 | on
o S 8,0 8,54 16,82 34,98 50,100 66,98 84,82 92,54 92,0

@ interseccion | Intersección | S | H | 1 | on
o S 8,100 8,46 16,18 34,2 50,0 66,2 84,18 92,46 92,100

@ vacio | Vacío | S | H | 1 | on
o O 50,52,36,42,270,-90 / L 90,0 10,100

@ para-todo | Para todo | S | H | 1 | on
o L 4,0 50,100 96,0 / L 22,44 78,44

@ existe | Existe | S | H | 1 | on
o L 8,2 90,2 90,98 8,98 / L 26,50 90,50
g L 8,2 90,2 90,98 8,98 / L 26,50 90,50
g L 8,2 90,2 90,50 26,50 90,50 90,98 8,98

@ no | No | S | W | 1 | on
o L 4,30 92,30 92,72

@ y | Y lógico | S | H | 1 | on
o L 4,100 50,2 96,100

@ o | O lógico | S | H | 1 | on
o L 4,0 50,98 96,0

@ reales | Reales | S | H | 1 | on
o L 12,100 12,0 / S 12,2* 50,2 68,10 74,26 66,42 48,50 14,50* 76,100 / L 24,4 24,100
g L 12,100 12,0 / S 12,2* 50,2 68,10 74,26 66,42 48,50 14,50* 76,100 / L 24,4 24,100
g L 12,100 12,0 / S 12,2* 50,2 68,10 74,26 66,42 48,50 14,50* 76,100

@ naturales | Naturales | S | H | 1 | on
o L 10,100 10,0 82,100 82,0 / L 22,17 22,100
g L 10,100 10,0 82,100 82,0 / L 22,17 22,100
g L 10,100 10,0 82,100 82,0

@ enteros | Enteros | S | H | 1 | on
o L 8,2 92,2 12,98 / L 80,2 0,98 96,98
g L 8,2 92,2 12,98 / L 80,2 0,98 96,98
g L 8,2 92,2 4,98 96,98

@ racionales | Racionales | S | H | 1 | on
o O 46,50,40,48,270,-90 / L 56,74 88,102 / S 25,22 19,50 25,78
g O 46,50,40,48,270,-90 / L 56,74 88,102 / S 25,22 19,50 25,78
g O 46,50,40,48,270,-90 / L 56,74 88,102

@ complejos | Complejos | S | H | 1 | on
o S 88,14 72,2 50,0 26,8 10,30 6,50 10,70 26,92 50,100 72,98 88,86 / S 29,18 22,50 29,82
"""

        private const val ARROWS = """
@ flecha-derecha | Flecha a la derecha | A | H | 1 | on | h0-0.7
o L 0,50 100,50 / L 76,28 100,50 76,72
g L 0,50 100,50 / L 76,28 100,50 76,72
g L 0,50 100,50 76,28 100,50 76,72

@ flecha-izquierda | Flecha a la izquierda | A | H | 1 | on | h0.3-1
o L 100,50 0,50 / L 24,28 0,50 24,72
g L 100,50 0,50 / L 24,28 0,50 24,72
g L 100,50 0,50 24,28 0,50 24,72

@ flecha-doble | Flecha doble | A | H | 1 | on | h0.25-0.75
o L 0,50 100,50 / L 22,30 0,50 22,70 / L 78,30 100,50 78,70

@ implica | Implica | A | H | 1 | on | h0-0.66
o L 0,36 82,36 / L 0,64 82,64 / L 70,14 100,50 70,86

@ si-solo-si | Si y solo si | A | H | 1 | on | h0.3-0.7
o L 18,36 82,36 / L 18,64 82,64 / L 30,14 0,50 30,86 / L 70,14 100,50 70,86

@ flecha-arriba | Flecha hacia arriba | A | W | 1 | on | v0.3-1
o L 50,100 50,0 / L 28,24 50,0 72,24

@ flecha-abajo | Flecha hacia abajo | A | W | 1 | on | v0-0.7
o L 50,0 50,100 / L 28,76 50,100 72,76

@ asigna | Asigna | A | H | 1 | on | h0.05-0.7
o L 0,30 0,70 / L 0,50 100,50 / L 78,30 100,50 78,70
g L 0,30 0,70 / L 0,50 100,50 78,30 100,50 78,70

@ angulo | Ángulo | A | H | 1 | on
o L 96,4 6,98 98,98

@ perpendicular | Perpendicular | A | H | 1 | off
o L 50,0 50,98 / L 4,98 96,98

@ paralelo | Paralelo | A | H | 1 | off
o L 34,0 34,100 / L 66,0 66,100

@ grado | Grado | A | H | 1 | off
o O 50,50,20,20,270,-90
"""
    }
}
