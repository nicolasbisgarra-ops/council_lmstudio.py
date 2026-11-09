package io.trazo.core.math

import java.math.BigInteger

/**
 * «Paso a paso» sin conexión: el razonamiento de una cuenta como lo escribiría un profesor, generado con el
 * motor propio ([MathParser], [Evaluator], [Poly], [SymbolicValue], [Calculus]).
 *
 * - [expression]: aritmética con fracciones (común denominador, simplificación, división por la inversa…) y
 *   derivadas término a término (regla de la potencia, del producto y de la cadena).
 * - [equation]: ecuación de grado 1 (pasaje de términos y despeje) o de grado 2 (a, b, c, discriminante y
 *   fórmula resolvente) en una sola letra.
 * - [system]: sistema lineal de 2×2 por sustitución (si alguna letra tiene coeficiente ±1 o 0) o por reducción.
 *
 * Todas son funciones puras: devuelven los renglones (en castellano, con «−» y «·») o null si no saben
 * explicar ese caso; nunca lanzan. Las letras automáticas de los patrones quedan como están: quien muestra los
 * pasos los pasa por su `readable()`.
 */
internal object StepByStep {
    private const val MAX_STEPS = 20
    private const val MAX_TERMS = 8
    private const val MAX_EXPONENT = 12
    private const val MAX_BITS = 256
    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    /** Separa un paso de su motivo: «2x = 4   (se opera en cada miembro)». */
    private const val SEP = "   ("
    private val DECIMAL = Regex("\\d[.,]\\d")
    private val TWO: BigInteger = BigInteger.valueOf(2L)
    private val FOUR: BigInteger = BigInteger.valueOf(4L)

    /** Un caso que no se sabe explicar: corta y se devuelve null. */
    private class Unexplained : Exception()

    /** Pasos de una cuenta (`1/2+1/3=`, `d/dx(x^3)=`, `a=2+3*4`); null si no hay nada que explicar. */
    fun expression(text: String): List<String>? = safe { expressionSteps(text) }

    /**
     * Pasos para resolver la ecuación [text] (`L=R`) en su única letra sin valor; las variables de [context]
     * (renglones como `a=3`) se reemplazan. Con [complex], una cuadrática sin raíces reales sigue con imaginarios.
     */
    fun equation(text: String, context: List<String> = emptyList(), complex: Boolean = false): List<String>? =
        safe { equationSteps(text, context, complex) }

    /** Pasos para resolver el sistema lineal de dos ecuaciones [equations] con dos letras. */
    fun system(equations: List<String>, context: List<String> = emptyList()): List<String>? =
        safe { systemSteps(equations, context) }

    private fun safe(block: () -> List<String>?): List<String>? = try {
        block()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun because(why: String) = "$SEP$why)"

    // --- Cuentas ---

    private fun expressionSteps(text: String): List<String>? {
        val expr = when (val st = MathParser.parseStatement(text)) {
            is Statement.Query -> st.expr
            is Statement.Assign -> st.expr
            else -> return null
        }
        if (expr is Expr.Deriv) return derivativeSteps(expr)
        if (DECIMAL.containsMatchIn(text)) return null
        return arithmeticSteps(expr)
    }

    /** Una cuenta con números: fracciones (no necesariamente simplificadas), operaciones y potencias enteras. */
    private sealed interface Node {
        /** [fresh]: salió de una operación y, si se puede, se simplifica en el paso siguiente. */
        data class Frac(val n: BigInteger, val d: BigInteger, val fresh: Boolean = false) : Node
        data class Op(val op: Char, val l: Node, val r: Node) : Node
        data class Minus(val x: Node) : Node
        data class Power(val b: Node, val k: Int) : Node
    }

    private fun arithmeticSteps(expr: Expr): List<String>? {
        val tree = tree(expr)
        var t = tree
        val start = show(t)
        val chain = mutableListOf<Pair<String, String?>>()
        while (true) {
            val step = reduce(t) ?: (t as? Node.Frac)?.let { simplify(it) } ?: break
            if (chain.size >= MAX_STEPS) return null
            t = step.first
            if (!small(t)) return null
            chain += show(t) to step.second
        }
        val f = t as? Node.Frac ?: return null
        // El resultado tiene que ser el mismo que calcula el motor.
        val exact = (Evaluator.evaluate(expr) { throw Unexplained() } as? ExactValue)?.rational ?: return null
        if (exact != Rational.of(f.n, f.d)) return null
        if (chain.isEmpty()) return null
        // «5 + 5 = 10» o «2 + 3 + 4»: nada que agregar a la línea principal.
        if (chain.all { it.second == null } && !hasProducts(tree)) return null
        return chain.mapIndexed { i, (s, why) -> (if (i == 0) "$start = $s" else "= $s") + (why?.let { because(it) } ?: "") }
    }

    private fun tree(e: Expr): Node = when (e) {
        is Expr.Num -> Node.Frac(e.value.num, e.value.den)
        is Expr.Neg -> negate(tree(e.operand))
        is Expr.Bin -> {
            val l = tree(e.left)
            val r = tree(e.right)
            if (e.op == '/' && literal(e.left) && literal(e.right) && l is Node.Frac && r is Node.Frac) fraction(l.n, r.n)
            else Node.Op(e.op, l, r)
        }
        is Expr.Pow -> Node.Power(tree(e.base), exponent(e.exponent))
        else -> throw Unexplained()
    }

    /** Un entero escrito (con su signo): `3/4` es la fracción 3/4, no una división para hacer. */
    private fun literal(e: Expr): Boolean =
        (e is Expr.Num && e.value.isInteger) || (e is Expr.Neg && e.operand.let { it is Expr.Num && it.value.isInteger })

    private fun exponent(e: Expr): Int {
        val r = when (e) {
            is Expr.Num -> e.value
            is Expr.Neg -> (e.operand as? Expr.Num)?.value?.let { -it } ?: throw Unexplained()
            else -> throw Unexplained()
        }
        if (!r.isInteger || r.num.abs() > BigInteger.valueOf(MAX_EXPONENT.toLong())) throw Unexplained()
        return r.num.toInt()
    }

    private fun fraction(n: BigInteger, d: BigInteger, fresh: Boolean = false): Node.Frac {
        if (d.signum() == 0) throw Unexplained()
        return if (d.signum() < 0) Node.Frac(n.negate(), d.negate(), fresh) else Node.Frac(n, d, fresh)
    }

    private fun negate(x: Node): Node = if (x is Node.Frac) Node.Frac(x.n.negate(), x.d, x.fresh) else Node.Minus(x)

    /** El primer paso (de izquierda a derecha, de adentro hacia afuera) y su motivo; null si ya es un número. */
    private fun reduce(t: Node): Pair<Node, String?>? = when (t) {
        is Node.Frac -> if (t.fresh) simplify(t) else null
        is Node.Minus -> reduce(t.x)?.let { (x, why) -> negate(x) to why } ?: throw Unexplained()
        is Node.Power -> reduce(t.b)?.let { (b, why) -> Node.Power(b, t.k) to why }
            ?: power(t.b as? Node.Frac ?: throw Unexplained(), t.k)
        is Node.Op -> reduce(t.l)?.let { (l, why) -> Node.Op(t.op, l, t.r) to why }
            ?: reduce(t.r)?.let { (r, why) -> Node.Op(t.op, t.l, r) to why }
            ?: operate(t.op, t.l as? Node.Frac ?: throw Unexplained(), t.r as? Node.Frac ?: throw Unexplained())
    }

    private fun simplify(f: Node.Frac): Pair<Node, String?>? {
        if (f.n.signum() == 0) return if (f.d == BigInteger.ONE) null else Node.Frac(BigInteger.ZERO, BigInteger.ONE) to null
        val g = f.n.gcd(f.d)
        if (g <= BigInteger.ONE) return null
        return Node.Frac(f.n / g, f.d / g) to "se simplifica dividiendo por $g"
    }

    private fun operate(op: Char, x: Node.Frac, y: Node.Frac): Pair<Node, String?> {
        val one = BigInteger.ONE
        return when (op) {
            '+', '-' -> {
                val plus = op == '+'
                if (x.d == y.d) {
                    val n = if (plus) x.n + y.n else x.n - y.n
                    Node.Frac(n, x.d, fresh = true) to
                        (if (x.d == one) null else "mismo denominador: se ${if (plus) "suman" else "restan"} los numeradores")
                } else {
                    val l = x.d / x.d.gcd(y.d) * y.d
                    val why = when {
                        x.d == one -> "se escribe ${whole(x.n)} como ${whole(x.n * l)}/$l"
                        y.d == one -> "se escribe ${whole(y.n)} como ${whole(y.n * l)}/$l"
                        else -> "denominador común: mcm(${x.d}, ${y.d}) = $l"
                    }
                    Node.Op(op, Node.Frac(x.n * (l / x.d), l), Node.Frac(y.n * (l / y.d), l)) to why
                }
            }
            '*' -> if (x.d == one && y.d == one) {
                Node.Frac(x.n * y.n, one, fresh = true) to null
            } else {
                Node.Frac(x.n * y.n, x.d * y.d, fresh = true) to
                    (if (x.d == one || y.d == one) "se multiplica el numerador" else "se multiplican los numeradores y los denominadores")
            }
            '/' -> when {
                y.n.signum() == 0 -> throw Unexplained()
                x.d == one && y.d == one ->
                    if (x.n.mod(y.n.abs()).signum() == 0) Node.Frac(x.n / y.n, one) to null
                    else fraction(x.n, y.n, fresh = true) to "la división se escribe como fracción"
                else -> {
                    val inverse = fraction(y.d, y.n)
                    Node.Op('*', x, inverse) to "dividir por ${show(y)} es multiplicar por su inversa, ${show(inverse)}"
                }
            }
            else -> throw Unexplained()
        }
    }

    private fun power(x: Node.Frac, k: Int): Pair<Node, String?> = when {
        k == 0 -> if (x.n.signum() == 0) throw Unexplained() else Node.Frac(BigInteger.ONE, BigInteger.ONE) to "un número distinto de 0 elevado a la 0 da 1"
        k < 0 -> if (x.n.signum() == 0) throw Unexplained() else Node.Power(fraction(x.d, x.n), -k) to "exponente negativo: se invierte la base"
        x.d == BigInteger.ONE -> Node.Frac(x.n.pow(k), BigInteger.ONE, fresh = true) to null
        else -> Node.Frac(x.n.pow(k), x.d.pow(k), fresh = true) to "se elevan el numerador y el denominador"
    }

    private fun small(t: Node): Boolean = when (t) {
        is Node.Frac -> t.n.bitLength() <= MAX_BITS && t.d.bitLength() <= MAX_BITS
        is Node.Op -> small(t.l) && small(t.r)
        is Node.Minus -> small(t.x)
        is Node.Power -> small(t.b)
    }

    private fun hasProducts(t: Node): Boolean = when (t) {
        is Node.Frac -> t.d != BigInteger.ONE
        is Node.Op -> t.op == '*' || t.op == '/' || hasProducts(t.l) || hasProducts(t.r)
        is Node.Minus -> hasProducts(t.x)
        is Node.Power -> true
    }

    private fun precedence(t: Node): Int = when (t) {
        is Node.Op -> if (t.op == '+' || t.op == '-') 1 else 2
        is Node.Minus -> 1
        is Node.Power -> 3
        is Node.Frac -> when {
            t.n.signum() < 0 -> 1
            t.d == BigInteger.ONE -> 4
            else -> 2
        }
    }

    private fun negative(t: Node) = t is Node.Minus || (t is Node.Frac && t.n.signum() < 0)

    private fun show(t: Node): String = when (t) {
        is Node.Frac -> if (t.d == BigInteger.ONE) whole(t.n) else whole(t.n) + "/" + t.d
        is Node.Minus -> "−" + wrapNode(t.x, precedence(t.x) < 2)
        is Node.Power -> wrapNode(t.b, precedence(t.b) < 4) + sup(t.k)
        is Node.Op -> {
            val p = if (t.op == '+' || t.op == '-') 1 else 2
            val l = wrapNode(t.l, t.l is Node.Op && precedence(t.l) < p)
            // «3 ÷ 1/2» se lee como (3 ÷ 1)/2: una fracción después de ÷ o · va entre paréntesis.
            val r = wrapNode(t.r, negative(t.r) ||
                ((t.op == '/' || t.op == '*') && t.r is Node.Frac && t.r.d != BigInteger.ONE) ||
                (t.r is Node.Op && (precedence(t.r) < p || (precedence(t.r) == p && (t.op == '-' || t.op == '/')))))
            when (t.op) {
                '+' -> "$l + $r"
                '-' -> "$l − $r"
                '*' -> "$l·$r"
                else -> "$l ÷ $r"
            }
        }
    }

    private fun wrapNode(t: Node, parens: Boolean) = show(t).let { if (parens) "($it)" else it }

    // --- Derivadas ---

    private fun derivativeSteps(d: Expr.Deriv): List<String>? {
        val v = d.variable
        if (d.operand.variables().any { it != v }) return null
        val parts = mutableListOf<Expr>()
        split(d.operand, parts)
        if (parts.size > MAX_TERMS) return null
        val lines = parts.map { termDerivative(it, v) ?: return null }
        if (parts.size == 1) return lines
        val total = Calculus.derivative(evaluate(d.operand), v)
        return listOf("La derivada de una suma es la suma de las derivadas: se deriva término a término.") + lines +
            "Se juntan los resultados: ${show(total)}"
    }

    /** Los términos sueltos de una suma (sin su signo: la suma final lo tiene en cuenta). */
    private fun split(e: Expr, out: MutableList<Expr>) {
        when {
            e is Expr.Bin && (e.op == '+' || e.op == '-') -> { split(e.left, out); split(e.right, out) }
            e is Expr.Neg -> split(e.operand, out)
            else -> out += e
        }
    }

    private fun termDerivative(term: Expr, v: String): String? {
        val shown = render(term)
        val head = "d/d$v($shown)"
        val constant = because("la derivada de una constante es 0")
        if (v !in term.variables()) return "$head = 0$constant"
        val value = evaluate(term)
        val terms = SymbolicValue.termsOf(value)
        if (terms.keys.any { m -> m.keys.any { it != v } }) return null
        val result = Calculus.derivative(value, v)
        if (terms.size <= 1) {
            val (m, c) = terms.entries.singleOrNull()?.toPair() ?: return "$head = 0$constant"
            val k = (c as? ExactValue)?.rational ?: return null
            val n = m[v] ?: 0
            val again = show(value)
            val start = if (norm(again) != norm(shown)) "$head = d/d$v($again)" else head
            if (n == 0) return "$start = 0$constant"
            val mid = if (k != Rational.ONE && n != 1) {
                factor(k) + "·" + (if (n < 0) "(−${-n})" else "$n") + "·" + v + (if (n - 1 == 1) "" else sup(n - 1))
            } else null
            return "$start = " + (mid?.let { "$it = " } ?: "") + show(result) + because("regla de la potencia: (${v}ⁿ)′ = n·${v}ⁿ⁻¹")
        }
        val (scale, core) = if (term is Expr.Bin && term.op == '*' && v !in term.left.variables()) term.left to term.right else null to term
        val pre = scale?.let { s -> render(s).let { if (precedence(s) < 4) "($it)" else it } + "·" }.orEmpty()
        if (core is Expr.Pow) {
            val k = runCatching { exponent(core.exponent) }.getOrNull()
            if (k != null && k >= 2 && v in core.base.variables()) {
                val u = evaluate(core.base)
                if (SymbolicValue.termsOf(u).size > 1) {
                    val du = show(Calculus.derivative(u, v))
                    val mid = pre + "$k·(${show(u)})" + (if (k - 1 == 1) "" else sup(k - 1)) + (if (du == "1") "" else "·" + wrapSum(du))
                    return "$head = $mid = ${show(result)}" + because("regla de la cadena: (uⁿ)′ = n·uⁿ⁻¹·u′, con u = ${show(u)}")
                }
            }
        }
        if (scale == null && core is Expr.Bin && core.op == '*' && v in core.left.variables() && v in core.right.variables()) {
            val f = evaluate(core.left)
            val g = evaluate(core.right)
            val mid = "${wrapSum(show(Calculus.derivative(f, v)))}·${wrapSum(show(g))} + ${wrapSum(show(f))}·${wrapSum(show(Calculus.derivative(g, v)))}"
            return "$head = $mid = ${show(result)}" + because("regla del producto: (f·g)′ = f′·g + f·g′")
        }
        return "$head = d/d$v(${show(value)}) = ${show(result)}" + because("se opera y se deriva término a término")
    }

    private fun evaluate(e: Expr): MathValue = Evaluator.evaluate(e) { SymbolicValue.variable(it) }

    // --- Ecuaciones de una letra ---

    private fun equationSteps(text: String, context: List<String>, complex: Boolean): List<String>? {
        if (text.count { it == '=' } != 1 || DECIMAL.containsMatchIn(text)) return null
        val lt = text.substringBefore('=')
        val rt = text.substringAfter('=')
        if (lt.isBlank() || rt.isBlank()) return null
        val values = VariableGraph.evaluate(context + listOf(lt, rt, "($lt)-($rt)"), symbolic = true).takeLast(3)
            .map { (it as? LineResult.Value)?.value ?: return null }
        val (lv, rv, dv) = values
        val x = letters(dv).singleOrNull() ?: return null
        val p = Poly.of(dv, x) ?: return null
        val lp = Poly.of(lv, x) ?: return null
        val rp = Poly.of(rv, x) ?: return null
        val left = MathParser.parseExpression(lt)
        val right = MathParser.parseExpression(rt)
        val original = render(left) + " = " + render(right)
        val substituted = (left.variables() + right.variables()).any { it != x }
        return when (p.degree) {
            1 -> linear(x, lp, rp, original, substituted)
            2 -> quadratic(x, p, rp, original, substituted, complex)
            else -> null
        }
    }

    private fun linear(x: String, lp: Poly, rp: Poly, original: String, substituted: Boolean): List<String>? {
        if (lp.degree > 1 || rp.degree > 1) return null
        val out = mutableListOf<String>()
        fun side(c: Rational, d: Rational) = sum(listOf(tm(c, x), tm(d)))
        val canonical = "${side(lp[1], lp[0])} = ${side(rp[1], rp[0])}"
        if (norm(canonical) != norm(original)) {
            out += canonical + because(if (substituted) "se reemplazan los valores conocidos y se opera en cada miembro" else "se opera en cada miembro")
        }
        var a = lp[1]
        var b = lp[0]
        var c = rp[1]
        var d = rp[0]
        if (a.isZero) {
            // La letra solo está a la derecha: se dan vuelta los miembros.
            val oldB = b
            a = c; b = d; c = Rational.ZERO; d = oldB
            out += "${side(a, b)} = ${num(d)}" + because("se dan vuelta los miembros")
        }
        val k = a - c
        val m = d - b
        if (k.isZero) return null
        if (!b.isZero || !c.isZero) {
            val reasons = mutableListOf<String>()
            if (!c.isZero) reasons += "el ${mono(c, x)} pasa ${if (c.signum > 0) "restando" else "sumando"} al primer miembro"
            if (!b.isZero) reasons += "el ${num(b.abs())} pasa ${if (b.signum > 0) "restando" else "sumando"} al segundo miembro"
            out += "${sum(listOf(tm(a, x), tm(-c, x)))} = ${sum(listOf(tm(d), tm(-b)))}" + because(reasons.joinToString(" y "))
            if (!c.isZero || (!d.isZero && !b.isZero)) out += "${sum(listOf(tm(k, x)))} = ${num(m)}" + because("se opera en cada miembro")
        }
        when {
            k == Rational.ONE -> Unit
            k == -Rational.ONE -> out.add("$x = ${num(-m)}" + because("se cambia el signo de los dos miembros"))
            k.isInteger && m.isInteger -> out.add("$x = ${num(m)}/${paren(k)}" + because("el ${num(k)} que multiplica pasa dividiendo"))
            k.isInteger -> out.add("$x = ${num(m)} ÷ ${paren(k)}" + because("el ${num(k)} que multiplica pasa dividiendo"))
            k.num == BigInteger.ONE -> out.add("$x = ${factor(m)}·${k.den}" + because("el ${k.den} que divide pasa multiplicando"))
            else -> out.add("$x = ${factor(m)}·${factor(Rational.ONE / k)}" +
                because("se multiplica por el inverso de ${num(k)}, que es ${num(Rational.ONE / k)}"))
        }
        val last = "$x = ${num(m / k)}"
        if (out.lastOrNull()?.substringBefore(SEP) != last) out += last
        return out.takeIf { it.size > 1 }
    }

    private fun quadratic(x: String, p: Poly, rp: Poly, original: String, substituted: Boolean, complex: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun poly(q: Poly) = sum(listOf(tm(q[2], "$x²"), tm(q[1], x), tm(q[0]))) + " = 0"
        if (norm(poly(p)) != norm(original)) {
            out += poly(p) + because(when {
                substituted -> "se reemplazan los valores conocidos y se pasa todo al primer miembro"
                rp.isZero -> "se opera en el primer miembro"
                else -> "se pasa todo al primer miembro"
            })
        }
        var q = p
        val lcm = p.c.fold(BigInteger.ONE) { acc, r -> acc / acc.gcd(r.den) * r.den }
        if (lcm != BigInteger.ONE) {
            q = Poly(p.c.map { it * Rational.of(lcm) })
            out += poly(q) + because("se multiplica todo por $lcm para sacar los denominadores")
        }
        val a = q[2].num
        val b = q[1].num
        val c = q[0].num
        out += "a = ${whole(a)}, b = ${whole(b)}, c = ${whole(c)}" + because("a acompaña a $x², b a $x y c es el término independiente")
        val b2 = b * b
        val ac4 = FOUR * a * c
        val disc = b2 - ac4
        val square = if (b.signum() < 0) "(${whole(b)})²" else "$b²"
        val difference = if (ac4.signum() >= 0) "$b2 − $ac4" else "$b2 + ${ac4.negate()}"
        out += "Δ = b² − 4ac = $square − 4·${wholeParen(a)}·${wholeParen(c)} = $difference = ${whole(disc)}"
        val den = TWO * a
        val nb = b.negate()
        when {
            disc.signum() < 0 -> {
                out += "Δ < 0: no hay raíces reales."
                if (complex) {
                    val (k, m) = squareFree(disc.negate())
                    out += "Con números imaginarios: √Δ = √(${whole(disc)}) = ${surd(k, m, "i")}"
                    out += "$x = (−b ± √Δ)/2a = " + roots(nb, k, m, den, "i")
                }
            }
            disc.signum() == 0 -> {
                out += "Δ = 0: hay una sola raíz (doble)."
                val raw = "${whole(nb)}/${wholeParen(den)}"
                val r = num(Rational.of(nb, den))
                out += "$x = −b/2a = $raw" + (if (raw != r) " = $r" else "")
            }
            else -> {
                out += "Δ > 0: hay dos raíces reales."
                out += "$x = (−b ± √Δ)/2a = (${whole(nb)} ± √$disc)/${wholeParen(den)}"
                val s = isqrt(disc)
                if (s * s == disc) {
                    out += "${x}₁ = (${whole(nb)} + $s)/${wholeParen(den)} = ${num(Rational.of(nb + s, den))}"
                    out += "${x}₂ = (${whole(nb)} − $s)/${wholeParen(den)} = ${num(Rational.of(nb - s, den))}"
                } else {
                    val (k, m) = squareFree(disc)
                    if (k != BigInteger.ONE) out += "√$disc = ${k}√$m" + because("se simplifica la raíz")
                    val r = roots(nb, k, m, den, "")
                    if (k != BigInteger.ONE || r.contains(" = ")) out += "$x = $r"
                }
            }
        }
        return out
    }

    /** `k√m` (con [suffix] = «i», imaginario): «2√3», «√5», «2i», «i». */
    private fun surd(k: BigInteger, m: BigInteger, suffix: String): String {
        val core = (if (m == BigInteger.ONE) "" else "√$m") + suffix
        return if (k == BigInteger.ONE && core.isNotEmpty()) core else "$k$core"
    }

    /** (nb ± k√m)/den y, si se puede, simplificado: «(0 ± 2√2)/2 = ±√2». */
    private fun roots(nb: BigInteger, k: BigInteger, m: BigInteger, den: BigInteger, suffix: String): String {
        val raw = "(${whole(nb)} ± ${surd(k, m, suffix)})/${wholeParen(den)}"
        val g = nb.gcd(k).gcd(den)
        var b2 = nb / g
        val k2 = k / g
        var d2 = den / g
        if (d2.signum() < 0) { b2 = b2.negate(); d2 = d2.negate() }
        val s = surd(k2, m, suffix)
        val inner = if (b2.signum() == 0) "±$s" else "${whole(b2)} ± $s"
        val simple = when {
            d2 == BigInteger.ONE -> inner
            b2.signum() == 0 -> "$inner/$d2"
            else -> "($inner)/$d2"
        }
        return if (simple == raw) raw else "$raw = $simple"
    }

    /** n = k²·m con m sin cuadrados (hasta donde se puede probar rápido). */
    private fun squareFree(n: BigInteger): Pair<BigInteger, BigInteger> {
        var m = n
        var k = BigInteger.ONE
        var f = TWO
        var steps = 0
        while (f * f <= m && steps < 200_000) {
            val sq = f * f
            while (m.mod(sq).signum() == 0) { m /= sq; k *= f }
            f += BigInteger.ONE
            steps++
        }
        return k to m
    }

    private fun isqrt(n: BigInteger): BigInteger {
        if (n.signum() <= 0) return BigInteger.ZERO
        var x = BigInteger.ONE.shiftLeft(n.bitLength() / 2 + 1)
        while (true) {
            val y = (x + n / x).shiftRight(1)
            if (y >= x) return x
            x = y
        }
    }

    // --- Sistemas 2×2 ---

    private fun systemSteps(equations: List<String>, context: List<String>): List<String>? {
        if (equations.size != 2 || equations.any { e -> e.count { it == '=' } != 1 || DECIMAL.containsMatchIn(e) }) return null
        val diffs = equations.map { "(" + it.substringBefore('=') + ")-(" + it.substringAfter('=') + ")" }
        val values = VariableGraph.evaluate(context + diffs, symbolic = true).takeLast(2)
            .map { (it as? LineResult.Value)?.value ?: return null }
        val names = values.flatMap { letters(it) }.toSortedSet().toList()
        if (names.size != 2) return null
        val x = names[0]
        val y = names[1]
        // Cada ecuación como a·x + b·y = c.
        val rows = values.map { v ->
            var a = Rational.ZERO
            var b = Rational.ZERO
            var c = Rational.ZERO
            for ((m, coef) in SymbolicValue.termsOf(v)) {
                val r = (coef as? ExactValue)?.rational ?: return null
                when (m) {
                    emptyMap<String, Int>() -> c = -r
                    mapOf(x to 1) -> a = r
                    mapOf(y to 1) -> b = r
                    else -> return null
                }
            }
            listOf(a, b, c)
        }
        val (a1, b1, c1) = rows[0]
        val (a2, b2, c2) = rows[1]
        val det = a1 * b2 - a2 * b1
        if (det.isZero) return null
        val xv = (c1 * b2 - c2 * b1) / det
        val yv = (a1 * c2 - a2 * c1) / det
        if (Polynomials.solveLinear(rows, 2) != listOf(xv, yv)) return null

        val out = mutableListOf<String>()
        fun eq(a: Rational, b: Rational, c: Rational) = "${sum(listOf(tm(a, x), tm(b, y)))} = ${num(c)}"
        out += "(1) ${eq(a1, b1, c1)}"
        out += "(2) ${eq(a2, b2, c2)}"
        val coef = listOf(listOf(a1, b1), listOf(a2, b2))
        val consts = listOf(c1, c2)
        val cells = listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        // Sustitución si alguna letra tiene coeficiente ±1 (o si una ecuación tiene una sola letra); si no, reducción.
        val pick = cells.firstOrNull { (i, j) -> coef[i][j].abs() == Rational.ONE }
            ?: cells.firstOrNull { (i, j) -> coef[i][j].isZero }?.let { (i, j) -> i to 1 - j }
        if (pick != null) {
            val (i, j) = pick
            val u = names[j]
            val w = names[1 - j]
            val cu = coef[i][j]
            val cw = coef[i][1 - j]
            val other = 1 - i
            val ak = coef[other][j]
            val bk = coef[other][1 - j]
            val ck = consts[other]
            val const = consts[i] / cu
            val slope = -cw / cu
            val expr = sum(listOf(tm(const), tm(slope, w)))
            out += "Método de sustitución: de (${i + 1}) se despeja $u: $u = $expr"
            val kk = ak * slope + bk
            val pp = ak * const
            if (!ak.isZero) {
                val uPart = when (ak) {
                    Rational.ONE -> "($expr)"
                    -Rational.ONE -> "−($expr)"
                    // «1/2(3 − y)» se lee como 1/(2(3 − y)): la fracción va entre paréntesis.
                    else -> if (ak.den != BigInteger.ONE) "(${num(ak)})·($expr)" else "${num(ak)}($expr)"
                }
                val wPart = if (bk.isZero) "" else (if (bk.signum > 0) " + " else " − ") + mono(bk, w)
                out += "Se reemplaza en (${other + 1}): $uPart$wPart = ${num(ck)}"
                out += "${sum(listOf(tm(kk, w), tm(pp)))} = ${num(ck)}" + because("se aplica la propiedad distributiva y se agrupan los términos")
                if (!pp.isZero) out += "${sum(listOf(tm(kk, w)))} = ${num(ck - pp)}" +
                    because("el ${num(pp.abs())} pasa ${if (pp.signum > 0) "restando" else "sumando"}")
            }
            if (kk.isZero) return null
            val wv = (ck - pp) / kk
            if (kk != Rational.ONE) out += "$w = ${num(wv)}" + because(divide(kk))
            val uv = const + slope * wv
            if (!slope.isZero) {
                val times = if (slope.abs() == Rational.ONE) paren(wv) else "${num(slope.abs())}·${factor(wv)}"
                val back = if (const.isZero) (if (slope.signum < 0) "−" else "") + times
                    else "${num(const)} ${if (slope.signum < 0) "−" else "+"} $times"
                out += "Se reemplaza $w = ${num(wv)} en $u = $expr: $u = $back = ${num(uv)}"
            }
            if ((if (j == 0) uv to wv else wv to uv) != (xv to yv)) return null
        } else {
            val m1: Rational
            val m2: Rational
            if (a1.isInteger && a2.isInteger) {
                val p = a1.num.abs()
                val q = a2.num.abs()
                val l = Rational.of(p / p.gcd(q) * q)
                m1 = l / a1
                m2 = l / a2
            } else {
                m1 = a2
                m2 = a1
            }
            val how = listOfNotNull(
                "(1) por ${num(m1)}".takeIf { m1 != Rational.ONE },
                "(2) por ${num(m2)}".takeIf { m2 != Rational.ONE },
            )
            out += "Método de reducción: " +
                if (how.isEmpty()) "los coeficientes de $x ya son iguales" else "se multiplica ${how.joinToString(" y ")} para igualar los coeficientes de $x"
            if (m1 != Rational.ONE) out += "(1)·${factor(m1)}: ${eq(a1 * m1, b1 * m1, c1 * m1)}"
            if (m2 != Rational.ONE) out += "(2)·${factor(m2)}: ${eq(a2 * m2, b2 * m2, c2 * m2)}"
            val bb = b1 * m1 - b2 * m2
            val cc = c1 * m1 - c2 * m2
            if (bb.isZero) return null
            out += "Se resta (1) − (2): ${sum(listOf(tm(bb, y)))} = ${num(cc)}" + because("se elimina la $x")
            val y0 = cc / bb
            if (bb != Rational.ONE) out += "$y = ${num(y0)}" + because(divide(bb))
            val times = if (b1.abs() == Rational.ONE) paren(y0) else "${num(b1.abs())}·${factor(y0)}"
            out += "Se reemplaza $y = ${num(y0)} en (1): ${sum(listOf(tm(a1, x)))} ${if (b1.signum < 0) "−" else "+"} $times = ${num(c1)}"
            val rest = c1 - b1 * y0
            out += "${sum(listOf(tm(a1, x)))} = ${num(rest)}" + because("se opera y se pasa al segundo miembro")
            if (a1 != Rational.ONE) out += "$x = ${num(rest / a1)}" + because(divide(a1))
            if (y0 != yv || rest / a1 != xv) return null
        }
        out += "$x = ${num(xv)} ; $y = ${num(yv)}"
        return out
    }

    private fun divide(k: Rational): String = when {
        k == -Rational.ONE -> "se cambia el signo de los dos miembros"
        k.isInteger -> "el ${num(k)} que multiplica pasa dividiendo"
        else -> "se divide por ${num(k)} (se multiplica por su inverso, ${num(Rational.ONE / k)})"
    }

    // --- Texto ---

    /** Letras sin valor de un valor (π no cuenta). */
    private fun letters(v: MathValue): Set<String> =
        SymbolicValue.termsOf(v).keys.flatMap { it.keys }.filter { it != SymbolicValue.PI_LETTER }.toSet()

    private fun whole(n: BigInteger): String = if (n.signum() < 0) "−${n.negate()}" else n.toString()

    private fun wholeParen(n: BigInteger): String = if (n.signum() < 0) "(${whole(n)})" else n.toString()

    private fun num(r: Rational): String = if (r.isInteger) whole(r.num) else whole(r.num) + "/" + r.den

    /** Un número después de un signo: entre paréntesis si es negativo. */
    private fun paren(r: Rational): String = if (r.signum < 0) "(${num(r)})" else num(r)

    /** Un número que multiplica: entre paréntesis si es negativo o una fracción. */
    private fun factor(r: Rational): String = if (r.signum < 0 || !r.isInteger) "(${num(r)})" else num(r)

    private fun tm(c: Rational, letter: String? = null): Pair<Rational, String?> = c to letter

    /** Un término con letra sin su signo: «3x», «x», «x/2», «3x²/4». */
    private fun mono(c: Rational, letter: String): String {
        val a = c.abs()
        return (if (a.num == BigInteger.ONE) "" else a.num.toString()) + letter + (if (a.isInteger) "" else "/" + a.den)
    }

    /** Suma de términos c·letra (letra null: un número), sin los nulos: «2x − 3», «−x + 1/2», «0». */
    private fun sum(terms: List<Pair<Rational, String?>>): String {
        val sb = StringBuilder()
        for ((c, letter) in terms) {
            if (c.isZero) continue
            val body = if (letter == null) num(c.abs()) else mono(c, letter)
            if (sb.isEmpty()) { if (c.signum < 0) sb.append('−') } else sb.append(if (c.signum < 0) " − " else " + ")
            sb.append(body)
        }
        return if (sb.isEmpty()) "0" else sb.toString()
    }

    private fun sup(k: Int): String =
        (if (k < 0) "⁻" else "") + kotlin.math.abs(k).toString().map { SUPERSCRIPTS[it - '0'] }.joinToString("")

    private fun show(v: MathValue): String = v.display().replace('-', '−')

    private fun wrapSum(s: String) = if (s.contains(' ') || s.startsWith('−')) "($s)" else s

    /** Para comparar dos escrituras de lo mismo: sin espacios ni «·». */
    private fun norm(s: String) = s.filter { it != ' ' && it != '·' }

    private fun precedence(e: Expr): Int = when (e) {
        is Expr.Bin -> if (e.op == '+' || e.op == '-') 1 else 2
        is Expr.Neg -> 1
        is Expr.Num -> when {
            e.value.signum < 0 -> 1
            e.value.isInteger -> 4
            else -> 2
        }
        is Expr.Pow -> 3
        else -> 4
    }

    /** Una expresión como se escribe en la hoja: «2x + 3», «(2x + 1)³», «x·(x + 1)». */
    private fun render(e: Expr): String = when (e) {
        is Expr.Num -> num(e.value)
        is Expr.Var -> e.name
        Expr.Pi -> "π"
        is Expr.Neg -> "−" + wrap(e.operand, precedence(e.operand) < 2)
        is Expr.Bin -> {
            val p = if (e.op == '+' || e.op == '-') 1 else 2
            val left = e.left
            val right = e.right
            val l = wrap(left, left is Expr.Bin && precedence(left) < p)
            val negative = right is Expr.Neg || (right is Expr.Num && right.value.signum < 0)
            val r = wrap(right, negative || precedence(right) < p || (precedence(right) == p && (e.op == '-' || e.op == '/')))
            when (e.op) {
                '+' -> "$l + $r"
                '-' -> "$l − $r"
                '*' -> if (implicit(l, r)) l + r else "$l·$r"
                else -> "$l/$r"
            }
        }
        is Expr.Pow -> {
            val base = wrap(e.base, precedence(e.base) < 4)
            val k = (e.exponent as? Expr.Num)?.value?.takeIf { it.isInteger && it.num.bitLength() < 31 }
            if (k != null) base + sup(k.num.toInt()) else base + "^" + wrap(e.exponent, precedence(e.exponent) < 4)
        }
        is Expr.Sqrt -> "√" + wrap(e.operand, precedence(e.operand) < 4)
        else -> throw Unexplained()
    }

    private fun wrap(e: Expr, parens: Boolean) = render(e).let { if (parens) "($it)" else it }

    /** Producto escrito pegado: un número delante de una letra o un paréntesis («3x», «2(x + 1)»), o «(…)(…)». */
    private fun implicit(l: String, r: String): Boolean {
        val first = r.firstOrNull() ?: return false
        val digits = l.removePrefix("−")
        val number = digits.isNotEmpty() && digits.all { it.isDigit() }
        return (number && (first.isLetter() || first == '(' || first == '√')) || (l.endsWith(")") && first == '(')
    }
}
