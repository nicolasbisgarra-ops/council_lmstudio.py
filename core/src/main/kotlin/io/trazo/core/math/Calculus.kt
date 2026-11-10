package io.trazo.core.math

/**
 * Derivadas e integrales de polinomios (con exponentes enteros, también negativos) término a término, y
 * cuentas con matrices: una matriz es un par de pares de igual largo, `[[1;2];[3;4]]`.
 */
internal object Calculus {
    /** Constante de integración de una integral indefinida. */
    const val CONSTANT = "C"
    private const val SIMPSON_STEPS = 2000
    private const val MAX_MATRIX = 6

    fun derivative(f: MathValue, v: String): MathValue = when (f) {
        is TupleValue -> TupleValue(f.items.map { derivative(it, v) })
        is QuotientValue -> domain(QuotientValue.STOP)
        else -> sum(SymbolicValue.termsOf(f).mapNotNull { (m, c) ->
            val k = m[v] ?: 0
            if (k == 0) null else term(m + (v to k - 1), c, Rational.of(k.toLong()))
        })
    }

    fun antiderivative(f: MathValue, v: String): MathValue = when (f) {
        is TupleValue -> TupleValue(f.items.map { antiderivative(it, v) })
        is QuotientValue -> domain(QuotientValue.STOP)
        else -> sum(SymbolicValue.termsOf(f).map { (m, c) ->
            val k = m[v] ?: 0
            if (k == -1) domain("La integral de 1/$v da un logaritmo: todavía no la calculo con letras")
            term(m + (v to k + 1), c, Rational.of(1L, (k + 1).toLong()))
        })
    }

    /** F(b) − F(a): reemplaza [v] por cada límite. */
    fun between(f: MathValue, v: String, a: MathValue, b: MathValue): MathValue {
        if (hasPole(f, v) && crossesZero(a, b)) domain("La integral diverge")
        return SymbolicValue.add(substitute(f, v, b), SymbolicValue.negate(substitute(f, v, a)))
    }

    internal fun hasPole(f: MathValue, v: String): Boolean = when (f) {
        is TupleValue -> f.items.any { hasPole(it, v) }
        else -> SymbolicValue.termsOf(f).any { (m, _) -> (m[v] ?: 0) < 0 }
    }

    internal fun crossesZero(a: MathValue, b: MathValue): Boolean {
        if (a.hasLetters() || b.hasLetters() || a is TupleValue || b is TupleValue) return false
        val da = a.toDouble()
        val db = b.toDouble()
        if (da.isNaN() || db.isNaN()) return false
        return (da <= 0.0 && db >= 0.0) || (da >= 0.0 && db <= 0.0)
    }

    private fun substitute(f: MathValue, v: String, x: MathValue): MathValue = when (f) {
        is TupleValue -> TupleValue(f.items.map { substitute(it, v, x) })
        else -> sum(SymbolicValue.termsOf(f).map { (m, c) ->
            val k = m[v] ?: 0
            val rest = SymbolicValue.of(mapOf(m - v to c))
            if (k == 0) rest else SymbolicValue.multiply(rest, SymbolicValue.power(x, ExactValue.of(Rational.of(k.toLong()))))
        })
    }

    /** Regla de Simpson, para integrandos que no son polinomios (raíces, 1/x…) con límites numéricos. */
    fun simpson(f: (Double) -> Double, a: Double, b: Double): ApproxValue {
        val n = SIMPSON_STEPS
        val h = (b - a) / n
        var s = f(a) + f(b)
        for (i in 1 until n) s += (if (i % 2 == 1) 4 else 2) * f(a + i * h)
        val out = s * h / 3
        if (out.isNaN() || out.isInfinite()) domain("La integral no da un número")
        return ApproxValue(out)
    }

    private fun term(m: Map<String, Int>, c: MathValue, factor: Rational): MathValue =
        SymbolicValue.multiply(SymbolicValue.of(mapOf(m to c)), ExactValue.of(factor))

    private fun sum(values: List<MathValue>): MathValue = values.fold(ExactValue.ZERO as MathValue) { a, b -> SymbolicValue.add(a, b) }

    // --- Matrices ---

    /** Filas de una matriz, o null si el valor no lo es. */
    fun rows(v: MathValue): List<List<MathValue>>? {
        if (v !is TupleValue || v.items.isEmpty()) return null
        val rows = v.items.map { (it as? TupleValue)?.items ?: return null }
        if (rows.any { r -> r.size != rows[0].size || r.any { it is TupleValue } }) return null
        return rows
    }

    private fun matrix(rows: List<List<MathValue>>) = TupleValue(rows.map { TupleValue(it) })

    /** Producto con al menos una matriz: matriz·matriz, matriz·vector o vector·matriz. */
    fun multiply(a: TupleValue, b: TupleValue, op: (Char, MathValue, MathValue) -> MathValue): MathValue {
        val ra = rows(a)
        val rb = rows(b)
        val left = ra ?: listOf(a.items)
        val right = rb ?: b.items.map { listOf(it) }
        if (left[0].size != right.size) domain("Las matrices no tienen tamaños compatibles para multiplicarse")
        val out = left.map { row -> (0 until right[0].size).map { j -> dot(row, right.map { it[j] }, op) } }
        return when {
            ra != null && rb != null -> matrix(out)
            ra != null -> TupleValue(out.map { it.single() })
            else -> TupleValue(out.single())
        }
    }

    private fun dot(x: List<MathValue>, y: List<MathValue>, op: (Char, MathValue, MathValue) -> MathValue): MathValue =
        x.zip(y) { p, q -> op('*', p, q) }.reduce { s, t -> op('+', s, t) }

    fun transpose(v: TupleValue): TupleValue {
        val r = rows(v) ?: return v
        return matrix(r[0].indices.map { j -> r.map { it[j] } })
    }

    fun power(v: TupleValue, k: Int, op: (Char, MathValue, MathValue) -> MathValue): MathValue {
        val r = square(v)
        val base = if (k < 0) inverse(r, op) else v
        var out: MathValue = matrix(r.indices.map { i -> r.indices.map { j -> ExactValue.of(if (i == j) Rational.ONE else Rational.ZERO) } })
        repeat(kotlin.math.abs(k)) { out = multiply(out as TupleValue, base as TupleValue, op) }
        return out
    }

    fun determinant(v: MathValue, op: (Char, MathValue, MathValue) -> MathValue): MathValue = det(square(v), op)

    private fun square(v: MathValue): List<List<MathValue>> {
        val r = rows(v) ?: domain("Hace falta una matriz, como [[1;2];[3;4]]")
        if (r.size != r[0].size) domain("La matriz tiene que ser cuadrada")
        if (r.size > MAX_MATRIX) domain("La matriz es demasiado grande")
        return r
    }

    private fun det(r: List<List<MathValue>>, op: (Char, MathValue, MathValue) -> MathValue): MathValue {
        if (r.size == 1) return r[0][0]
        var out: MathValue = ExactValue.ZERO
        for (j in r.indices) {
            val minor = det(minor(r, 0, j), op)
            val t = op('*', r[0][j], minor)
            out = op(if (j % 2 == 0) '+' else '-', out, t)
        }
        return out
    }

    private fun minor(r: List<List<MathValue>>, i: Int, j: Int) =
        r.filterIndexed { a, _ -> a != i }.map { row -> row.filterIndexed { b, _ -> b != j } }

    /** Inversa por la adjunta: cof(A)ᵀ / det(A). */
    private fun inverse(r: List<List<MathValue>>, op: (Char, MathValue, MathValue) -> MathValue): TupleValue {
        val d = det(r, op)
        if (d.sameAs(ExactValue.ZERO)) domain("La matriz no tiene inversa: su determinante es 0")
        if (r.size == 1) return matrix(listOf(listOf(op('/', ExactValue.of(Rational.ONE), d))))
        return matrix(r.indices.map { i ->
            r.indices.map { j ->
                val c = det(minor(r, j, i), op)
                op('/', if ((i + j) % 2 == 0) c else op('-', ExactValue.ZERO, c), d)
            }
        })
    }

    private fun domain(detail: String): Nothing = throw MathException(MathError.Domain(detail))
}
