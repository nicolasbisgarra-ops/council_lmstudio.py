package io.trazo.core.math

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Modo gráfica: compila funciones de x escritas a mano o con el teclado («2x^2 - sin(x)/3», «y = e^-x»)
 * a evaluadores rápidos en double y las muestrea en un intervalo, cortando el trazo donde la función
 * no existe o salta (asíntotas). No usa el motor exacto: para dibujar alcanza con decimales y así se
 * pueden evaluar miles de puntos por cuadro.
 */
object FunctionPlot {

    /** Una función lista para evaluar. [source] es el texto tal como lo escribió el usuario. */
    class Fn internal constructor(val source: String, private val f: (Double) -> Double) {
        operator fun invoke(x: Double): Double = f(x)
    }

    /** Resultado de compilar: la función o un mensaje corto para mostrar junto al renglón. */
    sealed interface Compiled {
        data class Ok(val fn: Fn) : Compiled
        data class Error(val message: String) : Compiled
    }

    /**
     * Compila [text]. Las letras que no son x, e, π ni nombres de función se toman como parámetros
     * («y = a·x² + b»): valen lo que diga [params] y 1 si no figuran ahí.
     */
    fun compile(text: String, params: Map<String, Double> = emptyMap()): Compiled {
        val body = stripLeft(text.trim())
        if (body.isBlank()) return Compiled.Error("Escriba una función de x")
        return try {
            val p = P(tokens(body), params)
            val e = p.expr()
            if (!p.done()) throw Bad("sobra «${p.rest()}»")
            Compiled.Ok(Fn(text.trim(), e))
        } catch (b: Bad) {
            Compiled.Error("No se entiende: ${b.message}")
        }
    }

    /** Los parámetros de [text] («a·x² + b» → [a, b]): letras libres, sin repetir y en orden de aparición. */
    fun parameters(text: String): List<String> {
        val body = stripLeft(text.trim())
        if (body.isBlank()) return emptyList()
        return try {
            tokens(body)
                .filter { it.k == K.ID && it.s != "x" && it.s !in CONSTS && it.s !in FUNS }
                .map { it.s }
                .distinct()
        } catch (b: Bad) {
            emptyList()
        }
    }

    /** Saca un «y =», «f(x) =» del principio. */
    private fun stripLeft(s: String): String {
        val eq = s.indexOf('=')
        if (eq < 0) return s
        val left = s.substring(0, eq).replace(" ", "")
        return if (left == "y" || Regex("[a-zA-Z]\\(x\\)").matches(left)) s.substring(eq + 1) else s
    }

    /** Tramos continuos de la curva en [a, b] con [samples] muestras. Cada tramo es (x0, y0, x1, y1, …). */
    fun sample(fn: Fn, a: Double, b: Double, samples: Int, yMin: Double, yMax: Double): List<FloatArray> {
        val n = samples.coerceIn(2, 20_000)
        val out = ArrayList<FloatArray>()
        val span = (yMax - yMin).coerceAtLeast(1e-12)
        var cur = FloatArrayBuilder()
        var prevY = Double.NaN
        for (i in 0..n) {
            val x = a + (b - a) * i / n
            val y = fn(x)
            val ok = y.isFinite() && abs(y) < 1e12
            // Un salto de más de dos alturas de pantalla entre muestras vecinas es una asíntota: se corta.
            val jump = ok && prevY.isFinite() && abs(y - prevY) > 2 * span
            if (!ok || jump) {
                if (cur.size >= 4) out += cur.build()
                cur = FloatArrayBuilder()
            }
            if (ok) cur.add(x.toFloat(), y.toFloat())
            prevY = if (ok) y else Double.NaN
        }
        if (cur.size >= 4) out += cur.build()
        return out
    }

    /** Paso «lindo» (1, 2 o 5 por una potencia de 10) para que entren unas [target] marcas en [span]. */
    fun niceStep(span: Double, target: Int = 8): Double {
        if (!(span > 0) || !span.isFinite()) return 1.0
        val raw = span / target.coerceAtLeast(1)
        val p = 10.0.pow(floor(log10(raw)))
        val m = raw / p
        return p * when { m < 1.5 -> 1.0; m < 3.5 -> 2.0; m < 7.5 -> 5.0; else -> 10.0 }
    }

    /** Las marcas múltiplos de [step] dentro de [a, b]. */
    fun ticks(a: Double, b: Double, step: Double): List<Double> {
        if (!(step > 0) || b <= a) return emptyList()
        val first = ceil(a / step) * step
        val n = ((b - first) / step).toInt().coerceAtMost(500)
        return (0..n).map { first + it * step }.map { if (abs(it) < step * 1e-9) 0.0 else it }
    }

    /** Rango de y que muestra bien las funciones en [a, b] (percentiles, para que una asíntota no lo aplaste). */
    fun fitY(fns: List<Fn>, a: Double, b: Double): Pair<Double, Double> {
        val ys = ArrayList<Double>()
        for (fn in fns) for (i in 0..200) {
            val y = fn(a + (b - a) * i / 200)
            if (y.isFinite() && abs(y) < 1e9) ys += y
        }
        if (ys.isEmpty()) return -5.0 to 5.0
        ys.sort()
        var lo = ys[(ys.size * 0.05).toInt()]
        var hi = ys[((ys.size - 1) * 0.95).toInt()]
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.15
        return (lo - pad) to (hi + pad)
    }

    // ---- puntos notables ----

    enum class Kind { ROOT, MIN, MAX, Y_INTERCEPT, INTERSECTION }

    /**
     * Un punto notable de la curva [fn] (índice en la lista dada). En una intersección, [other] es el índice
     * de la otra función; si no, vale -1.
     */
    data class Notable(val kind: Kind, val x: Double, val y: Double, val fn: Int, val other: Int = -1)

    private const val NOTABLE_SAMPLES = 800
    private const val NOTABLE_MAX = 40

    /**
     * Raíces, mínimos y máximos locales, corte con el eje y e intersecciones entre pares de [fns] en [a, b].
     * Las asíntotas y los huecos del dominio no cuentan (1/x no tiene raíz). Devuelve a lo sumo 40 puntos,
     * los más cercanos al centro del intervalo.
     */
    fun notablePoints(fns: List<Fn>, a: Double, b: Double): List<Notable> {
        if (fns.isEmpty() || !a.isFinite() || !b.isFinite() || !(b > a)) return emptyList()
        val n = NOTABLE_SAMPLES
        val xs = DoubleArray(n + 1) { a + (b - a) * it / n }
        val ys = fns.map { f -> DoubleArray(n + 1) { k -> clean(f(xs[k])) } }
        val out = ArrayList<Notable>()
        for (i in fns.indices) {
            val f = fns[i]
            val fx = { x: Double -> f(x) }
            for (r in rootsOf(fx, xs, ys[i])) out += Notable(Kind.ROOT, snap(r), 0.0, i)
            for ((kind, x0) in extremaOf(fx, xs, ys[i])) {
                val x = snap(x0, 1e-7)
                val y = clean(f(x))
                if (!y.isNaN()) out += Notable(kind, x, snap(y), i)
            }
            if (a <= 0.0 && 0.0 <= b) {
                val y = clean(f(0.0))
                if (!y.isNaN()) out += Notable(Kind.Y_INTERCEPT, 0.0, snap(y), i)
            }
        }
        for (i in fns.indices) for (j in i + 1 until fns.size) {
            val fi = fns[i]
            val fj = fns[j]
            val gy = DoubleArray(n + 1) { k -> ys[i][k] - ys[j][k] }
            val g = { x: Double -> fi(x) - fj(x) }
            for (r in rootsOf(g, xs, gy)) {
                val x = snap(r)
                val y = clean(fi(x))
                if (!y.isNaN()) out += Notable(Kind.INTERSECTION, x, snap(y), i, j)
            }
        }
        val mid = (a + b) / 2
        return out.sortedBy { abs(it.x - mid) }.take(NOTABLE_MAX)
    }

    /** NaN si [y] no sirve para dibujar (infinito, NaN o enorme). */
    private fun clean(y: Double): Double = if (y.isFinite() && abs(y) < 1e12) y else Double.NaN

    /** Redondea a entero si está a menos de [tol] (y saca el -0). */
    private fun snap(v: Double, tol: Double = 1e-9): Double {
        val r = round(v)
        return if (abs(v - r) < tol) r + 0.0 else v + 0.0
    }

    /**
     * Ceros de [f] dados sus valores [ys] en las abscisas [xs] (NaN = no definida): cambios de signo
     * refinados por bisección, muestras que caen justo en cero y toques tangentes (mínimo local de |f| casi 0).
     */
    private fun rootsOf(f: (Double) -> Double, xs: DoubleArray, ys: DoubleArray): List<Double> {
        val out = ArrayList<Double>()
        // Función nula (o sin dominio): no hay raíces que marcar.
        if (ys.all { it.isNaN() || it == 0.0 }) return out
        val last = xs.size - 1
        for (i in 0..last) {
            val y0 = ys[i]
            if (y0.isNaN()) continue
            if (y0 == 0.0) {
                if (i == 0 || ys[i - 1] != 0.0) out += xs[i]
                continue
            }
            if (i < last) {
                val y1 = ys[i + 1]
                if (!y1.isNaN() && y1 != 0.0 && (y0 < 0) != (y1 < 0)) {
                    val r = bisect(f, xs[i], xs[i + 1], y0, y1)
                    if (r != null) out += r
                }
            }
            if (i in 1 until last) {
                val ym = ys[i - 1]
                val yp = ys[i + 1]
                val sameSide = !ym.isNaN() && !yp.isNaN() && ym != 0.0 && yp != 0.0 &&
                    (ym < 0) == (y0 < 0) && (yp < 0) == (y0 < 0)
                if (sameSide && abs(y0) <= abs(ym) && abs(y0) < abs(yp)) {
                    val m = golden({ t -> abs(f(t)) }, xs[i - 1], xs[i + 1])
                    if (abs(f(m)) < 1e-9) out += snap(m, 1e-7)
                }
            }
        }
        return out
    }

    /**
     * Bisección en [lo0, hi0] donde [flo0] y [fhi0] tienen distinto signo. Devuelve null si lo que había era un
     * salto o una asíntota: en una raíz de verdad |f| termina casi en 0, en un polo termina enorme.
     */
    private fun bisect(f: (Double) -> Double, lo0: Double, hi0: Double, flo0: Double, fhi0: Double): Double? {
        var lo = lo0
        var hi = hi0
        var flo = flo0
        for (k in 0 until 200) {
            val mid = (lo + hi) / 2
            if (mid <= lo || mid >= hi) break
            val fm = f(mid)
            if (!fm.isFinite()) return null
            if (fm == 0.0) { lo = mid; hi = mid; break }
            if ((fm < 0) == (flo < 0)) { lo = mid; flo = fm } else { hi = mid }
        }
        val x = (lo + hi) / 2
        val fr = f(x)
        if (!fr.isFinite()) return null
        if (abs(fr) > maxOf(1e-6, 1e-3 * minOf(abs(flo0), abs(fhi0)))) return null
        return x
    }

    /** Extremos locales (cambio de signo de la pendiente entre muestras) refinados con sección dorada. */
    private fun extremaOf(f: (Double) -> Double, xs: DoubleArray, ys: DoubleArray): List<Pair<Kind, Double>> {
        val out = ArrayList<Pair<Kind, Double>>()
        for (i in 1 until xs.size - 1) {
            val y0 = ys[i - 1]
            val y1 = ys[i]
            val y2 = ys[i + 1]
            if (y0.isNaN() || y1.isNaN() || y2.isNaN()) continue
            val dl = y1 - y0
            val dr = y2 - y1
            val isMin = dl < 0 && dr >= 0
            val isMax = dl > 0 && dr <= 0
            if (!isMin && !isMax) continue
            // Ruido de redondeo en una función constante no es un extremo.
            if (maxOf(abs(dl), abs(dr)) <= 1e-12 * maxOf(1.0, abs(y1))) continue
            val sgn = if (isMax) -1.0 else 1.0
            val x = golden({ t -> sgn * f(t) }, xs[i - 1], xs[i + 1])
            val fx = f(x)
            if (!fx.isFinite() || abs(fx) >= 1e12) continue
            val tol = 1e-9 * maxOf(1.0, abs(y1))
            if ((isMin && fx > y1 + tol) || (isMax && fx < y1 - tol)) continue
            // Una asíntota con salto de signo parece un extremo: ahí f cambia enormemente en un paso mínimo.
            val eta = 1e-6 * (xs[i + 1] - xs[i - 1])
            val slack = 0.1 * abs(fx) + 1e-3 * (maxOf(y0, y1, y2) - minOf(y0, y1, y2))
            if (!(abs(fx - f(x - eta)) <= slack) || !(abs(fx - f(x + eta)) <= slack)) continue
            out += (if (isMax) Kind.MAX else Kind.MIN) to x
        }
        return out
    }

    /** Mínimo de [f] en [lo0, hi0] por sección dorada (supone una sola cuenca en el tramo). */
    private fun golden(f: (Double) -> Double, lo0: Double, hi0: Double): Double {
        val g = (sqrt(5.0) - 1) / 2
        var lo = lo0
        var hi = hi0
        var c = hi - g * (hi - lo)
        var d = lo + g * (hi - lo)
        var fc = f(c)
        var fd = f(d)
        for (k in 0 until 100) {
            if (fc < fd) {
                hi = d; d = c; fd = fc
                c = hi - g * (hi - lo); fc = f(c)
            } else {
                lo = c; c = d; fc = fd
                d = lo + g * (hi - lo); fd = f(d)
            }
        }
        return (lo + hi) / 2
    }

    // ---- compilador ----

    private class Bad(msg: String) : Exception(msg)

    private enum class K { NUM, ID, OP, LP, RP, END }
    private data class T(val k: K, val s: String, val v: Double = 0.0)

    private val SUPER_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private val SUPER_RUN = Regex("⁻?[⁰¹²³⁴⁵⁶⁷⁸⁹]+")

    private fun tokens(src: String): List<T> {
        // «x²» → «x^2», «x¹⁰» → «x^10».
        val sup = SUPER_RUN.replace(src) { m ->
            val v = m.value
            if (v.startsWith('⁻')) "^-" + v.substring(1).map { SUPER_DIGITS.indexOf(it) }.joinToString("")
            else "^" + v.map { SUPER_DIGITS.indexOf(it) }.joinToString("")
        }
        val s = sup.replace('·', '*').replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("π", "pi").replace("√", "sqrt").replace('[', '(').replace(']', ')').replace(',', '.')
        val out = ArrayList<T>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    out += T(K.NUM, s.substring(st, i), s.substring(st, i).toDoubleOrNull() ?: throw Bad(s.substring(st, i)))
                }
                c.isLetter() -> {
                    val st = i
                    while (i < s.length && s[i].isLetter()) i++
                    val word = s.substring(st, i)
                    val parts = splitWord(word)
                    // «foo(x)»: una palabra de varias letras sin ningún nombre conocido y seguida de «(» es una
                    // función desconocida, no un producto de parámetros.
                    var nx = i
                    while (nx < s.length && s[nx].isWhitespace()) nx++
                    if (word.length >= 2 && nx < s.length && s[nx] == '(' && parts.none { it.s in NAMES }) {
                        throw Bad("«$word»")
                    }
                    out += parts
                }
                c in "+-*/^" -> { out += T(K.OP, c.toString()); i++ }
                c == '(' -> { out += T(K.LP, "("); i++ }
                c == ')' -> { out += T(K.RP, ")"); i++ }
                c == '|' -> { out += T(K.OP, "|"); i++ }
                else -> throw Bad("«$c»")
            }
        }
        out += T(K.END, "")
        return out
    }

    /**
     * «xsin» → x, sin; «pix» → pi, x: se parte la palabra en nombres conocidos de izquierda a derecha.
     * Las letras que no empiezan ningún nombre son parámetros, de a una: «ax» → a, x.
     */
    private fun splitWord(w: String): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (i < w.length) {
            val name = NAMES.firstOrNull { w.startsWith(it, i) }
            if (name != null) {
                out += T(K.ID, name)
                i += name.length
            } else {
                out += T(K.ID, w[i].toString())
                i++
            }
        }
        return out
    }

    private val FUNS: Map<String, (Double) -> Double> = mapOf(
        "sin" to { x -> kotlin.math.sin(x) }, "sen" to { x -> kotlin.math.sin(x) },
        "cos" to { x -> kotlin.math.cos(x) }, "tan" to { x -> kotlin.math.tan(x) }, "tg" to { x -> kotlin.math.tan(x) },
        "asin" to { x -> kotlin.math.asin(x) }, "acos" to { x -> kotlin.math.acos(x) }, "atan" to { x -> kotlin.math.atan(x) },
        "arcsin" to { x -> kotlin.math.asin(x) }, "arccos" to { x -> kotlin.math.acos(x) }, "arctan" to { x -> kotlin.math.atan(x) },
        "sinh" to { x -> kotlin.math.sinh(x) }, "cosh" to { x -> kotlin.math.cosh(x) }, "tanh" to { x -> kotlin.math.tanh(x) },
        "exp" to { x -> kotlin.math.exp(x) }, "ln" to { x -> kotlin.math.ln(x) }, "log" to { x -> kotlin.math.log10(x) },
        "sqrt" to { x -> kotlin.math.sqrt(x) }, "abs" to { x -> kotlin.math.abs(x) }, "cbrt" to { x -> kotlin.math.cbrt(x) },
        "sec" to { x -> 1 / kotlin.math.cos(x) }, "csc" to { x -> 1 / kotlin.math.sin(x) }, "cot" to { x -> 1 / kotlin.math.tan(x) },
    )
    private val CONSTS = mapOf("pi" to Math.PI, "e" to Math.E)

    /** Más largos primero, para que «sinh» gane a «sin» y «exp» a «e». */
    private val NAMES = (FUNS.keys + CONSTS.keys + "x").sortedByDescending { it.length }

    private class P(val t: List<T>, val params: Map<String, Double> = emptyMap()) {
        var i = 0
        fun peek() = t[i]
        fun done() = peek().k == K.END
        fun rest() = t.drop(i).joinToString("") { it.s }

        fun expr(): (Double) -> Double {
            var l = term()
            while (peek().k == K.OP && peek().s in "+-") {
                val op = t[i++].s
                val a = l; val b = term()
                l = if (op == "+") { x -> a(x) + b(x) } else { x -> a(x) - b(x) }
            }
            return l
        }

        fun term(): (Double) -> Double {
            var l = unary()
            while (true) {
                val p = peek()
                l = if (p.k == K.OP && p.s in "*/") {
                    i++
                    val a = l; val b = unary()
                    if (p.s == "*") { x -> a(x) * b(x) } else { x -> a(x) / b(x) }
                } else if (p.k == K.NUM || p.k == K.ID || p.k == K.LP) {
                    // Multiplicación implícita: «2x», «x(x+1)», «3sin x».
                    val a = l
                    val b = power()
                    val m: (Double) -> Double = { x -> a(x) * b(x) }
                    m
                } else return l
            }
        }

        fun unary(): (Double) -> Double {
            val p = peek()
            if (p.k == K.OP && p.s == "-") { i++; val a = unary(); return { x -> -a(x) } }
            if (p.k == K.OP && p.s == "+") { i++; return unary() }
            return power()
        }

        fun power(): (Double) -> Double {
            val base = primary()
            if (peek().k == K.OP && peek().s == "^") {
                i++
                val e = unary() // derecha asociativa: 2^3^2 = 2^9; admite «e^-x».
                return { x -> base(x).pow(e(x)) }
            }
            return base
        }

        fun primary(): (Double) -> Double {
            val p = t[i++]
            return when (p.k) {
                K.NUM -> { val v = p.v; { v } }
                K.ID -> when {
                    p.s == "x" -> { x -> x }
                    p.s in CONSTS -> { val v = CONSTS.getValue(p.s); { v } }
                    p.s !in FUNS -> { val v = params[p.s] ?: 1.0; { v } } // parámetro: vale lo que diga el mapa.
                    else -> {
                        val f = FUNS.getValue(p.s)
                        // «sin^2 x» = (sin x)^2; «sin^-1 x» = arcsin(x).
                        var pw: ((Double) -> Double)? = null
                        var isMinusOne = false
                        if (peek().k == K.OP && peek().s == "^") {
                            i++
                            pw = unary()
                            isMinusOne = try { pw(0.0) == -1.0 && pw(1.0) == -1.0 } catch (_: Throwable) { false }
                        }
                        val inv = if (isMinusOne) when (p.s) {
                            "sin", "sen" -> FUNS["arcsin"] ?: FUNS.getValue("asin")
                            "cos" -> FUNS["arccos"] ?: FUNS.getValue("acos")
                            "tan", "tg" -> FUNS["arctan"] ?: FUNS.getValue("atan")
                            else -> null
                        } else null
                        val arg = if (peek().k == K.LP) primary() else power()
                        if (inv != null) {
                            { x -> inv(arg(x)) }
                        } else {
                            val g = pw
                            if (g == null) { x -> f(arg(x)) } else { x -> f(arg(x)).pow(g(x)) }
                        }
                    }
                }
                K.LP -> {
                    val e = expr()
                    if (t[i].k != K.RP) throw Bad("falta «)»")
                    i++
                    e
                }
                K.OP -> if (p.s == "|") {
                    val e = expr()
                    if (t[i].s != "|") throw Bad("falta «|»")
                    i++
                    { x -> abs(e(x)) }
                } else throw Bad("«${p.s}» fuera de lugar")
                K.RP -> throw Bad("«)» de más")
                K.END -> throw Bad("está incompleta")
            }
        }
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(64)
        var size = 0
            private set
        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x; a[size++] = y
        }
        fun build(): FloatArray = a.copyOf(size)
    }
}
