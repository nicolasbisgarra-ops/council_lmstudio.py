package io.trazo.core.math.tex

/**
 * Lector de la notación TeX con que contesta el chat del Modo IA. No es un motor tipográfico: arma un árbol
 * chico ([TexNode]) que [TexInk] dibuja con los patrones del usuario. Lo que no conoce no falla: queda como
 * texto suelto, para que una respuesta rara nunca rompa el chat.
 */
sealed interface TexNode {
    /** Cómo se separa de sus vecinos: un «+» lleva aire a los lados, un «(» no. */
    enum class Kind { ORD, BIN, REL, OPEN, CLOSE, PUNCT }

    data class Row(val items: List<TexNode>) : TexNode
    /** Un signo: [key] es el nombre del patrón («Más», «x», «alfa») o el carácter si no hay patrón. */
    data class Sym(val key: String, val kind: Kind = Kind.ORD) : TexNode
    /** Palabra derecha: sin, log, lím o un \text{…}. */
    data class Word(val text: String, val prose: Boolean = false) : TexNode
    data class Frac(val num: TexNode, val den: TexNode) : TexNode
    data class Root(val body: TexNode, val index: TexNode? = null) : TexNode
    data class Scripts(val base: TexNode, val sup: TexNode?, val sub: TexNode?) : TexNode
    /** Paréntesis que crecen con lo de adentro; null = sin ese lado («\left.»). */
    data class Fenced(val open: String?, val close: String?, val body: TexNode) : TexNode
    /** Sumatoria, integral, límite: [below] pone los límites debajo y encima en vez de al costado. */
    data class BigOp(val key: String, val below: Boolean, val word: String? = null) : TexNode
    data class Matrix(val rows: List<List<TexNode>>, val open: String?, val close: String?, val align: Align = Align.CENTER) : TexNode
    /** Un recuadro alrededor (\boxed): el resultado final. */
    data class Boxed(val body: TexNode) : TexNode
    data class Accent(val kind: AccentKind, val body: TexNode) : TexNode
    data class Space(val em: Float) : TexNode
    /** Punto: a media altura (·) o en la línea (.). */
    data class Dot(val center: Boolean) : TexNode

    enum class AccentKind { BAR, VEC, HAT }

    /** Columnas de una matriz: centradas, a la izquierda (cases) o de a pares derecha|izquierda (aligned, «&=»). */
    enum class Align { CENTER, LEFT, PAIRS }
}

/** Un trozo de un mensaje del chat: prosa o una fórmula (en su renglón propio si [display]). */
sealed interface TexPart {
    data class Prose(val text: String) : TexPart
    data class Formula(val source: String, val display: Boolean) : TexPart
}

object TexMath {
    /** Separa las fórmulas de la prosa: `$$…$$`, `\[…\]`, `\(…\)` y `$…$`. */
    fun split(message: String): List<TexPart> {
        val out = mutableListOf<TexPart>()
        val prose = StringBuilder()
        fun flushProse() { if (prose.isNotEmpty()) { out += TexPart.Prose(prose.toString()); prose.clear() } }
        var i = 0
        while (i < message.length) {
            val match = DELIMITERS.firstNotNullOfOrNull { (open, close, display) ->
                if (!message.startsWith(open, i)) return@firstNotNullOfOrNull null
                val from = i + open.length
                val end = message.indexOf(close, from)
                if (end < 0) {
                    // La IA a veces abre `$$` o `\[` y no lo cierra: la fórmula llega hasta el renglón en blanco o el final.
                    if (!display) return@firstNotNullOfOrNull null
                    val stop = message.indexOf("\n\n", from).let { if (it < 0) message.length else it }
                    val body = message.substring(from, stop)
                    // Solo si el resto parece TeX (un comando, llaves, ^ o _); si no, el delimitador queda como texto.
                    if (!LOOKS_TEX.containsMatchIn(body)) return@firstNotNullOfOrNull null
                    return@firstNotNullOfOrNull Triple(body, stop, true)
                }
                val body = message.substring(from, end)
                // Un «$» suelto (precio, «$ 5») no abre fórmula: tiene que pegarse a su contenido y cerrar en el párrafo.
                if (open == "$" && (body.isBlank() || body.first().isWhitespace() || body.last().isWhitespace() || "\n\n" in body)) {
                    return@firstNotNullOfOrNull null
                }
                Triple(body, end + close.length, display)
            }
            if (match == null) { prose.append(message[i]); i++; continue }
            flushProse()
            out += TexPart.Formula(match.first.trim(), match.third)
            i = match.second
        }
        flushProse()
        return out
    }

    private val LOOKS_TEX = Regex("""\\[A-Za-z]|[{}^_]""")

    private val DELIMITERS = listOf(
        Triple("$$", "$$", true), Triple("\\[", "\\]", true), Triple("\\(", "\\)", false), Triple("$", "$", false),
    )

    /** Lee [source]; nunca lanza. */
    fun parse(source: String): TexNode = Reader(source).document()

    private class Reader(private val s: String) {
        private var i = 0

        fun document(): TexNode {
            // Renglones sueltos con «\\» o columnas con «&» fuera de un entorno: se leen como un aligned.
            val rows = mutableListOf<List<TexNode>>()
            var cells = mutableListOf<TexNode>()
            var items = mutableListOf<TexNode>()
            while (i < s.length) {
                items += row(setOf()).items
                if (i >= s.length) break
                when {
                    s.startsWith("&", i) -> { i++; cells += Row(items); items = mutableListOf() }
                    s.startsWith("\\\\", i) -> { i += 2; skipSpacing(); cells += Row(items); items = mutableListOf(); rows += cells; cells = mutableListOf() }
                    // Un cierre de más («}», «\right)» suelto, un «\end» sin «\begin») se saltea.
                    else -> { val end = s.startsWith("\\end", i); i += stopLength(); if (end) rawGroup() }
                }
            }
            if (rows.isEmpty() && cells.isEmpty()) return Row(items)
            cells += Row(items)
            rows += cells
            val kept = rows.filter { r -> r.any { !(it is TexNode.Row && it.items.isEmpty()) } }
            if (kept.size == 1 && kept[0].size == 1) return kept[0][0]
            return TexNode.Matrix(kept.map { r -> r.map { c -> if (c is TexNode.Row && c.items.size == 1) c.items[0] else c } }, null, null,
                if (kept.any { it.size > 1 }) TexNode.Align.PAIRS else TexNode.Align.CENTER)
        }

        /** El espacio opcional después de un salto de renglón: «\\[8pt]». */
        private fun skipSpacing() {
            skipSpaces()
            if (i < s.length && s[i] == '[') {
                val end = s.indexOf(']', i)
                if (end > 0 && end - i < 16) i = end + 1
            }
        }

        private fun Row(items: List<TexNode>) = TexNode.Row(items)

        /** Lo que corta un renglón: «}», «&», «\\», «\right», «\end», y «]» dentro del índice de una raíz. */
        private fun atStop(stops: Set<String>): Boolean {
            skipSpaces()
            if (i >= s.length) return true
            return stops.any { s.startsWith(it, i) && (it.last() != 't' && it.last() != 'd' || !s.getOrNull(i + it.length).isLetterOrFalse()) }
        }

        private fun Char?.isLetterOrFalse() = this != null && this.isLetter()

        private fun stopLength(): Int = when {
            s.startsWith("\\\\", i) -> 2
            s.startsWith("\\right", i) -> 6
            s.startsWith("\\end", i) -> 4
            else -> 1
        }

        private val defaultStops = setOf("}", "&", "\\\\", "\\right", "\\end")

        fun row(extra: Set<String>): TexNode.Row {
            val stops = defaultStops + extra
            val items = mutableListOf<TexNode>()
            while (!atStop(stops)) {
                val atom = atom() ?: continue
                items += scripts(atom)
            }
            return Row(items)
        }

        private fun scripts(base: TexNode): TexNode {
            var sup: TexNode? = null
            var sub: TexNode? = null
            while (true) {
                skipSpaces()
                when {
                    i < s.length && s[i] == '^' && sup == null -> { i++; sup = argument() }
                    i < s.length && s[i] == '_' && sub == null -> { i++; sub = argument() }
                    i < s.length && s[i] == '\'' -> {
                        // f'(x): la prima es un exponente chico.
                        var n = 0
                        while (i < s.length && s[i] == '\'') { n++; i++ }
                        sup = Row(listOfNotNull(sup) + List(n) { TexNode.Sym("′") })
                    }
                    else -> break
                }
            }
            return if (sup == null && sub == null) base else TexNode.Scripts(base, sup, sub)
        }

        /** Un argumento: `{…}` o un solo átomo. */
        private fun argument(): TexNode {
            skipSpaces()
            if (i >= s.length) return Row(emptyList())
            if (s[i] == '{') return group()
            return atom() ?: Row(emptyList())
        }

        private fun group(): TexNode {
            i++ // {
            val r = row(emptySet())
            if (i < s.length && s[i] == '}') i++
            return if (r.items.size == 1) r.items[0] else r
        }

        /** Texto crudo entre llaves (para \text, \begin, \mathbb). */
        private fun rawGroup(): String {
            skipSpaces()
            if (i >= s.length || s[i] != '{') return ""
            var depth = 0
            val start = i + 1
            while (i < s.length) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { i++; return s.substring(start, i - 1) } }
                    '\\' -> i++
                }
                i++
            }
            return s.substring(start)
        }

        private fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun atom(): TexNode? {
            skipSpaces()
            if (i >= s.length) return null
            val c = s[i]
            return when {
                c == '{' -> group()
                c == '\\' -> command()
                c == '^' || c == '_' -> Row(emptyList())
                c == '}' -> { i++; null }
                c == '~' -> { i++; TexNode.Space(0.3f) }
                else -> { i++; char(c) }
            }
        }

        private fun command(): TexNode? {
            i++ // \
            if (i >= s.length) return null
            if (!s[i].isLetter()) {
                val c = s[i++]
                return when (c) {
                    ',' -> TexNode.Space(0.17f); ':', '>' -> TexNode.Space(0.22f); ';' -> TexNode.Space(0.28f)
                    '!' -> TexNode.Space(-0.1f); ' ' -> TexNode.Space(0.3f)
                    '{' -> TexNode.Sym("Abre llave", TexNode.Kind.OPEN); '}' -> TexNode.Sym("Cierra llave", TexNode.Kind.CLOSE)
                    '%' -> TexNode.Sym("Porcentaje"); '|' -> TexNode.Sym("Paralelo")
                    '$', '#', '&', '_' -> TexNode.Sym(c.toString())
                    else -> TexNode.Sym(c.toString())
                }
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> TexNode.Frac(argument(), argument())
                "binom" -> TexNode.Fenced("Abre paréntesis", "Cierra paréntesis", TexNode.Matrix(listOf(listOf(argument()), listOf(argument())), null, null))
                "sqrt" -> {
                    skipSpaces()
                    val index = if (i < s.length && s[i] == '[') { i++; row(setOf("]")).also { if (i < s.length && s[i] == ']') i++ } } else null
                    TexNode.Root(argument(), index?.takeIf { it.items.isNotEmpty() })
                }
                "left" -> fenced()
                "right" -> null
                "begin" -> environment(rawGroup())
                "text", "textrm", "textit", "textbf", "mbox", "mathrm", "operatorname", "mathit", "textnormal" ->
                    TexNode.Word(rawGroup().replace(Regex("\\\\[,;: ]"), " "), prose = name.startsWith("text") || name == "mbox")
                "mathbf", "boldsymbol", "bm", "mathsf", "mathcal", "mathscr", "mathfrak", "displaystyle", "textstyle", "scriptstyle" ->
                    if (name.endsWith("style")) TexNode.Space(0f) else argument()
                "mathbb" -> when (rawGroup().trim()) {
                    "R" -> TexNode.Sym("Reales"); "N" -> TexNode.Sym("Naturales"); "Z" -> TexNode.Sym("Enteros")
                    "Q" -> TexNode.Sym("Racionales"); "C" -> TexNode.Sym("Complejos"); else -> TexNode.Sym("?")
                }
                "overline", "bar" -> TexNode.Accent(TexNode.AccentKind.BAR, argument())
                "vec", "overrightarrow" -> TexNode.Accent(TexNode.AccentKind.VEC, argument())
                "hat", "widehat" -> TexNode.Accent(TexNode.AccentKind.HAT, argument())
                "boxed", "fbox", "framebox" -> TexNode.Boxed(argument())
                "underline", "cancel", "bcancel", "xcancel", "tilde", "widetilde", "dot", "ddot", "mathring" -> argument()
                "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
                "limits", "nolimits" -> TexNode.Space(0f)
                "quad" -> TexNode.Space(1f); "qquad" -> TexNode.Space(2f)
                "cdot", "centerdot" -> TexNode.Dot(true)
                "ldots", "dots", "dotsc" -> Row(List(3) { TexNode.Dot(false) })
                "cdots", "dotsb", "dotsm" -> Row(List(3) { TexNode.Dot(true) })
                "vdots", "ddots" -> Row(List(3) { TexNode.Dot(true) })
                "sum" -> TexNode.BigOp("Sumatoria", below = true)
                "prod" -> TexNode.BigOp("Productoria", below = true)
                "int" -> TexNode.BigOp("Integral", below = false)
                "oint" -> TexNode.BigOp("Integral de línea", below = false)
                "iint" -> TexNode.BigOp("Integral doble", below = false)
                "lim", "max", "min", "sup", "inf", "liminf", "limsup" -> TexNode.BigOp("", below = true, word = if (name == "lim") "lím" else name)
                in FUNCTIONS -> TexNode.Word(name)
                else -> SYMBOLS[name]?.let { (key, kind) -> TexNode.Sym(key, kind) } ?: TexNode.Word(name)
            }
        }

        private fun delimiter(): String? {
            skipSpaces()
            if (i >= s.length) return null
            if (s[i] == '\\') {
                val start = i + 1
                var end = start
                if (end < s.length && !s[end].isLetter()) end++ else while (end < s.length && s[end].isLetter()) end++
                val name = s.substring(start, end)
                i = end
                return when (name) {
                    "{", "lbrace" -> "Abre llave"; "}", "rbrace" -> "Cierra llave"
                    "langle" -> "Menor"; "rangle" -> "Mayor"; "|", "Vert" -> "Paralelo"; "vert", "lvert", "rvert" -> "Valor absoluto"
                    "lfloor", "lceil" -> "Abre corchete"; "rfloor", "rceil" -> "Cierra corchete"
                    else -> null
                }
            }
            val c = s[i++]
            return when (c) {
                '(' -> "Abre paréntesis"; ')' -> "Cierra paréntesis"; '[' -> "Abre corchete"; ']' -> "Cierra corchete"
                '|' -> "Valor absoluto"; '<' -> "Menor"; '>' -> "Mayor"; '.' -> null
                else -> null
            }
        }

        private fun fenced(): TexNode {
            val open = delimiter()
            val body = row(emptySet())
            var close: String? = null
            if (s.startsWith("\\right", i)) { i += 6; close = delimiter() }
            return TexNode.Fenced(open, close, body)
        }

        private fun environment(name: String): TexNode {
            val base = name.trimEnd('*')
            if (base == "array" || base == "alignat") rawGroup() // la especificación de columnas no importa
            val rows = mutableListOf<List<TexNode>>()
            var cells = mutableListOf<TexNode>()
            while (i < s.length) {
                val cell = row(emptySet())
                cells += if (cell.items.size == 1) cell.items[0] else cell
                when {
                    s.startsWith("&", i) -> i++
                    s.startsWith("\\\\", i) -> { i += 2; skipSpacing(); rows += cells; cells = mutableListOf() }
                    s.startsWith("\\end", i) -> { i += 4; rawGroup(); break }
                    s.startsWith("}", i) || s.startsWith("\\right", i) -> break
                    else -> break
                }
            }
            if (cells.any { !(it is TexNode.Row && it.items.isEmpty()) }) rows += cells
            val (open, close) = when (base) {
                "pmatrix" -> "Abre paréntesis" to "Cierra paréntesis"
                "bmatrix" -> "Abre corchete" to "Cierra corchete"
                "Bmatrix" -> "Abre llave" to "Cierra llave"
                "vmatrix" -> "Valor absoluto" to "Valor absoluto"
                "Vmatrix" -> "Paralelo" to "Paralelo"
                "cases", "dcases" -> "Abre llave" to null
                else -> null to null
            }
            val align = when (base) {
                "cases", "dcases", "rcases" -> TexNode.Align.LEFT
                "aligned", "align", "alignat", "alignedat", "split", "eqnarray", "flalign" -> TexNode.Align.PAIRS
                else -> TexNode.Align.CENTER
            }
            return TexNode.Matrix(rows, open, close, align)
        }

        private fun char(c: Char): TexNode = when (c) {
            in '0'..'9' -> TexNode.Sym(c.toString())
            '+' -> TexNode.Sym("Más", TexNode.Kind.BIN)
            '-', '−', '–' -> TexNode.Sym("Menos", TexNode.Kind.BIN)
            '*', '×' -> TexNode.Sym("Por", TexNode.Kind.BIN)
            '·', '⋅' -> TexNode.Dot(true)
            '÷' -> TexNode.Sym("Dividido", TexNode.Kind.BIN)
            '±' -> TexNode.Sym("Más o menos", TexNode.Kind.BIN)
            '/' -> TexNode.Sym("Barra")
            '=' -> TexNode.Sym("Igual", TexNode.Kind.REL)
            '<' -> TexNode.Sym("Menor", TexNode.Kind.REL)
            '>' -> TexNode.Sym("Mayor", TexNode.Kind.REL)
            '≤' -> TexNode.Sym("Menor o igual", TexNode.Kind.REL)
            '≥' -> TexNode.Sym("Mayor o igual", TexNode.Kind.REL)
            '≠' -> TexNode.Sym("Distinto", TexNode.Kind.REL)
            '≈' -> TexNode.Sym("Aproximado", TexNode.Kind.REL)
            '→' -> TexNode.Sym("Flecha a la derecha", TexNode.Kind.REL)
            '⇒' -> TexNode.Sym("Implica", TexNode.Kind.REL)
            '(' -> TexNode.Sym("Abre paréntesis", TexNode.Kind.OPEN)
            ')' -> TexNode.Sym("Cierra paréntesis", TexNode.Kind.CLOSE)
            '[' -> TexNode.Sym("Abre corchete", TexNode.Kind.OPEN)
            ']' -> TexNode.Sym("Cierra corchete", TexNode.Kind.CLOSE)
            '|' -> TexNode.Sym("Valor absoluto")
            ',' -> TexNode.Sym("Coma", TexNode.Kind.PUNCT)
            '.' -> TexNode.Dot(false)
            ';', ':' -> TexNode.Sym(c.toString(), TexNode.Kind.PUNCT)
            '!' -> TexNode.Sym("Factorial")
            '%' -> TexNode.Sym("Porcentaje")
            '∞' -> TexNode.Sym("Infinito")
            '°' -> TexNode.Sym("Grado")
            '∫' -> TexNode.BigOp("Integral", below = false)
            '∑' -> TexNode.BigOp("Sumatoria", below = true)
            '√' -> TexNode.Root(argument())
            else -> GREEK_CHARS[c]?.let { TexNode.Sym(it) } ?: TexNode.Sym(c.toString())
        }
    }

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "sen", "tg", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "lg", "exp", "det", "dim", "ker", "gcd", "mcd", "deg", "arg", "mod", "bmod", "pmod",
    )

    private val GREEK = mapOf(
        "alpha" to "alfa", "beta" to "beta", "gamma" to "gamma", "delta" to "delta", "Delta" to "Delta",
        "epsilon" to "épsilon", "varepsilon" to "épsilon", "eta" to "eta", "theta" to "theta", "vartheta" to "theta",
        "lambda" to "lambda", "mu" to "mu", "pi" to "pi", "rho" to "rho", "sigma" to "sigma", "tau" to "tau",
        "phi" to "fi", "varphi" to "fi", "psi" to "psi", "omega" to "omega", "Omega" to "Omega",
    )

    private val GREEK_CHARS = mapOf(
        'α' to "alfa", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta", 'Δ' to "Delta", 'ε' to "épsilon", 'η' to "eta",
        'θ' to "theta", 'λ' to "lambda", 'μ' to "mu", 'π' to "pi", 'ρ' to "rho", 'σ' to "sigma", 'τ' to "tau",
        'φ' to "fi", 'ψ' to "psi", 'ω' to "omega", 'Ω' to "Omega",
    )

    private val SYMBOLS: Map<String, Pair<String, TexNode.Kind>> = buildMap {
        GREEK.forEach { (k, v) -> put(k, v to TexNode.Kind.ORD) }
        val bin = TexNode.Kind.BIN
        val rel = TexNode.Kind.REL
        put("times", "Por" to bin); put("div", "Dividido" to bin); put("pm", "Más o menos" to bin); put("mp", "Más o menos" to bin)
        put("cup", "Unión" to bin); put("cap", "Intersección" to bin); put("land", "Y lógico" to bin); put("wedge", "Y lógico" to bin)
        put("lor", "O lógico" to bin); put("vee", "O lógico" to bin); put("setminus", "Barra" to bin)
        put("ne", "Distinto" to rel); put("neq", "Distinto" to rel); put("approx", "Aproximado" to rel); put("simeq", "Aproximado" to rel)
        put("equiv", "Equivalente" to rel); put("le", "Menor o igual" to rel); put("leq", "Menor o igual" to rel)
        put("leqslant", "Menor o igual" to rel); put("ge", "Mayor o igual" to rel); put("geq", "Mayor o igual" to rel)
        put("geqslant", "Mayor o igual" to rel); put("lt", "Menor" to rel); put("gt", "Mayor" to rel)
        put("to", "Flecha a la derecha" to rel); put("rightarrow", "Flecha a la derecha" to rel); put("longrightarrow", "Flecha a la derecha" to rel)
        put("leftarrow", "Flecha a la izquierda" to rel); put("gets", "Flecha a la izquierda" to rel)
        put("leftrightarrow", "Flecha doble" to rel); put("Rightarrow", "Implica" to rel); put("implies", "Implica" to rel)
        put("Longrightarrow", "Implica" to rel); put("Leftrightarrow", "Si y solo si" to rel); put("iff", "Si y solo si" to rel)
        put("uparrow", "Flecha hacia arriba" to rel); put("downarrow", "Flecha hacia abajo" to rel); put("mapsto", "Asigna" to rel)
        put("in", "Pertenece" to rel); put("notin", "No pertenece" to rel); put("subset", "Subconjunto" to rel)
        put("supset", "Superconjunto" to rel); put("subseteq", "Subconjunto o igual" to rel); put("supseteq", "Superconjunto o igual" to rel)
        put("perp", "Perpendicular" to rel); put("parallel", "Paralelo" to rel)
        put("infty", "Infinito" to TexNode.Kind.ORD); put("partial", "Derivada parcial" to TexNode.Kind.ORD)
        put("nabla", "Nabla" to TexNode.Kind.ORD); put("emptyset", "Vacío" to TexNode.Kind.ORD); put("varnothing", "Vacío" to TexNode.Kind.ORD)
        put("forall", "Para todo" to TexNode.Kind.ORD); put("exists", "Existe" to TexNode.Kind.ORD); put("neg", "No" to TexNode.Kind.ORD)
        put("lnot", "No" to TexNode.Kind.ORD); put("angle", "Ángulo" to TexNode.Kind.ORD); put("circ", "Grado" to TexNode.Kind.ORD)
        put("degree", "Grado" to TexNode.Kind.ORD); put("prime", "′" to TexNode.Kind.ORD)
        put("lbrace", "Abre llave" to TexNode.Kind.OPEN); put("rbrace", "Cierra llave" to TexNode.Kind.CLOSE)
        put("langle", "Menor" to TexNode.Kind.OPEN); put("rangle", "Mayor" to TexNode.Kind.CLOSE)
        put("vert", "Valor absoluto" to TexNode.Kind.ORD); put("mid", "Valor absoluto" to rel); put("Vert", "Paralelo" to TexNode.Kind.ORD)
        put("lvert", "Valor absoluto" to TexNode.Kind.OPEN); put("rvert", "Valor absoluto" to TexNode.Kind.CLOSE)
        put("colon", ":" to TexNode.Kind.PUNCT)
    }
}
