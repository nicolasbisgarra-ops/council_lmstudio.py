package io.trazo.core.math

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Cuenta con letras sin valor («resolución genérica»): suma de términos c·x²k… Las letras quedan escritas y
 * los números se operan, como en `2x + 3x = 5x` o `(3, 2)·(5, k) = (15, 2k)`. π se guarda como una letra más
 * para no perder exactitud; si no queda ninguna otra letra, el valor vuelve a ser un número.
 *
 * Cada término es un producto de letras con exponente entero (negativo = dividido) y un coeficiente
 * racional ([ExactValue] sin π) o decimal ([ApproxValue]).
 */
class SymbolicValue private constructor(val terms: Map<Map<String, Int>, MathValue>) : MathValue {

    /** No es un número: las comparaciones con decimales nunca dan iguales. */
    override fun toDouble(): Double = Double.NaN

    /** Términos ordenados como se escriben: mayor grado primero, el número solo al final. */
    val ordered: List<Pair<Map<String, Int>, MathValue>>
        get() = terms.entries.map { it.toPair() }.sortedWith(
            compareByDescending<Pair<Map<String, Int>, MathValue>> { (m, _) -> m.filterKeys { it != PI_LETTER }.values.sum() }
                .thenBy { (m, _) -> m.isEmpty() || m.keys == setOf(PI_LETTER) }
                .thenBy { (m, _) -> letters(m).joinToString("") { (l, e) -> l + e } },
        )

    override fun display(): String {
        val sb = StringBuilder()
        for ((i, t) in ordered.withIndex()) {
            val (m, c) = t
            val negative = sign(c) < 0
            when {
                i == 0 && negative -> sb.append('-')
                i > 0 -> sb.append(if (negative) " - " else " + ")
            }
            sb.append(termText(m, magnitude(c)))
        }
        return sb.toString()
    }

    override fun equals(other: Any?) = other is SymbolicValue && terms == other.terms
    override fun hashCode() = terms.hashCode()
    override fun toString() = display()

    companion object {
        /** Nombre interno de π dentro de un término. */
        const val PI_LETTER = "π"
        /** Mayor exponente que se desarrolla con letras: (x + 1)¹² ya tiene 13 términos. */
        const val MAX_EXPONENT = 12
        private const val MAX_TERMS = 200
        private val ONE = ExactValue.of(Rational.ONE)

        fun variable(name: String): SymbolicValue = SymbolicValue(mapOf(mapOf(name to 1) to ONE))

        /** Letras de un término en orden de escritura (π primero) con su exponente. */
        fun letters(m: Map<String, Int>): List<Pair<String, Int>> =
            m.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key != PI_LETTER }.thenBy { it.key }).map { it.toPair() }

        /** Términos de cualquier valor (un número es un término sin letras). */
        internal fun termsOf(v: MathValue): Map<Map<String, Int>, MathValue> = when (v) {
            is SymbolicValue -> v.terms
            is ExactValue -> v.terms.entries.associate { (k, c) -> (if (k == 0) emptyMap() else mapOf(PI_LETTER to k)) to ExactValue.of(c) }
            is ApproxValue -> if (v.value == 0.0) emptyMap() else mapOf(emptyMap<String, Int>() to v)
            is TupleValue -> domain("Un par no se puede usar como número")
            is QuotientValue -> domain(QuotientValue.STOP)
        }

        /** Junta términos iguales, quita los nulos y, si no quedan letras, devuelve el número. */
        internal fun of(raw: Map<Map<String, Int>, MathValue>): MathValue {
            val terms = raw.filterValues { !isZero(it) }.mapKeys { (m, _) -> m.filterValues { it != 0 } }
            if (terms.size > MAX_TERMS) domain("La cuenta con letras es demasiado larga")
            if (terms.keys.any { m -> m.keys.any { it != PI_LETTER } }) return SymbolicValue(terms)
            if (terms.values.all { it is ExactValue }) {
                return ExactValue.of(terms.entries.associate { (m, c) -> (m[PI_LETTER] ?: 0) to (c as ExactValue).rational!! })
            }
            return ApproxValue(terms.entries.sumOf { (m, c) -> c.toDouble() * PI.pow(m[PI_LETTER] ?: 0) })
        }

        fun add(a: MathValue, b: MathValue): MathValue {
            val out = termsOf(a).toMutableMap()
            for ((m, c) in termsOf(b)) out[m] = out[m]?.let { plus(it, c) } ?: c
            return of(out)
        }

        fun negate(a: MathValue): MathValue = of(termsOf(a).mapValues { (_, c) -> times(c, MINUS_ONE) })

        fun multiply(a: MathValue, b: MathValue): MathValue {
            val out = mutableMapOf<Map<String, Int>, MathValue>()
            for ((m1, c1) in termsOf(a)) for ((m2, c2) in termsOf(b)) {
                val m = (m1.keys + m2.keys).associateWith { (m1[it] ?: 0) + (m2[it] ?: 0) }.filterValues { it != 0 }
                val c = times(c1, c2)
                out[m] = out[m]?.let { plus(it, c) } ?: c
                if (out.size > MAX_TERMS) domain("La cuenta con letras es demasiado larga")
            }
            return of(out)
        }

        /**
         * Dividir por un término (`6xy / 2x = 3y`), por un número, o un polinomio por otro de la misma letra:
         * `(x² − 1)/(x − 1) = x + 1`; si no da exacto, cociente y resto ([QuotientValue]).
         */
        fun divide(a: MathValue, b: MathValue): MathValue {
            if (b is SymbolicValue && b.terms.size > 1) return polynomialDivision(a, b)
            return multiply(a, inverse(b))
        }

        private fun polynomialDivision(a: MathValue, b: SymbolicValue): MathValue {
            val letter = b.terms.keys.flatMap { it.keys }.filter { it != PI_LETTER }.distinct().singleOrNull()
                ?: domain("Solo se divide por un polinomio de una sola letra")
            val num = Poly.of(a, letter)
            val den = Poly.of(b, letter)
            if (num == null || den == null) domain("No se puede dividir por una suma con letras")
            if (num.degree > Polynomials.MAX_DEGREE || den.degree > Polynomials.MAX_DEGREE) domain("El polinomio es de grado demasiado alto")
            val (q, r) = num.divide(den)
            if (r.isZero) return q.toValue(letter)
            if (q.isZero) domain("No se puede dividir por una suma con letras")
            return QuotientValue(q.toValue(letter), r.toValue(letter), b)
        }

        fun power(base: MathValue, exponent: MathValue): MathValue {
            if (exponent is SymbolicValue) domain("Un exponente con letras no se puede calcular")
            val n = (exponent as? ExactValue)?.rational?.takeIf { it.isInteger }
                ?: domain("Con letras, el exponente tiene que ser un número entero")
            if (n.num.abs() > MAX_EXPONENT.toBigInteger()) domain("El exponente es demasiado grande para una cuenta con letras")
            val k = n.num.toInt()
            val b = if (k < 0) inverse(base) else base
            var out: MathValue = ONE
            repeat(abs(k)) { out = multiply(out, b) }
            return out
        }

        private fun inverse(v: MathValue): MathValue {
            val terms = termsOf(v)
            if (terms.isEmpty()) throw MathException(MathError.DivisionByZero)
            val (m, c) = terms.entries.singleOrNull()?.toPair()
                ?: if (v !is SymbolicValue) return ApproxValue(1.0 / v.toDouble()) else domain("No se puede dividir por una suma con letras")
            val inv = when (c) {
                is ExactValue -> ExactValue.of(Rational.ONE / c.rational!!)
                else -> ApproxValue(1.0 / c.toDouble())
            }
            return SymbolicValue(mapOf(m.mapValues { -it.value } to inv))
        }

        private val MINUS_ONE = ExactValue.of(-Rational.ONE)

        private fun plus(a: MathValue, b: MathValue): MathValue =
            if (a is ExactValue && b is ExactValue) a + b else ApproxValue(a.toDouble() + b.toDouble())

        private fun times(a: MathValue, b: MathValue): MathValue =
            if (a is ExactValue && b is ExactValue) a * b else ApproxValue(a.toDouble() * b.toDouble())

        private fun isZero(c: MathValue) = c is ExactValue && c.isZero || c is ApproxValue && c.value == 0.0

        internal fun sign(c: MathValue): Int = when (c) {
            is ExactValue -> c.rational?.signum ?: 1
            else -> c.toDouble().compareTo(0.0)
        }

        internal fun magnitude(c: MathValue): MathValue = if (sign(c) < 0) times(c, MINUS_ONE) else c

        /** Texto de un término con coeficiente positivo: «3x²», «x/2», «2/(xy)». */
        private fun termText(m: Map<String, Int>, c: MathValue): String {
            val up = letters(m).filter { it.second > 0 }.joinToString("") { (l, e) -> l + superscript(e) }
            val down = letters(m).filter { it.second < 0 }.joinToString("") { (l, e) -> l + superscript(-e) }
            val (num, den) = when (c) {
                is ExactValue -> c.rational!!.let { it.num.toString() to it.den.toString() }
                else -> BigDecimal(c.toDouble()).round(MathContext(10)).stripTrailingZeros().toPlainString() to "1"
            }
            val top = (if (num != "1" || up.isEmpty()) num else "") + up
            val bottom = (if (den != "1") den else "") + down
            if (bottom.isEmpty()) return top
            return "$top/" + if (bottom.length == 1 || bottom.all { it.isDigit() }) bottom else "($bottom)"
        }

        internal fun superscript(n: Int): String =
            if (n == 1) "" else n.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")

        private fun domain(detail: String): Nothing = throw MathException(MathError.Domain(detail))
    }
}

/** Un par, una terna…: `(3, 2)`. Las cuentas entre pares van elemento a elemento. */
data class TupleValue(val items: List<MathValue>) : MathValue {
    override fun toDouble(): Double = Double.NaN
    override fun display(): String = items.joinToString(", ", "(", ")") { it.display() }

    companion object {
        /** [op] entre pares del mismo largo, o un par por (o dividido) un número. */
        fun binary(op: Char, a: MathValue, b: MathValue, scalar: (Char, MathValue, MathValue) -> MathValue): MathValue = when {
            a is TupleValue && b is TupleValue -> {
                if (a.items.size != b.items.size) domain("Los pares no tienen la misma cantidad de elementos")
                TupleValue(a.items.zip(b.items) { x, y -> scalar(op, x, y) })
            }
            a is TupleValue && op in "*/" -> TupleValue(a.items.map { scalar(op, it, b) })
            b is TupleValue && op == '*' -> TupleValue(b.items.map { scalar(op, a, it) })
            b is TupleValue && op == '/' -> domain("No se puede dividir un número por un par")
            else -> domain(if (op == '+') "No se puede sumar un número con un par" else "No se puede restar un número y un par")
        }

        private fun domain(detail: String): Nothing = throw MathException(MathError.Domain(detail))
    }
}

/**
 * Una división de polinomios que no da exacta: [quotient] + [remainder] / [divisor], como
 * `(x² + 1)/(x − 1) = x + 1 + 2/(x − 1)`. Es un resultado para escribir: no se sigue operando con él.
 */
data class QuotientValue(val quotient: MathValue, val remainder: MathValue, val divisor: MathValue) : MathValue {
    override fun toDouble(): Double = Double.NaN
    override fun display(): String {
        val q = if (quotient.sameAs(ExactValue.ZERO)) "" else quotient.display() + " + "
        val r = remainder.display().let { if (it.contains(' ')) "($it)" else it }
        return "$q$r/(${divisor.display()})"
    }

    companion object {
        const val STOP = "Una división con resto no se puede seguir operando: calculala sola"
    }
}

/** Si el valor tiene letras sin valor (también dentro de un par). */
fun MathValue.hasLetters(): Boolean = when (this) {
    is SymbolicValue, is QuotientValue -> true
    is TupleValue -> items.any { it.hasLetters() }
    else -> false
}

internal fun sameSymbolic(a: MathValue, b: MathValue): Boolean {
    if (a is QuotientValue || b is QuotientValue) return a == b
    if (a is TupleValue || b is TupleValue) {
        return a is TupleValue && b is TupleValue && a.items.size == b.items.size && a.items.zip(b.items).all { (x, y) -> x.sameAs(y) }
    }
    val diff = SymbolicValue.add(a, SymbolicValue.negate(b))
    return when (diff) {
        is SymbolicValue -> diff.terms.values.all { it is ApproxValue && abs(it.value) <= 1e-9 * max(1.0, abs(it.value)) }
        else -> diff.sameAs(ExactValue.ZERO)
    }
}
