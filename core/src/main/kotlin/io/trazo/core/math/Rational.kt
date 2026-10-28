package io.trazo.core.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** Fracción exacta n/d en forma canónica: d > 0 y mcd(n, d) = 1. */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    val isZero: Boolean get() = num.signum() == 0
    val isInteger: Boolean get() = den == BigInteger.ONE
    val signum: Int get() = num.signum()

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        if (o.isZero) throw MathException(MathError.DivisionByZero)
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = Rational(num.negate(), den)
    fun abs() = if (num.signum() < 0) -this else this

    fun pow(n: Int): Rational = when {
        n >= 0 -> Rational(num.pow(n), den.pow(n))
        else -> ONE / Rational(num.pow(-n), den.pow(-n))
    }

    /** Raíz cuadrada exacta si numerador y denominador son cuadrados perfectos; si no, null. */
    fun exactSqrt(): Rational? {
        if (num.signum() < 0) return null
        val n = isqrt(num)
        val d = isqrt(den)
        return if (n * n == num && d * d == den) Rational(n, d) else null
    }

    fun toDouble(): Double =
        if (isInteger) num.toDouble() else BigDecimal(num).divide(BigDecimal(den), MathContext.DECIMAL64).toDouble()

    override fun compareTo(other: Rational): Int = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = 31 * num.hashCode() + den.hashCode()
    override fun toString() = if (isInteger) num.toString() else "$num/$den"

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Rational {
            if (d.signum() == 0) throw MathException(MathError.DivisionByZero)
            val g = n.gcd(d)
            val s = if (d.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
            return Rational(n / g * s, d / g * s)
        }

        /** "12", "0.25" o "3,5" (coma decimal) → valor exacto. */
        fun parseDecimal(text: String): Rational {
            val t = text.replace(',', '.')
            val dot = t.indexOf('.')
            if (dot < 0) return of(BigInteger(t))
            val decimals = t.length - dot - 1
            return of(BigInteger(t.removeRange(dot, dot + 1)), BigInteger.TEN.pow(decimals))
        }

        // BigInteger.sqrt() no existe en todas las versiones de Android que soporta la app.
        private fun isqrt(n: BigInteger): BigInteger {
            if (n.signum() == 0) return n
            var x = BigInteger.ONE.shiftLeft(n.bitLength() / 2 + 1)
            while (true) {
                val y = (x + n / x).shiftRight(1)
                if (y >= x) return x
                x = y
            }
        }
    }
}
