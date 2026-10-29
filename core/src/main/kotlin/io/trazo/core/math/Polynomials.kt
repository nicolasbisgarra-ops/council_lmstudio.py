package io.trazo.core.math

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polinomio en una letra con coeficientes racionales: [c] de menor a mayor grado (`c[0] + c[1]·x + …`),
 * sin ceros al final. El polinomio nulo no tiene coeficientes.
 */
class Poly(coefficients: List<Rational>) {
    val c: List<Rational> = coefficients.dropLastWhile { it.isZero }
    val degree: Int get() = c.size - 1
    val isZero: Boolean get() = c.isEmpty()
    val lead: Rational get() = c.last()

    operator fun get(i: Int): Rational = c.getOrElse(i) { Rational.ZERO }

    fun at(x: Rational): Rational = c.foldRight(Rational.ZERO) { a, acc -> acc * x + a }

    operator fun minus(o: Poly) = Poly(List(maxOf(c.size, o.c.size)) { this[it] - o[it] })

    operator fun times(o: Poly): Poly {
        if (isZero || o.isZero) return Poly(emptyList())
        val out = MutableList(c.size + o.c.size - 1) { Rational.ZERO }
        for (i in c.indices) for (j in o.c.indices) out[i + j] = out[i + j] + c[i] * o.c[j]
        return Poly(out)
    }

    /** Cociente y resto de la división larga por [d] (no nulo). */
    fun divide(d: Poly): Pair<Poly, Poly> {
        require(!d.isZero)
        val r = c.toMutableList()
        val q = MutableList(maxOf(0, degree - d.degree + 1)) { Rational.ZERO }
        for (k in degree - d.degree downTo 0) {
            val f = r[k + d.degree] / d.lead
            q[k] = f
            for (j in d.c.indices) r[k + j] = r[k + j] - f * d.c[j]
        }
        return Poly(q) to Poly(r.take(maxOf(0, d.degree)))
    }

    /** El valor con letras: `x² + 2x + 1`. */
    fun toValue(letter: String): MathValue {
        var out: MathValue = ExactValue.ZERO
        val x = SymbolicValue.variable(letter)
        for ((i, a) in c.withIndex()) {
            if (a.isZero) continue
            out = SymbolicValue.add(out, SymbolicValue.multiply(ExactValue.of(a), SymbolicValue.power(x, ExactValue.of(Rational.of(i.toLong())))))
        }
        return out
    }

    override fun equals(other: Any?) = other is Poly && c == other.c
    override fun hashCode() = c.hashCode()
    override fun toString() = c.toString()

    companion object {
        fun x(): Poly = Poly(listOf(Rational.ZERO, Rational.ONE))
        fun constant(a: Rational) = Poly(listOf(a))

        /**
         * El polinomio en [letter] de un valor; null si tiene otras letras, π, decimales o la letra
         * dividiendo. Un número es un polinomio de grado 0.
         */
        fun of(v: MathValue, letter: String): Poly? {
            val terms = when (v) {
                is SymbolicValue, is ExactValue -> SymbolicValue.termsOf(v)
                else -> return null
            }
            val out = mutableMapOf<Int, Rational>()
            for ((m, coef) in terms) {
                if (m.keys.any { it != letter }) return null
                val e = m[letter] ?: 0
                if (e < 0) return null
                out[e] = (coef as? ExactValue)?.rational ?: return null
            }
            val n = (out.keys.maxOrNull() ?: -1) + 1
            return Poly(List(n) { out[it] ?: Rational.ZERO })
        }
    }
}

/** Una solución: `a + b·√m` (con [imaginary], `a + b·√m·i`). Con [m] = 1 y [b] = 0 es un racional. */
sealed interface Root {
    data class Exact(val a: Rational, val b: Rational = Rational.ZERO, val m: BigInteger = BigInteger.ONE, val imaginary: Boolean = false) : Root
    /** Calculada con decimales (grado 3 o más sin raíces racionales). */
    data class Approx(val re: Double, val im: Double = 0.0) : Root
}

val Root.isReal: Boolean get() = when (this) {
    is Root.Exact -> !imaginary || b.isZero
    is Root.Approx -> im == 0.0
}

/** Ecuaciones, factorización y sistemas con polinomios. */
object Polynomials {
    /** Mayor grado que se resuelve. */
    const val MAX_DEGREE = 10
    /** Mayor número cuyos divisores se prueban como raíces racionales. */
    private val MAX_DIVISORS_OF: BigInteger = BigInteger.TEN.pow(12)

    /** Factor de [p]: `(q·x − r)^k` o, si no tiene raíces racionales, el resto (primitivo, con entero). */
    data class Factor(val poly: Poly, val power: Int)

    /**
     * Factoriza con raíces racionales: [p] = constante · Π factores. Los factores tienen coeficientes
     * enteros sin divisor común y coeficiente principal positivo. null si el grado supera [MAX_DEGREE].
     */
    fun factor(p: Poly): Pair<Rational, List<Factor>>? {
        if (p.degree < 1 || p.degree > MAX_DEGREE) return null
        var (k, rest) = primitive(p)
        val out = mutableListOf<Factor>()
        for (r in rationalRoots(rest)) {
            val lin = primitive(Poly(listOf(-Rational.of(r.num), Rational.of(r.den)))).second
            var n = 0
            while (rest.degree >= 1 && rest.at(r).isZero) {
                rest = rest.divide(lin).first
                n++
            }
            if (n > 0) out += Factor(lin, n)
        }
        val (k2, prim) = primitive(rest)
        k *= k2
        if (prim.degree >= 1) out += Factor(prim, 1)
        return k to out
    }

    /** Constante y polinomio entero primitivo con coeficiente principal positivo. */
    private fun primitive(p: Poly): Pair<Rational, Poly> {
        if (p.isZero) return Rational.ONE to p
        val lcm = p.c.fold(BigInteger.ONE) { acc, a -> acc / acc.gcd(a.den) * a.den }
        val ints = p.c.map { (it * Rational.of(lcm)).num }
        var g = ints.fold(BigInteger.ZERO) { acc, a -> acc.gcd(a) }
        if (p.lead.signum < 0) g = g.negate()
        val k = Rational.of(g, lcm)
        return k to Poly(ints.map { Rational.of(it, g) })
    }

    /** Raíces racionales distintas de [p] (de menor a mayor), por el teorema de las raíces racionales. */
    fun rationalRoots(p: Poly): List<Rational> {
        if (p.degree < 1) return emptyList()
        val (_, prim) = primitive(p)
        val out = mutableListOf<Rational>()
        var q = prim
        if (q[0].isZero) {
            out += Rational.ZERO
            while (q.degree >= 1 && q[0].isZero) q = Poly(q.c.drop(1))
        }
        if (q.degree < 1) return out
        val a0 = q[0].num.abs()
        val an = q.lead.num.abs()
        if (a0 > MAX_DIVISORS_OF || an > MAX_DIVISORS_OF) return out
        for (num in divisors(a0)) for (den in divisors(an)) for (s in listOf(1L, -1L)) {
            val r = Rational.of(num * BigInteger.valueOf(s), den)
            if (r !in out && q.at(r).isZero) out += r
        }
        return out.sorted()
    }

    private fun divisors(n: BigInteger): List<BigInteger> {
        val out = mutableListOf<BigInteger>()
        var i = BigInteger.ONE
        while (i * i <= n) {
            if (n.mod(i).signum() == 0) { out += i; if (i * i != n) out += n / i }
            i += BigInteger.ONE
        }
        return out
    }

    /**
     * Todas las raíces de [p] con su multiplicidad sumada en una sola: racionales exactas, las de un
     * factor de grado 2 con raíz cuadrada exacta y el resto con decimales. Con [complex] = false, solo las
     * reales. null si el grado es 0 o supera [MAX_DEGREE].
     */
    fun roots(p: Poly, complex: Boolean): List<Root>? {
        if (p.degree < 1 || p.degree > MAX_DEGREE) return null
        val (_, factors) = factor(p) ?: return null
        val out = mutableListOf<Root>()
        for (f in factors) {
            val q = f.poly
            when (q.degree) {
                1 -> out += Root.Exact(-q[0] / q[1])
                2 -> out += quadratic(q)
                else -> out += numeric(q)
            }
        }
        val kept = if (complex) out else out.filter { it.isReal }
        return kept.distinctBy { r -> if (r is Root.Approx) Math.round(r.re * 1e6) to Math.round(r.im * 1e6) else r }.sortedWith(compareBy({ re(it) }, { im(it) }))
    }

    private fun re(r: Root) = when (r) {
        is Root.Exact -> r.a.toDouble() + if (r.imaginary) 0.0 else r.b.toDouble() * sqrt(r.m.toDouble())
        is Root.Approx -> r.re
    }

    private fun im(r: Root) = when (r) {
        is Root.Exact -> if (r.imaginary) r.b.toDouble() * sqrt(r.m.toDouble()) else 0.0
        is Root.Approx -> r.im
    }

    /** Fórmula resolvente: (−b ± √Δ) / 2a, con la raíz simplificada (√12 = 2√3). */
    private fun quadratic(q: Poly): List<Root> {
        val (c, b, a) = Triple(q[0], q[1], q[2])
        val disc = b * b - Rational.of(4) * a * c
        val center = -b / (Rational.of(2) * a)
        if (disc.isZero) return listOf(Root.Exact(center))
        // √(n/d) = √(n·d)/d; luego n·d = k²·m.
        val (k, m) = squareFree(disc.num.abs() * disc.den)
        val half = Rational.of(k, disc.den) / (Rational.of(2) * a).abs()
        val imaginary = disc.signum < 0
        if (m == BigInteger.ONE && !imaginary) return listOf(Root.Exact(center - half), Root.Exact(center + half))
        return listOf(Root.Exact(center, -half, m, imaginary), Root.Exact(center, half, m, imaginary))
    }

    /** n = k²·m con m sin cuadrados (hasta donde se puede probar rápido). */
    private fun squareFree(n: BigInteger): Pair<BigInteger, BigInteger> {
        var m = n
        var k = BigInteger.ONE
        var f = BigInteger.TWO
        var steps = 0
        while (f * f <= m && steps < 200_000) {
            val sq = f * f
            while (m.mod(sq).signum() == 0) { m /= sq; k *= f }
            f += BigInteger.ONE
            steps++
        }
        return k to m
    }

    /** Raíces con decimales (Durand–Kerner); las de parte imaginaria despreciable quedan reales. */
    private fun numeric(q: Poly): List<Root> {
        val n = q.degree
        val a = q.c.map { (it / q.lead).toDouble() }
        val re = DoubleArray(n) { 0.4 + 0.9 * cos(2 * Math.PI * it / n + 0.4) }
        val im = DoubleArray(n) { 0.9 * sin(2 * Math.PI * it / n + 0.4) }
        for (iteration in 0 until 2000) {
            var moved = 0.0
            for (i in 0 until n) {
                // p(z) por Horner.
                var pr = 1.0; var pi = 0.0
                for (k in n - 1 downTo 0) {
                    val nr = pr * re[i] - pi * im[i] + a[k]
                    pi = pr * im[i] + pi * re[i]
                    pr = nr
                }
                var dr = 1.0; var di = 0.0
                for (j in 0 until n) if (j != i) {
                    val xr = re[i] - re[j]; val xi = im[i] - im[j]
                    val nr = dr * xr - di * xi
                    di = dr * xi + di * xr
                    dr = nr
                }
                val den = dr * dr + di * di
                if (den == 0.0) continue
                val sr = (pr * dr + pi * di) / den
                val si = (pi * dr - pr * di) / den
                re[i] -= sr; im[i] -= si
                moved = maxOf(moved, hypot(sr, si))
            }
            if (moved < 1e-14) break
        }
        return (0 until n).map { i ->
            val scale = maxOf(1.0, hypot(re[i], im[i]))
            Root.Approx(clean(re[i]), if (abs(im[i]) < 1e-9 * scale) 0.0 else clean(im[i]))
        }
    }

    private fun clean(x: Double) = if (abs(x) < 1e-12) 0.0 else x

    /**
     * Sistema lineal: cada fila son los coeficientes de las [n] incógnitas y el término independiente
     * (`a·x + b·y = c` es [a, b, c]). La solución única, o null si no hay una sola.
     */
    fun solveLinear(rows: List<List<Rational>>, n: Int): List<Rational>? {
        val m = rows.map { it.toMutableList() }.toMutableList()
        var r = 0
        val pivots = mutableListOf<Int>()
        for (col in 0 until n) {
            val p = (r until m.size).firstOrNull { !m[it][col].isZero } ?: continue
            m[r] = m[p].also { m[p] = m[r] }
            val lead = m[r][col]
            m[r] = m[r].map { it / lead }.toMutableList()
            for (i in m.indices) if (i != r && !m[i][col].isZero) {
                val f = m[i][col]
                m[i] = m[i].mapIndexed { j, v -> v - f * m[r][j] }.toMutableList()
            }
            pivots += col
            r++
        }
        if (pivots.size < n) return null
        if ((r until m.size).any { !m[it][n].isZero }) return null
        return (0 until n).map { m[it][n] }
    }
}
