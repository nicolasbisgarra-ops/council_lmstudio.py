package io.trazo.core

/**
 * Respuesta del tutor (Markdown con fórmulas LaTeX) pasada a texto plano para escribirla a mano en la hoja:
 * `\frac{a}{b}` → a/b, `\sqrt{x}` → √x, `x^2` → x², los bloques `aligned` quedan un paso por renglón y los
 * comandos que no se conocen pierden la barra. Solo usa caracteres que [HandFont] sabe dibujar.
 */
object TutorText {
    /** Renglones de texto plano, sin vacíos repetidos ni al principio o al final. */
    fun plain(text: String): List<String> {
        val flat = Parser(text.replace("\r", "")).run()
        val lines = flat.split('\n').map { markdown(it).replace(Regex(" {2,}"), " ").trim() }
        val out = mutableListOf<String>()
        for (l in lines) if (l.isNotEmpty() || out.lastOrNull()?.isNotEmpty() == true) out += l
        while (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
        return out
    }

    private fun markdown(line: String): String {
        var l = line.trimStart()
        l = l.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^[*-]\\s+"), "• ").replace(Regex("^>\\s*"), "")
        return l.replace("**", "").replace("__", "").replace("`", "")
    }

    private val symbols = mapOf(
        "cdot" to " · ", "times" to " × ", "div" to " ÷ ", "pm" to " ± ", "mp" to " ± ",
        "Rightarrow" to " ⇒ ", "implies" to " ⇒ ", "Leftrightarrow" to " ⇔ ", "iff" to " ⇔ ", "Leftarrow" to " ← ",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔", "mapsto" to "→",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
        "equiv" to "≡", "infty" to "∞", "propto" to "∝", "degree" to "°", "circ" to "°",
        "in" to "∈", "cup" to "∪", "cap" to "∩", "subset" to "⊂", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "therefore" to "∴", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "int" to "∫", "partial" to "∂", "nabla" to "∇", "sum" to "Σ", "prod" to "Π", "ldots" to "...",
        "dots" to "...", "cdots" to "...", "lt" to "<", "gt" to ">", "%" to "%", "$" to "$", "&" to "&",
        "#" to "#", "_" to "_", "{" to "(", "}" to ")", "lbrace" to "(", "rbrace" to ")",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "phi" to "φ", "varphi" to "φ", "omega" to "ω", "eta" to "η", "kappa" to "κ", "nu" to "ν", "xi" to "ξ",
        "psi" to "ψ", "chi" to "χ", "zeta" to "ζ", "Delta" to "Δ", "Sigma" to "Σ", "Omega" to "Ω", "Pi" to "Π",
        "Phi" to "Φ", "Theta" to "Θ", "Lambda" to "Λ", "Gamma" to "Γ",
    )

    /** Comandos que solo envuelven su argumento (o no dibujan nada). */
    private val wrappers = setOf(
        "boxed", "text", "textbf", "textit", "mathrm", "mathbf", "mathit", "operatorname", "mbox", "emph",
        "underline", "overline", "bar", "vec", "hat", "displaystyle", "mathbb", "mathcal", "bm",
    )
    private val spaces = setOf(",", ";", ":", " ", "quad", "qquad", "enspace", "thinspace")
    private val ignored = setOf("left", "right", "big", "Big", "bigg", "Bigg", "displaystyle", "textstyle", "!", "nonumber", "notag")

    private val supDigits = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '-' to '⁻', '+' to '⁺', '−' to '⁻',
    )

    private class Parser(val s: String) {
        var i = 0

        fun run(): String = seq(stop = null)

        /** Lee hasta [stop] (sin consumirlo) o hasta el final. */
        fun seq(stop: Char?): String {
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == stop) break
                when (c) {
                    '\\' -> b.append(command())
                    '{' -> { i++; b.append(seq('}')); if (i < s.length) i++ }
                    '}' -> i++
                    '$' -> i++
                    '&' -> i++
                    '^' -> { i++; b.append(sup(arg())) }
                    '_' -> { i++; b.append(sub(arg())) }
                    '~' -> { i++; b.append(' ') }
                    else -> { b.append(c); i++ }
                }
            }
            return b.toString()
        }

        /** Un argumento: `{…}`, un comando o un solo carácter. */
        fun arg(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return when (s[i]) {
                '{' -> { i++; val r = seq('}'); if (i < s.length) i++; r }
                '\\' -> command()
                else -> s[i++].toString()
            }
        }

        /** Después de un salto de LaTeX, el salto de línea del fuente ya no cuenta. */
        fun eol() {
            while (i < s.length && s[i] == ' ') i++
            if (i < s.length && s[i] == '\n') i++
        }

        fun optional() {
            if (i < s.length && s[i] == '[') {
                val end = s.indexOf(']', i)
                if (end > 0) i = end + 1
            }
        }

        fun command(): String {
            i++ // la barra
            if (i >= s.length) return ""
            val start = i
            if (s[i].isLetter()) while (i < s.length && s[i].isLetter()) i++ else i++
            val name = s.substring(start, i)
            return when (name) {
                "\\" -> { optional(); eol(); "\n" }
                "[", "]" -> "\n"
                "(", ")" -> ""
                "begin", "end" -> { arg(); "\n" }
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val n = arg(); val d = arg()
                    "${paren(n)}/${paren(d, denominator = true)}"
                }
                "sqrt" -> {
                    var index = ""
                    if (i < s.length && s[i] == '[') {
                        val end = s.indexOf(']', i)
                        if (end > 0) { index = s.substring(i + 1, end); i = end + 1 }
                    }
                    val x = arg()
                    (if (index.isNotEmpty()) sup(index) else "") + "√" + paren(x)
                }
                in wrappers -> arg()
                in spaces -> " "
                in ignored -> ""
                else -> symbols[name] ?: name
            }
        }

        fun paren(t: String, denominator: Boolean = false): String {
            val x = t.trim()
            val simple = if (denominator) x.all { it.isDigit() || it == '.' || it == ',' } || x.all { it.isLetter() }
            else x.all { it.isLetterOrDigit() || it == '.' || it == ',' }
            return if (x.length <= 1 || simple ||
                (x.startsWith('(') && x.endsWith(')'))) x else "($x)"
        }

        fun sup(t: String): String {
            val x = t.trim()
            if (x.isNotEmpty() && x.all { it in supDigits }) return x.map { supDigits.getValue(it) }.joinToString("")
            return if (x.isEmpty()) "" else "^" + paren(x)
        }

        fun sub(t: String): String {
            val x = t.trim()
            return if (x.isEmpty()) "" else if (x.length == 1) x else "_" + paren(x)
        }
    }
}
