package io.trazo.core.math

/** Árbol tipado de una expresión. No hay "texto suelto": todo lo que no encaja es un error de sintaxis. */
sealed interface Expr {
    data class Num(val value: Rational) : Expr
    data class Var(val name: String) : Expr
    data object Pi : Expr
    data class Neg(val operand: Expr) : Expr
    data class Bin(val op: Char, val left: Expr, val right: Expr) : Expr
    data class Pow(val base: Expr, val exponent: Expr) : Expr
    data class Sqrt(val operand: Expr) : Expr
    /** `(a, b, …)`: un par o una terna; las cuentas se hacen elemento a elemento. */
    data class Tuple(val items: List<Expr>) : Expr
    /** `d/dx (…)`: derivada respecto de [variable]. */
    data class Deriv(val variable: String, val operand: Expr) : Expr
    /** `∫ … dx` o `∫_a^b … dx`. */
    data class Integral(val variable: String, val operand: Expr, val lower: Expr?, val upper: Expr?) : Expr
    /** Función con nombre: `det(A)`. */
    data class Call(val name: String, val argument: Expr) : Expr
}

/** Lo que dice una línea del cuaderno. */
sealed interface Statement {
    data object Empty : Statement
    /** `x = expr` */
    data class Assign(val name: String, val expr: Expr) : Statement
    /** `expr =` o una expresión sin igual. */
    data class Query(val expr: Expr) : Statement
    /** `a = b` con algo más que una variable a la izquierda. */
    data class Check(val left: Expr, val right: Expr) : Statement
}

/** Variables libres de una expresión, en orden de aparición. */
fun Expr.variables(): Set<String> = linkedSetOf<String>().also { collect(this, it) }

private fun collect(e: Expr, out: MutableSet<String>) {
    when (e) {
        is Expr.Var -> out += e.name
        is Expr.Neg -> collect(e.operand, out)
        is Expr.Bin -> { collect(e.left, out); collect(e.right, out) }
        is Expr.Pow -> { collect(e.base, out); collect(e.exponent, out) }
        is Expr.Sqrt -> collect(e.operand, out)
        is Expr.Tuple -> e.items.forEach { collect(it, out) }
        is Expr.Deriv -> out += linkedSetOf<String>().also { collect(e.operand, it) } - e.variable
        is Expr.Integral -> {
            e.lower?.let { collect(it, out) }; e.upper?.let { collect(it, out) }
            out += linkedSetOf<String>().also { collect(e.operand, it) } - e.variable
        }
        is Expr.Call -> collect(e.argument, out)
        is Expr.Num, Expr.Pi -> Unit
    }
}

/**
 * Parser estricto de una línea. Gramática:
 * ```
 * expr    = term (('+' | '-') term)*
 * term    = product (('*' | '/') product)*
 * product = unary implícita*                        implícita: sigue identificador, π, '(' o √
 * unary   = ('+' | '-') unary | power
 * power   = postfix ('^' unary)?                    asociativa a la derecha
 * postfix = primary (superíndice | '%')*               50% = 50/100
 * primary = número | identificador | π | '(' expr (';' expr)* ')' | '√' postfix    con ';', un par
 *         | '[' expr ((';' | número) expr)* ']'      vector; [[1;2];[3;4]] es una matriz
 *         | 'd/dx' power | '∫' ('_' primary '^' primary)? expr 'dx' | 'det' '(' expr ')'
 * ```
 * La multiplicación implícita nunca toma un número a la derecha: "2 3" es un error, no 6.
 * Lo escrito pegado es un solo término, más fuerte que × y ÷: "2x/5y" es (2x)/(5y), como se lee en la hoja.
 */
object MathParser {

    fun parseStatement(line: String): Statement {
        val tokens = Lexer(line).tokens()
        if (tokens.size == 1) return Statement.Empty
        val equals = tokens.filter { it.kind == Kind.EQUALS }
        if (equals.size > 1) syntax("hay más de un signo igual", equals[1].pos)
        if (equals.isEmpty()) return Statement.Query(parseExpr(tokens, line.length))

        val split = tokens.indexOf(equals.single())
        val left = tokens.subList(0, split)
        val right = tokens.subList(split + 1, tokens.size - 1)
        if (left.isEmpty()) syntax("falta la expresión antes del igual", equals.single().pos)
        if (right.isEmpty()) return Statement.Query(parseExpr(left + eof(equals.single().pos), equals.single().pos))
        val rightExpr = parseExpr(right + eof(line.length), line.length)
        val single = left.singleOrNull()
        if (single != null && single.kind == Kind.IDENT) return Statement.Assign(single.text, rightExpr)
        return Statement.Check(parseExpr(left + eof(equals.single().pos), equals.single().pos), rightExpr)
    }

    fun parseExpression(text: String): Expr = parseExpr(Lexer(text).tokens(), text.length)

    private fun parseExpr(tokens: List<Token>, end: Int): Expr {
        val p = Parser(tokens)
        val e = p.expr()
        val rest = p.peek()
        if (rest.kind != Kind.EOF) {
            syntax(if (rest.kind == Kind.RPAREN) "sobra un paréntesis de cierre" else "sobra '${rest.text}'", rest.pos)
        }
        return e
    }

    private fun eof(pos: Int) = Token(Kind.EOF, "", pos)

    private class Parser(val tokens: List<Token>) {
        var i = 0
        fun peek() = tokens[i]
        fun next() = tokens[i++]

        fun expr(): Expr {
            var e = term()
            while (peek().kind == Kind.PLUS || peek().kind == Kind.MINUS) {
                val op = if (next().kind == Kind.PLUS) '+' else '-'
                e = Expr.Bin(op, e, term())
            }
            return e
        }

        fun term(): Expr {
            var e = product()
            while (true) {
                e = when (peek().kind) {
                    Kind.TIMES -> { next(); Expr.Bin('*', e, product()) }
                    Kind.DIVIDE -> { next(); Expr.Bin('/', e, product()) }
                    else -> return e
                }
            }
        }

        fun product(): Expr {
            var e = unary()
            while (peek().kind in setOf(Kind.IDENT, Kind.PI, Kind.LPAREN, Kind.LBRACK, Kind.SQRT, Kind.INTEGRAL)) e = Expr.Bin('*', e, power())
            return e
        }

        fun unary(): Expr = when (peek().kind) {
            Kind.MINUS -> { next(); Expr.Neg(unary()) }
            Kind.PLUS -> { next(); unary() }
            else -> power()
        }

        fun power(): Expr {
            val base = postfix()
            if (peek().kind != Kind.CARET) return base
            next()
            return Expr.Pow(base, unary())
        }

        fun postfix(): Expr {
            var e = primary()
            while (true) {
                e = when (peek().kind) {
                    Kind.SUPERSCRIPT -> Expr.Pow(e, Expr.Num(Rational.parseDecimal(next().text)))
                    Kind.PERCENT -> { next(); Expr.Bin('/', e, Expr.Num(Rational.of(100))) }
                    else -> break
                }
            }
            return e
        }

        fun primary(): Expr {
            derivative()?.let { return it }
            val t = next()
            return when (t.kind) {
                Kind.NUMBER -> Expr.Num(Rational.parseDecimal(t.text))
                Kind.IDENT -> Expr.Var(t.text)
                Kind.PI -> Expr.Pi
                Kind.SQRT -> Expr.Sqrt(postfix())
                Kind.LPAREN -> {
                    val items = mutableListOf(expr())
                    while (peek().kind == Kind.SEP) { next(); items += expr() }
                    val close = next()
                    if (close.kind != Kind.RPAREN) syntax("falta cerrar un paréntesis", close.pos)
                    items.singleOrNull() ?: Expr.Tuple(items)
                }
                Kind.LBRACK -> {
                    val items = mutableListOf(expr())
                    while (peek().kind == Kind.SEP || peek().kind == Kind.NUMBER || peek().kind == Kind.LBRACK) {
                        if (peek().kind == Kind.SEP) next()
                        items += expr()
                    }
                    val close = next()
                    if (close.kind != Kind.RBRACK) syntax("falta cerrar un corchete", close.pos)
                    items.singleOrNull() ?: Expr.Tuple(items)
                }
                Kind.INTEGRAL -> integral(t)
                Kind.FUNCTION -> {
                    if (peek().kind != Kind.LPAREN && peek().kind != Kind.LBRACK) syntax("${t.text} necesita paréntesis", t.pos)
                    Expr.Call(t.text, postfix())
                }
                Kind.EOF -> syntax("la expresión está incompleta", t.pos)
                Kind.SUPERSCRIPT -> syntax("el exponente no tiene base", t.pos)
                Kind.PERCENT -> syntax("el % no tiene número", t.pos)
                else -> syntax("no se esperaba '${t.text}'", t.pos)
            }
        }

        private fun isD(t: Token) = t.kind == Kind.IDENT && t.text == "d"

        /** `d/dx`, también con los paréntesis que agrega la fracción escrita a mano: `((d)/(d x))`. */
        private fun derivative(): Expr? {
            val start = i
            fun fail(): Expr? { i = start; return null }
            var depth = 0
            while (peek().kind == Kind.LPAREN) { next(); depth++ }
            if (!isD(peek())) return fail()
            next()
            while (peek().kind == Kind.RPAREN && depth > 0) { next(); depth-- }
            if (peek().kind != Kind.DIVIDE) return fail()
            next()
            while (peek().kind == Kind.LPAREN) { next(); depth++ }
            val variable = when {
                isD(peek()) && tokens[i + 1].kind == Kind.IDENT && tokens[i + 1].text.length == 1 -> { next(); next().text }
                peek().kind == Kind.IDENT && peek().text.length == 2 && peek().text[0] == 'd' -> next().text.substring(1)
                else -> return fail()
            }
            while (peek().kind == Kind.RPAREN && depth > 0) { next(); depth-- }
            if (depth != 0) return fail()
            if (peek().kind in setOf(Kind.EOF, Kind.EQUALS, Kind.RPAREN, Kind.RBRACK, Kind.SEP, Kind.PLUS, Kind.MINUS, Kind.TIMES, Kind.DIVIDE)) return fail()
            return Expr.Deriv(variable, power())
        }

        /** `∫_a^b f dx`: el integrando llega hasta su diferencial (contando las integrales de adentro). */
        private fun integral(sign: Token): Expr {
            var lower: Expr? = null
            var upper: Expr? = null
            if (peek().kind == Kind.UNDERSCORE) {
                next()
                lower = primary()
                upper = when (peek().kind) {
                    Kind.CARET -> { next(); unaryPrimary() }
                    Kind.SUPERSCRIPT -> Expr.Num(Rational.parseDecimal(next().text))
                    else -> syntax("a la integral le falta el límite de arriba", peek().pos)
                }
            }
            var depth = 0
            var pending = 0
            var j = i
            while (true) {
                val t = tokens[j]
                when (t.kind) {
                    Kind.LPAREN, Kind.LBRACK -> depth++
                    Kind.RPAREN, Kind.RBRACK -> depth--
                    Kind.INTEGRAL -> if (depth == 0) pending++
                    else -> Unit
                }
                if (depth < 0 || t.kind in setOf(Kind.EOF, Kind.EQUALS, Kind.SEP)) syntax("a la integral le falta el diferencial (dx)", t.pos)
                val differential = depth == 0 && when {
                    isD(t) -> tokens[j + 1].kind == Kind.IDENT && tokens[j + 1].text.length == 1
                    else -> t.kind == Kind.IDENT && t.text.length >= 2 && t.text[t.text.length - 2] == 'd'
                }
                if (differential) {
                    if (pending == 0) break
                    pending--
                }
                j++
            }
            val t = tokens[j]
            val body = tokens.subList(i, j).toMutableList()
            val variable: String
            if (isD(t)) { variable = tokens[j + 1].text; i = j + 2 } else {
                variable = t.text.takeLast(1)
                if (t.text.length > 2) body += Token(Kind.IDENT, t.text.dropLast(2), t.pos)
                i = j + 1
            }
            if (body.isEmpty()) syntax("la integral no tiene qué integrar", sign.pos)
            val p = Parser(body + Token(Kind.EOF, "", t.pos))
            val operand = p.expr()
            if (p.peek().kind != Kind.EOF) syntax("sobra '${p.peek().text}' en la integral", p.peek().pos)
            return Expr.Integral(variable, operand, lower, upper)
        }

        private fun unaryPrimary(): Expr = if (peek().kind == Kind.MINUS) { next(); Expr.Neg(primary()) } else primary()
    }

    private enum class Kind { NUMBER, IDENT, PI, PLUS, MINUS, TIMES, DIVIDE, CARET, SUPERSCRIPT, SQRT, LPAREN, RPAREN, LBRACK, RBRACK, SEP, EQUALS, PERCENT, INTEGRAL, UNDERSCORE, FUNCTION, EOF }

    private data class Token(val kind: Kind, val text: String, val pos: Int)

    private class Lexer(val s: String) {
        fun tokens(): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val start = i
                when {
                    c.isWhitespace() -> { i++; continue }
                    c.isDigit() -> {
                        while (i < s.length && s[i].isDigit()) i++
                        if (i + 1 < s.length && (s[i] == '.' || s[i] == ',') && s[i + 1].isDigit()) {
                            i++
                            while (i < s.length && s[i].isDigit()) i++
                        }
                        out += Token(Kind.NUMBER, s.substring(start, i), start)
                        continue
                    }
                    c.isLetter() && c != 'π' -> {
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_') && s[i] != 'π' && !isSuperscript(s[i])) i++
                        val word = s.substring(start, i)
                        out += Token(if (word == "pi") Kind.PI else Kind.IDENT, word, start)
                        continue
                    }
                    isSuperscript(c) -> {
                        val sb = StringBuilder()
                        if (c == '⁻') { sb.append('-'); i++ }
                        while (i < s.length && s[i] in SUPERSCRIPT_DIGITS) sb.append(SUPERSCRIPT_DIGITS.indexOf(s[i++]))
                        if (sb.isEmpty() || sb.toString() == "-") syntax("el exponente está incompleto", start)
                        out += Token(Kind.SUPERSCRIPT, sb.toString(), start)
                        continue
                    }
                }
                val kind = when (c) {
                    'π' -> Kind.PI
                    '+' -> Kind.PLUS
                    '-', '−' -> Kind.MINUS
                    '*', '×', '·', '⋅' -> Kind.TIMES
                    '/', '÷', ':' -> Kind.DIVIDE
                    '%' -> Kind.PERCENT
                    '^' -> Kind.CARET
                    '√' -> Kind.SQRT
                    '[' -> Kind.LBRACK
                    ']' -> Kind.RBRACK
                    '∫' -> Kind.INTEGRAL
                    '_' -> Kind.UNDERSCORE
                    '(' -> Kind.LPAREN
                    ')' -> Kind.RPAREN
                    ';' -> Kind.SEP
                    '=' -> Kind.EQUALS
                    else -> syntax("carácter inesperado '$c'", i)
                }
                out += Token(kind, c.toString(), i)
                i++
            }
            out += Token(Kind.EOF, "", s.length)
            return functions(out)
        }
    }

    /** «det» escrito pegado o letra por letra («d e t»), si le sigue un paréntesis. */
    private fun functions(tokens: List<Token>): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val spelled = (0 until 3).all { tokens.getOrNull(i + it)?.let { x -> x.kind == Kind.IDENT && x.text == "det"[it].toString() } == true }
            val open = { k: Int -> tokens.getOrNull(k)?.kind.let { it == Kind.LPAREN || it == Kind.LBRACK } }
            when {
                t.kind == Kind.IDENT && t.text in FUNCTIONS && open(i + 1) -> { out += t.copy(kind = Kind.FUNCTION); i++ }
                spelled && open(i + 3) -> { out += Token(Kind.FUNCTION, "det", t.pos); i += 3 }
                else -> { out += t; i++ }
            }
        }
        return out
    }

    private val FUNCTIONS = setOf("det")

    private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private fun isSuperscript(c: Char) = c == '⁻' || c in SUPERSCRIPT_DIGITS

    private fun syntax(detail: String, pos: Int): Nothing = throw MathException(MathError.Syntax(detail, pos))
}
