package io.trazo.core.share

import io.trazo.core.math.tex.TexMath
import io.trazo.core.math.tex.TexPart

/**
 * El texto del chat de revisión (Markdown con fórmulas TeX) pasado a HTML para la web. Todo lo que
 * escribieron el modelo o el estudiante sale escapado y nunca va dentro de un atributo: no hay forma de que
 * un mensaje abra una etiqueta. Las fórmulas quedan como `<span class="tex">fuente</span>` (con `display` si
 * van en su renglón) y el navegador las dibuja; sin eso, se lee la fuente tal cual.
 */
object WebText {
    // Marcas internas de «acá va la fórmula n»: dos caracteres de uso privado que se sacan de la entrada.
    private const val OPEN = ''
    private const val CLOSE = ''
    private const val MAX_CHARS = 200_000
    private const val MAX_DEPTH = 6
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|>~$"


    // Los renglones se leen a mano y no con expresiones regulares: una respuesta con miles de «*» seguidos
    // no puede agotar la pila del servidor.

    /** «## Título» → (nivel, texto); null si no es un título. */
    private fun heading(t: String): Pair<Int, String>? {
        var n = 0
        while (n < t.length && t[n] == '#') n++
        if (n !in 1..6 || n >= t.length || !t[n].isWhitespace()) return null
        var text = t.substring(n).trim()
        val bare = text.trimEnd('#')
        if (bare.length < text.length && (bare.isEmpty() || bare.last().isWhitespace())) text = bare.trim()
        return n to text
    }

    /** «---», «* * *»: tres o más del mismo signo y nada más. */
    private fun rule(t: String): Boolean {
        val c = t[0]
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (x in t) if (x == c) count++ else if (x != ' ') return false
        return count >= 3
    }

    private fun bullet(t: String): String? =
        if (t.length >= 2 && (t[0] == '-' || t[0] == '*' || t[0] == '+') && t[1] == ' ') t.substring(2).trimStart() else null

    /** «3. texto» o «3) texto» → (3, texto). */
    private fun ordered(t: String): Pair<Int, String>? {
        var n = 0
        while (n < t.length && n < 4 && t[n].isDigit()) n++
        if (n !in 1..3 || n + 1 >= t.length || (t[n] != '.' && t[n] != ')') || t[n + 1] != ' ') return null
        return t.substring(0, n).toInt() to t.substring(n + 2).trimStart()
    }

    /** El renglón de guiones que separa el encabezado de una tabla: «|:--|--:|». */
    private fun tableRule(cells: List<String>): Boolean = cells.isNotEmpty() && cells.all { c ->
        val dashes = c.removePrefix(":").removeSuffix(":")
        dashes.isNotEmpty() && dashes.all { it == '-' }
    }

    fun escape(s: CharSequence): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&#39;")
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    /** Un mensaje entero: párrafos, títulos, listas, tablas, código y fórmulas. */
    fun html(message: String): String {
        val out = StringBuilder(message.length + 64)
        for (seg in segments(clean(message))) {
            if (seg.code) {
                out.append("<pre><code>").append(escape(seg.text)).append("</code></pre>")
            } else {
                val formulas = ArrayList<TexPart.Formula>()
                Renderer(formulas, out).blocks(marked(seg.text, formulas))
            }
        }
        return out.toString()
    }

    /** Un texto corto (el resumen de un veredicto, una marca): sin bloques, con negrita y fórmulas. */
    fun inline(text: String): String {
        val out = StringBuilder(text.length + 32)
        val formulas = ArrayList<TexPart.Formula>()
        val lines = marked(clean(text), formulas).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        Renderer(formulas, out).joined(lines)
        return out.toString()
    }

    private fun clean(s: String): String {
        val t = if (s.length > MAX_CHARS) s.substring(0, MAX_CHARS) + "\n…" else s
        val b = StringBuilder(t.length)
        for (c in t) {
            when {
                c == '\n' -> b.append(c)
                c == '\t' -> b.append("    ")
                c == OPEN || c == CLOSE -> {}
                c < ' ' || c == '\u007f' -> {}
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    private class Segment(val text: String, val code: Boolean)

    /** Separa los bloques de código (```): adentro no hay Markdown ni fórmulas. */
    private fun segments(s: String): List<Segment> {
        val out = ArrayList<Segment>()
        val text = StringBuilder()
        var code: StringBuilder? = null
        for (line in s.split('\n')) {
            val t = line.trimStart()
            val fence = t.startsWith("```") && line.length - t.length <= 3
            val open = code
            when {
                open != null && fence -> { out += Segment(open.toString().trimEnd('\n'), true); code = null }
                open != null -> open.append(line).append('\n')
                fence -> {
                    if (text.isNotBlank()) out += Segment(text.toString(), false)
                    text.clear()
                    code = StringBuilder()
                }
                else -> text.append(line).append('\n')
            }
        }
        // Un bloque sin cerrar (la respuesta todavía se está escribiendo) igual es código.
        code?.let { out += Segment(it.toString().trimEnd('\n'), true) }
        if (text.isNotBlank()) out += Segment(text.toString(), false)
        return out
    }

    /** Cambia cada fórmula por su marca, para que el Markdown no toque los «*» y «_» del TeX. */
    private fun marked(text: String, formulas: MutableList<TexPart.Formula>): String {
        val b = StringBuilder(text.length)
        for (p in TexMath.split(text)) {
            when (p) {
                is TexPart.Prose -> b.append(p.text)
                is TexPart.Formula -> {
                    formulas += p
                    b.append(OPEN).append(formulas.size - 1).append(CLOSE)
                }
            }
        }
        return b.toString()
    }

    private class OpenList(val ordered: Boolean, val indent: Int)

    private class Renderer(private val formulas: List<TexPart.Formula>, private val out: StringBuilder) {
        private val para = ArrayList<String>()
        private var item: ArrayList<String>? = null
        private val lists = ArrayList<OpenList>()

        fun blocks(text: String) {
            val lines = text.split('\n')
            var blank = false
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trimEnd()
                if (line.isBlank()) { flushPara(); blank = true; i++; continue }
                val t = line.trimStart()
                val indent = line.length - t.length
                val heading = if (indent <= 3) heading(t) else null
                val bullet = bullet(t)
                val ordered = ordered(t)
                when {
                    heading != null -> {
                        closeLists(); flushPara()
                        val tag = "h" + minOf(heading.first + 2, 6)
                        out.append('<').append(tag).append('>')
                        inlineTo(heading.second, 0)
                        out.append("</").append(tag).append('>')
                    }
                    indent <= 3 && rule(t) -> { closeLists(); flushPara(); out.append("<hr>") }
                    t.startsWith(">") -> {
                        closeLists(); flushPara()
                        val quote = ArrayList<String>()
                        while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                            quote += lines[i].trimStart().substring(1).trim()
                            i++
                        }
                        out.append("<blockquote>")
                        joined(quote.filter { it.isNotEmpty() })
                        out.append("</blockquote>")
                        blank = false
                        continue
                    }
                    tableAt(lines, i) -> {
                        closeLists(); flushPara()
                        i = table(lines, i)
                        blank = false
                        continue
                    }
                    bullet != null -> startItem(indent, false, 1, bullet)
                    ordered != null -> startItem(indent, true, ordered.first, ordered.second)
                    // Un renglón suelto sigue al ítem de arriba, salvo que venga después de un blanco y sin sangría.
                    lists.isNotEmpty() && (!blank || indent >= 2) -> (item ?: ArrayList<String>().also { item = it }).add(t)
                    else -> { closeLists(); para += t }
                }
                blank = false
                i++
            }
            closeLists()
            flushPara()
        }

        private fun startItem(indent: Int, ordered: Boolean, start: Int, content: String) {
            flushPara()
            flushItem()
            val top = lists.lastOrNull()
            if (top == null || indent >= top.indent + 2) {
                open(ordered, indent, start)
            } else {
                while (lists.size > 1 && indent < lists.last().indent) closeList()
                if (lists.last().ordered != ordered) { closeList(); open(ordered, indent, start) } else out.append("</li>")
            }
            out.append("<li>")
            item = arrayListOf(content)
        }

        private fun open(ordered: Boolean, indent: Int, start: Int) {
            if (!ordered) out.append("<ul>")
            else if (start == 1) out.append("<ol>")
            else out.append("<ol start=\"").append(start).append("\">")
            lists += OpenList(ordered, indent)
        }

        private fun closeList() {
            flushItem()
            val top = lists.removeAt(lists.lastIndex)
            out.append("</li>").append(if (top.ordered) "</ol>" else "</ul>")
        }

        private fun closeLists() { while (lists.isNotEmpty()) closeList() }

        private fun flushItem() {
            item?.let { joined(it) }
            item = null
        }

        private fun flushPara() {
            if (para.isEmpty()) return
            out.append("<p>")
            joined(para)
            out.append("</p>")
            para.clear()
        }

        /** Renglones de un mismo párrafo; una fórmula en su propio renglón ya corta sola, sin `<br>`. */
        fun joined(lines: List<String>) {
            for ((k, l) in lines.withIndex()) {
                if (k > 0 && !displayOnly(lines[k - 1]) && !displayOnly(l)) out.append("<br>")
                inlineTo(l, 0)
            }
        }

        private fun displayOnly(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return false
            var i = 0
            while (i < t.length) {
                if (t[i] != OPEN) return false
                val end = t.indexOf(CLOSE, i)
                if (end < 0) return false
                val f = t.substring(i + 1, end).toIntOrNull()?.let { formulas.getOrNull(it) } ?: return false
                if (!f.display) return false
                i = end + 1
                while (i < t.length && t[i] == ' ') i++
            }
            return true
        }

        private fun tableAt(lines: List<String>, i: Int): Boolean {
            if (i + 1 >= lines.size || '|' !in lines[i] || '|' !in lines[i + 1]) return false
            val under = cells(lines[i + 1])
            return tableRule(under) && cells(lines[i]).size == under.size
        }

        private fun table(lines: List<String>, from: Int): Int {
            val head = cells(lines[from])
            val align = cells(lines[from + 1]).map {
                when {
                    it.startsWith(":") && it.endsWith(":") -> " class=\"c\""
                    it.endsWith(":") -> " class=\"r\""
                    else -> ""
                }
            }
            out.append("<div class=\"tbl\"><table><thead><tr>")
            for ((k, c) in head.withIndex()) { out.append("<th").append(align[k]).append('>'); inlineTo(c, 0); out.append("</th>") }
            out.append("</tr></thead><tbody>")
            var i = from + 2
            while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) {
                val row = cells(lines[i])
                out.append("<tr>")
                for (k in head.indices) { out.append("<td").append(align[k]).append('>'); inlineTo(row.getOrElse(k) { "" }, 0); out.append("</td>") }
                out.append("</tr>")
                i++
            }
            out.append("</tbody></table></div>")
            return i
        }

        private fun cells(row: String): List<String> {
            var r = row.trim()
            if (r.startsWith("|")) r = r.substring(1)
            if (r.endsWith("|") && !r.endsWith("\\|")) r = r.substring(0, r.length - 1)
            val cells = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < r.length) {
                val c = r[i]
                if (c == '\\' && i + 1 < r.length && r[i + 1] == '|') { cur.append('|'); i += 2; continue }
                if (c == '`') {
                    val end = r.indexOf('`', i + 1)
                    if (end > 0) { cur.append(r, i, end + 1); i = end + 1; continue }
                }
                if (c == '|') { cells += cur.toString().trim(); cur.clear() } else cur.append(c)
                i++
            }
            cells += cur.toString().trim()
            return cells
        }

        private fun formula(f: TexPart.Formula) {
            if (f.source.isBlank()) return
            out.append(if (f.display) "<span class=\"tex display\">" else "<span class=\"tex\">").append(escape(f.source)).append("</span>")
        }

        private fun formulaAt(s: String, open: Int): Pair<TexPart.Formula, Int>? {
            val end = s.indexOf(CLOSE, open + 1)
            if (end < 0) return null
            val f = s.substring(open + 1, end).toIntOrNull()?.let { formulas.getOrNull(it) } ?: return null
            return f to end + 1
        }

        /** Dentro de `código` las fórmulas vuelven a ser su fuente. */
        private fun plain(s: String): String {
            if (OPEN !in s) return s
            val b = StringBuilder()
            var i = 0
            while (i < s.length) {
                val f = if (s[i] == OPEN) formulaAt(s, i) else null
                if (f != null) { b.append('$').append(f.first.source).append('$'); i = f.second } else { b.append(s[i]); i++ }
            }
            return b.toString()
        }

        private fun inlineTo(s: String, depth: Int) {
            val lit = StringBuilder()
            fun flush() { if (lit.isNotEmpty()) { out.append(escape(lit)); lit.clear() } }
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> { lit.append(s[i + 1]); i += 2 }
                    c == OPEN -> {
                        val f = formulaAt(s, i)
                        if (f != null) { flush(); formula(f.first); i = f.second } else i++
                    }
                    c == CLOSE -> i++
                    c == '`' -> {
                        val end = s.indexOf('`', i + 1)
                        if (end > i + 1) {
                            flush()
                            out.append("<code>").append(escape(plain(s.substring(i + 1, end)))).append("</code>")
                            i = end + 1
                        } else { lit.append(c); i++ }
                    }
                    c == '*' || c == '_' || c == '~' -> {
                        var n = 1
                        while (n < 3 && i + n < s.length && s[i + n] == c) n++
                        val close = if (depth >= MAX_DEPTH || (c == '~' && n != 2)) -1 else closing(s, i, n, c)
                        if (close < 0) { lit.append(s, i, i + n); i += n } else {
                            flush()
                            val tags = when {
                                c == '~' -> "<del>" to "</del>"
                                n == 1 -> "<em>" to "</em>"
                                n == 2 -> "<strong>" to "</strong>"
                                else -> "<strong><em>" to "</em></strong>"
                            }
                            out.append(tags.first)
                            inlineTo(s.substring(i + n, close), depth + 1)
                            out.append(tags.second)
                            i = close + n
                        }
                    }
                    else -> { lit.append(c); i++ }
                }
            }
            flush()
        }

        /** Dónde cierra el énfasis que abre en [open] con [n] signos [c]; -1 si no es énfasis («2 * 3», «a_b»). */
        private fun closing(s: String, open: Int, n: Int, c: Char): Int {
            val from = open + n
            if (from >= s.length || s[from].isWhitespace()) return -1
            if (c == '_' && open > 0 && s[open - 1].isLetterOrDigit()) return -1
            var j = from + 1
            while (j < s.length) {
                val x = s[j]
                if (x == '\\') { j += 2; continue }
                if (x == OPEN) { val end = s.indexOf(CLOSE, j); j = if (end < 0) j + 1 else end + 1; continue }
                if (x == '`') { val end = s.indexOf('`', j + 1); if (end > 0) { j = end + 1; continue } }
                if (x == c) {
                    var run = 1
                    while (j + run < s.length && s[j + run] == c) run++
                    val before = !s[j - 1].isWhitespace()
                    val after = c != '_' || j + run >= s.length || !s[j + run].isLetterOrDigit()
                    // Un cierre más largo («**a *b***») cierra con sus últimos signos; uno distinto es de otro énfasis.
                    if (before && after && (run == n || run >= 3)) return j + (run - n).coerceAtLeast(0)
                    j += run
                    continue
                }
                j++
            }
            return -1
        }
    }
}
