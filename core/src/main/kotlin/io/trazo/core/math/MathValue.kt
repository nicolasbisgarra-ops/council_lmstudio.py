package io.trazo.core.math

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Resultado de un cálculo: exacto mientras se pueda, aproximado solo cuando no queda otra. */
sealed interface MathValue {
    fun toDouble(): Double
    fun display(): String
}

/**
 * Suma exacta de términos c·πᵏ (k puede ser negativo). Cubre racionales y expresiones como 9π o π/2
 * sin perder exactitud. Los coeficientes nulos no se guardan.
 */
class ExactValue private constructor(val terms: Map<Int, Rational>) : MathValue {

    val isZero: Boolean get() = terms.isEmpty()

    /** Si es un único término c·πᵏ, el par (k, c). */
    val monomial: Pair<Int, Rational>? get() = terms.entries.singleOrNull()?.toPair()

    /** El valor como racional puro, si no tiene π. */
    val rational: Rational?
        get() = when {
            terms.isEmpty() -> Rational.ZERO
            terms.size == 1 && terms.containsKey(0) -> terms.getValue(0)
            else -> null
        }

    operator fun plus(o: ExactValue): ExactValue {
        val out = terms.toMutableMap()
        for ((k, c) in o.terms) out[k] = (out[k] ?: Rational.ZERO) + c
        return of(out)
    }

    operator fun unaryMinus() = of(terms.mapValues { -it.value })
    operator fun minus(o: ExactValue) = this + -o

    operator fun times(o: ExactValue): ExactValue {
        val out = mutableMapOf<Int, Rational>()
        for ((k1, c1) in terms) for ((k2, c2) in o.terms) out[k1 + k2] = (out[k1 + k2] ?: Rational.ZERO) + c1 * c2
        return of(out)
    }

    /** División exacta por un monomio; con otro divisor devuelve null y el llamador aproxima. */
    fun divOrNull(o: ExactValue): ExactValue? {
        if (o.isZero) throw MathException(MathError.DivisionByZero)
        val (k, c) = o.monomial ?: return null
        return of(terms.entries.associate { (k1, c1) -> k1 - k to c1 / c })
    }

    override fun toDouble(): Double = terms.entries.sumOf { (k, c) -> c.toDouble() * PI.pow(k) }

    override fun display(): String {
        if (terms.isEmpty()) return "0"
        val sb = StringBuilder()
        for ((i, k) in terms.keys.sortedDescending().withIndex()) {
            val c = terms.getValue(k)
            when {
                i == 0 && c.signum < 0 -> sb.append('-')
                i > 0 -> sb.append(if (c.signum < 0) " - " else " + ")
            }
            sb.append(term(c.abs(), k))
        }
        return sb.toString()
    }

    override fun equals(other: Any?) = other is ExactValue && terms == other.terms
    override fun hashCode() = terms.hashCode()
    override fun toString() = display()

    companion object {
        val ZERO = ExactValue(emptyMap())
        fun of(r: Rational) = of(mapOf(0 to r))
        fun of(terms: Map<Int, Rational>) = ExactValue(terms.filterValues { !it.isZero })
        val PI_VALUE = of(mapOf(1 to Rational.ONE))

        private fun term(c: Rational, k: Int): String {
            if (k == 0) return c.toString()
            val pi = "π" + superscript(abs(k))
            val n = c.num.toString()
            val d = c.den.toString()
            return if (k > 0) {
                (if (n == "1") pi else n + pi) + (if (c.isInteger) "" else "/$d")
            } else {
                n + "/" + (if (c.isInteger) pi else "($d$pi)")
            }
        }

        private fun superscript(n: Int): String =
            if (n == 1) "" else n.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")
    }
}

/** Valor decimal, para raíces no exactas, potencias fraccionarias o divisiones por sumas con π. */
data class ApproxValue(val value: Double) : MathValue {
    override fun toDouble() = value
    override fun display(): String =
        "≈ " + BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
}

/** Igualdad para comprobar "a = b": exacta entre exactos, con tolerancia relativa si hay aproximados. */
fun MathValue.sameAs(other: MathValue): Boolean {
    if (this is ExactValue && other is ExactValue) return this == other
    val a = toDouble()
    val b = other.toDouble()
    return abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))
}
