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
        is Expr.Num, Expr.Pi -> Unit
    }
}

/**
 * Parser estricto de una línea. Gramática:
 * ```
 * expr    = term (('+' | '-') term)*
 * term    = unary (('*' | '/') unary | implícita)*   implícita: sigue identificador, π, '(' o √
 * unary   = ('+' | '-') unary | power
 * power   = postfix ('^' unary)?                    asociativa a la derecha
 * postfix = primary superíndice*
 * primary = número | identificador | π | '(' expr ')' | '√' postfix
 * ```
 * La multiplicación implícita nunca toma un número a la derecha: "2 3" es un error, no 6.
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
            var e = unary()
            while (true) {
                e = when (peek().kind) {
                    Kind.TIMES -> { next(); Expr.Bin('*', e, unary()) }
                    Kind.DIVIDE -> { next(); Expr.Bin('/', e, unary()) }
                    Kind.IDENT, Kind.PI, Kind.LPAREN, Kind.SQRT -> Expr.Bin('*', e, power())
                    else -> return e
                }
            }
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
            while (peek().kind == Kind.SUPERSCRIPT) {
                e = Expr.Pow(e, Expr.Num(Rational.parseDecimal(next().text)))
            }
            return e
        }

        fun primary(): Expr {
            val t = next()
            return when (t.kind) {
                Kind.NUMBER -> Expr.Num(Rational.parseDecimal(t.text))
                Kind.IDENT -> Expr.Var(t.text)
                Kind.PI -> Expr.Pi
                Kind.SQRT -> Expr.Sqrt(postfix())
                Kind.LPAREN -> {
                    val e = expr()
                    val close = next()
                    if (close.kind != Kind.RPAREN) syntax("falta cerrar un paréntesis", close.pos)
                    e
                }
                Kind.EOF -> syntax("la expresión está incompleta", t.pos)
                Kind.SUPERSCRIPT -> syntax("el exponente no tiene base", t.pos)
                else -> syntax("no se esperaba '${t.text}'", t.pos)
            }
        }
    }

    private enum class Kind { NUMBER, IDENT, PI, PLUS, MINUS, TIMES, DIVIDE, CARET, SUPERSCRIPT, SQRT, LPAREN, RPAREN, EQUALS, EOF }

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
                    '/', '÷' -> Kind.DIVIDE
                    '^' -> Kind.CARET
                    '√' -> Kind.SQRT
                    '(' -> Kind.LPAREN
                    ')' -> Kind.RPAREN
                    '=' -> Kind.EQUALS
                    else -> syntax("carácter inesperado '$c'", i)
                }
                out += Token(kind, c.toString(), i)
                i++
            }
            out += Token(Kind.EOF, "", s.length)
            return out
        }
    }

    private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private fun isSuperscript(c: Char) = c == '⁻' || c in SUPERSCRIPT_DIGITS

    private fun syntax(detail: String, pos: Int): Nothing = throw MathException(MathError.Syntax(detail, pos))
}
