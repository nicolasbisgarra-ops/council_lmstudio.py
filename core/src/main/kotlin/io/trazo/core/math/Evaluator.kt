package io.trazo.core.math

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Evalúa un [Expr]. Mantiene el valor exacto mientras se pueda y pasa a decimal solo al dividir por
 * una suma con π, con exponentes no enteros o con raíces no exactas.
 */
object Evaluator {
    /** Mayor exponente entero que se calcula de forma exacta. */
    const val MAX_EXACT_EXPONENT = 64

    fun evaluate(e: Expr, lookup: (String) -> MathValue): MathValue = when (e) {
        is Expr.Num -> ExactValue.of(e.value)
        is Expr.Pi -> ExactValue.PI_VALUE
        is Expr.Var -> lookup(e.name)
        is Expr.Neg -> negate(evaluate(e.operand, lookup))
        is Expr.Bin -> binary(e.op, evaluate(e.left, lookup), evaluate(e.right, lookup))
        is Expr.Pow -> power(evaluate(e.base, lookup), evaluate(e.exponent, lookup))
        is Expr.Sqrt -> squareRoot(evaluate(e.operand, lookup))
        is Expr.Tuple -> TupleValue(e.items.map { evaluate(it, lookup) })
    }

    private fun negate(v: MathValue): MathValue = when (v) {
        is ExactValue -> -v
        is ApproxValue -> ApproxValue(-v.value)
        is SymbolicValue -> SymbolicValue.negate(v)
        is TupleValue -> TupleValue(v.items.map(::negate))
    }

    private fun binary(op: Char, a: MathValue, b: MathValue): MathValue {
        if (a is TupleValue || b is TupleValue) return TupleValue.binary(op, a, b, ::binary)
        if (a is SymbolicValue || b is SymbolicValue) return when (op) {
            '+' -> SymbolicValue.add(a, b)
            '-' -> SymbolicValue.add(a, SymbolicValue.negate(b))
            '*' -> SymbolicValue.multiply(a, b)
            else -> SymbolicValue.divide(a, b)
        }
        if (a is ExactValue && b is ExactValue) {
            when (op) {
                '+' -> return a + b
                '-' -> return a - b
                '*' -> return a * b
                '/' -> a.divOrNull(b)?.let { return it }
            }
        }
        val x = a.toDouble()
        val y = b.toDouble()
        return when (op) {
            '+' -> approx(x + y)
            '-' -> approx(x - y)
            '*' -> approx(x * y)
            else -> {
                if (y == 0.0) throw MathException(MathError.DivisionByZero)
                approx(x / y)
            }
        }
    }

    private fun power(base: MathValue, exponent: MathValue): MathValue {
        if (exponent is TupleValue) domain("El exponente no puede ser un par")
        if (base is TupleValue) return TupleValue(base.items.map { power(it, exponent) })
        if (base is SymbolicValue || exponent is SymbolicValue) return SymbolicValue.power(base, exponent)
        val n = (exponent as? ExactValue)?.rational
        if (n != null && n.isInteger) {
            if (n.num.abs() > MAX_EXACT_EXPONENT.toBigInteger()) domain("El exponente es demasiado grande")
            val k = n.num.toInt()
            if (base.isZeroValue()) {
                if (k == 0) domain("0⁰ no está definido")
                if (k < 0) throw MathException(MathError.DivisionByZero)
            }
            if (base is ExactValue) exactPower(base, k)?.let { return it }
            return approx(base.toDouble().pow(k))
        }
        val x = base.toDouble()
        val y = exponent.toDouble()
        if (x < 0) domain("Una potencia con exponente no entero necesita una base positiva")
        if (x == 0.0 && y <= 0) domain("0 elevado a un exponente no positivo no está definido")
        return approx(x.pow(y))
    }

    private fun exactPower(base: ExactValue, k: Int): ExactValue? {
        if (k >= 0) {
            var out = ExactValue.of(Rational.ONE)
            repeat(k) { out *= base }
            return out
        }
        val (p, c) = base.monomial ?: return null
        return ExactValue.of(mapOf(p * k to c.pow(k)))
    }

    private fun squareRoot(v: MathValue): MathValue {
        if (v is TupleValue) return TupleValue(v.items.map(::squareRoot))
        if (v is SymbolicValue) domain("No se puede sacar la raíz de una cuenta con letras")
        if (v is ExactValue) {
            if (v.isZero) return v
            val (p, c) = v.monomial ?: return approxSqrt(v.toDouble())
            if (c.signum < 0) domain("No existe la raíz cuadrada real de un número negativo")
            val root = c.exactSqrt()
            if (root != null && p % 2 == 0) return ExactValue.of(mapOf(p / 2 to root))
        }
        return approxSqrt(v.toDouble())
    }

    private fun approxSqrt(x: Double): MathValue {
        if (x < 0) domain("No existe la raíz cuadrada real de un número negativo")
        return approx(sqrt(x))
    }

    private fun MathValue.isZeroValue() = when (this) {
        is ExactValue -> isZero
        is ApproxValue -> value == 0.0
        is SymbolicValue, is TupleValue -> false
    }

    private fun approx(x: Double): ApproxValue {
        if (x.isNaN() || x.isInfinite()) domain(if (x.isNaN()) "El resultado no está definido" else "El resultado es demasiado grande")
        return ApproxValue(if (abs(x) == 0.0) 0.0 else x)
    }

    private fun domain(detail: String): Nothing = throw MathException(MathError.Domain(detail))
}
