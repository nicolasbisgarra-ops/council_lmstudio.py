package io.trazo.core.math

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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

    fun compile(text: String): Compiled {
        val body = stripLeft(text.trim())
        if (body.isBlank()) return Compiled.Error("Escriba una función de x")
        return try {
            val p = P(tokens(body))
            val e = p.expr()
            if (!p.done()) throw Bad("sobra «${p.rest()}»")
            Compiled.Ok(Fn(text.trim(), e))
        } catch (b: Bad) {
            Compiled.Error("No se entiende: ${b.message}")
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

    // ---- compilador ----

    private class Bad(msg: String) : Exception(msg)

    private enum class K { NUM, ID, OP, LP, RP, END }
    private data class T(val k: K, val s: String, val v: Double = 0.0)

    private fun tokens(src: String): List<T> {
        val s = src.replace('·', '*').replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
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
                    out += splitWord(s.substring(st, i))
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

    /** «xsin» → x, sin; «pix» → pi, x: se parte la palabra en nombres conocidos de izquierda a derecha. */
    private fun splitWord(w: String): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (i < w.length) {
            val name = NAMES.firstOrNull { w.startsWith(it, i) } ?: throw Bad("«${w.substring(i)}»")
            out += T(K.ID, name)
            i += name.length
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

    private class P(val t: List<T>) {
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
                    else -> {
                        val f = FUNS.getValue(p.s)
                        // «sin^2 x» = (sin x)^2.
                        var pw: ((Double) -> Double)? = null
                        if (peek().k == K.OP && peek().s == "^") { i++; pw = primary() }
                        val arg = if (peek().k == K.LP) primary() else power()
                        val g = pw
                        if (g == null) { x -> f(arg(x)) } else { x -> f(arg(x)).pow(g(x)) }
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
