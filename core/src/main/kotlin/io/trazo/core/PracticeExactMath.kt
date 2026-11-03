package io.trazo.core

import java.math.BigDecimal
import java.math.BigInteger

/** Restricted exact arithmetic for practice, never a replacement for the full Trazo parser. */
data class PracticeRational private constructor(val numerator: BigInteger, val denominator: BigInteger) {
    init { require(denominator.signum() > 0) }
    operator fun plus(other: PracticeRational) = of(numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator)
    operator fun minus(other: PracticeRational) = of(numerator * other.denominator - other.numerator * denominator,
        denominator * other.denominator)
    operator fun times(other: PracticeRational) = of(numerator * other.numerator, denominator * other.denominator)
    operator fun div(other: PracticeRational): PracticeRational {
        require(other.numerator != BigInteger.ZERO) { "division by zero" }
        return of(numerator * other.denominator, denominator * other.numerator)
    }
    override fun toString(): String = if (denominator == BigInteger.ONE) "$numerator" else "$numerator/$denominator"

    companion object {
        val ZERO = of(BigInteger.ZERO, BigInteger.ONE)
        val ONE = of(BigInteger.ONE, BigInteger.ONE)
        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun of(n: BigInteger, d: BigInteger): PracticeRational {
            require(d != BigInteger.ZERO) { "division by zero" }
            val sign = if (d.signum() < 0) BigInteger.valueOf(-1) else BigInteger.ONE
            val numerator = n * sign; val denominator = d * sign
            val gcd = numerator.gcd(denominator)
            return PracticeRational(numerator / gcd, denominator / gcd)
        }
        /** Integers, finite decimals and integer fractions only; variable denominators are unsupported. */
        fun parse(raw: String): PracticeRational {
            val s = raw.trim().replace(',', '.')
            require(s.isNotEmpty() && s.length <= 80) { "invalid rational length" }
            if ('/' in s) {
                val parts = s.split('/')
                require(parts.size == 2 && parts.all { it.trim().matches(Regex("[+-]?\\d{1,38}")) })
                return of(parts[0].trim().toBigInteger(), parts[1].trim().toBigInteger())
            }
            require(s.matches(Regex("[+-]?(?:\\d{1,38})(?:\\.\\d{1,20})?")))
            val decimal = BigDecimal(s)
            val scale = decimal.scale().coerceAtLeast(0)
            return of(decimal.unscaledValue(), BigInteger.TEN.pow(scale))
        }
    }
}

sealed interface PracticeLinearSolution {
    data class Unique(val x: PracticeRational) : PracticeLinearSolution
    data object None : PracticeLinearSolution
    data object AllReal : PracticeLinearSolution
}

/** Equation a*x+b = c*x+d. Coefficients are exact constants; no division by x. */
data class PracticeLinearProblem(
    val a: PracticeRational, val b: PracticeRational,
    val c: PracticeRational, val d: PracticeRational,
) {
    fun solve(): PracticeLinearSolution {
        val coefficient = a - c
        val rhs = d - b
        return when {
            coefficient == PracticeRational.ZERO && rhs == PracticeRational.ZERO -> PracticeLinearSolution.AllReal
            coefficient == PracticeRational.ZERO -> PracticeLinearSolution.None
            else -> PracticeLinearSolution.Unique(rhs / coefficient)
        }
    }
    fun prompt(): String = "Resuelve en ℝ: (${a})x + (${b}) = (${c})x + (${d})"
    fun verifyAnswer(raw: String): Boolean {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s.length > 80) return false
        return when (val solution = solve()) {
            is PracticeLinearSolution.Unique -> runCatching {
                val value = s.replace(Regex("^x\\s*=\\s*"), "")
                PracticeRational.parse(value) == solution.x
            }.getOrDefault(false)
            PracticeLinearSolution.None -> normalized(s) in setOf("sin solucion", "no tiene solucion", "ninguna solucion")
            PracticeLinearSolution.AllReal -> normalized(s) in setOf("infinitas soluciones", "todos los reales", "r")
        }
    }
    fun explanation(): String = when (val result = solve()) {
        is PracticeLinearSolution.Unique -> "Agrupa términos: (${a} − ${c})x = (${d} − ${b}); x = ${result.x}."
        PracticeLinearSolution.None -> "Los coeficientes de x se cancelan y queda una igualdad falsa: no hay solución."
        PracticeLinearSolution.AllReal -> "Ambos miembros son idénticos: todo número real es solución."
    }
    private fun normalized(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()
}

/** Seeded and reproducible without RNG/platform state; includes unique/none/all solution cases. */
object PracticeLinearGenerator {
    fun generate(seed: Long): PracticeLinearProblem {
        // SplitMix64 avalanche; no overflow exceptions on the JVM.
        fun mix(x: Long): Long {
            var z = x - 7046029254386353131L
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            return z xor (z ushr 31)
        }
        fun number(index: Long): Long = Math.floorMod(mix(seed + index), 19L) - 9L
        val a = PracticeRational.of(number(1), 2)
        val b = PracticeRational.of(number(2), 3)
        return when (Math.floorMod(seed, 3L).toInt()) {
            0 -> {
                val c = a + PracticeRational.ONE // nonzero coefficient difference
                PracticeLinearProblem(a, b, c, PracticeRational.of(number(3), 4))
            }
            1 -> PracticeLinearProblem(a, b, a, b + PracticeRational.ONE)
            else -> PracticeLinearProblem(a, b, a, b)
        }
    }
}
