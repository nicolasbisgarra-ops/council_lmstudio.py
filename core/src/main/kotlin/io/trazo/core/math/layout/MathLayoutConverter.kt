package io.trazo.core.math.layout

import io.trazo.core.math.MathError
import io.trazo.core.math.MathException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Caja de un símbolo reconocido, en coordenadas de la hoja (Y crece hacia abajo). */
data class LayoutSymbol(val label: String, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
}

/** Arma un árbol espacial antes de emitir texto: ningún operando se convierte por separado. */
object MathLayoutConverter {
    private enum class Kind { ATOM, NUMBER, BAR, ROOT_MARK, OPEN, CLOSE, SEQUENCE, ROOT, FRACTION, GROUP, POWER }

    /** La caja completa y el nivel de escritura son distintos: un exponente no mueve la base. */
    private class Node(
        val kind: Kind, val box: Box, val y: Double, val emX: Double, val emY: Double,
        val text: String? = null, val label: String = "", val children: List<Node> = emptyList()
    ) {
        val baseline get() = y + emY / 2
        val canPower get() = kind in setOf(Kind.NUMBER, Kind.ROOT, Kind.FRACTION, Kind.GROUP, Kind.POWER) ||
            kind == Kind.ATOM && text?.singleOrNull()?.isLetter() == true
    }

    private data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2
        fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }

    fun convert(symbols: List<LayoutSymbol>): String {
        if (symbols.isEmpty()) return ""
        if (symbols.any { !it.minX.isFinite() || !it.minY.isFinite() || !it.maxX.isFinite() || !it.maxY.isFinite() || it.width < 0f || it.height < 0f })
            fail("la caja del símbolo no es válida", 0)
        val glyphs = symbols.filter { kind(it.label.trim(), spelling(it.label.trim())) !in setOf(Kind.BAR, Kind.ROOT_MARK, Kind.OPEN, Kind.CLOSE) }
        val unitX = median(glyphs.map { it.width.toDouble() }.filter { it > 0 })
        val unitY = median(glyphs.map { it.height.toDouble() }.filter { it > 0 })
        // Normalizar cada eje permite cambiar escala y proporciones sin introducir distancias en píxeles.
        val nodes = symbols.map {
            val label = it.label.trim()
            val text = spelling(label)
            val box = Box(it.minX.toDouble() / unitX, it.minY.toDouble() / unitY, it.maxX.toDouble() / unitX, it.maxY.toDouble() / unitY)
            Node(kind(label, text), box, box.centerY, box.width.takeIf { w -> w > 0 } ?: 1.0,
                box.height.takeIf { h -> h > 0 } ?: 1.0, text, label)
        }
        return layout(nodes).joinToString("\n") { row ->
            // Syntax.position pertenece a esta línea, incluso si una línea anterior ya se emitió.
            StringBuilder().also { emit(sequence(row), it) }.toString()
        }
    }

    private fun layout(input: List<Node>): List<List<Node>> {
        val items = containers(input).toMutableList()
        joinNumbers(items)
        // Las barras interiores se cierran antes que las que las abarcan. Con igual ancho,
        // la separación entre operandos decide la estructura más local; arriba se desempata.
        while (true) {
            val fraction = items.filter { it.kind == Kind.BAR }.mapNotNull { bar -> fraction(bar, items) }
                .minWithOrNull(Comparator { a, b ->
                    compareGeometry(a.bar.box.width, b.bar.box.width).takeIf { it != 0 }
                        ?: compareGeometry(a.span, b.span).takeIf { it != 0 }
                        ?: compareGeometry(a.bar.y, b.bar.y).takeIf { it != 0 }
                        ?: compareGeometry(a.bar.box.left, b.bar.box.left)
                }) ?: break
            val bar = fraction.bar
            val children = listOf(sequence(fraction.above), sequence(fraction.below))
            val parts = fraction.above + fraction.below
            val box = parts.fold(bar.box) { bounds, node -> bounds.union(node.box) }
            items.removeAll((parts.flatMap { origins(it, items) } + bar).toSet())
            items += Node(Kind.FRACTION, box, bar.y, median(parts.map { it.emX }), median(parts.map { it.emY }), children = children)
        }
        return rows(powers(items))
    }

    /** Paréntesis y techos de raíces delimitan regiones; adentro se aplica el mismo recorrido. */
    private fun containers(input: List<Node>): List<Node> {
        val items = input.toMutableList()
        for (close in input.filter { it.kind == Kind.CLOSE }.sortedWith(order)) {
            val open = items.filter { it.kind == Kind.OPEN && it.box.right <= close.box.left + EPS && sameLevel(it, close) }
                .maxByOrNull { it.box.left } ?: continue
            val inside = items.filter { it !== open && it !== close && it.box.left >= open.box.right - EPS && it.box.right <= close.box.left + EPS &&
                abs(it.y - open.y) <= open.emY * 1.8 }
            val children = layout(inside).map(::sequence)
            val box = inside.fold(open.box.union(close.box)) { bounds, node -> bounds.union(node.box) }
            items.removeAll((inside + open + close).toSet())
            items += Node(Kind.GROUP, box, open.y, median(inside.filter { it.canPower }.map { it.emX }).takeIf { inside.isNotEmpty() } ?: open.emX, open.emY, text = open.text, children = children)
        }
        for (root in input.filter { it.kind == Kind.ROOT_MARK }.sortedWith(compareByDescending<Node> { it.box.width }.thenBy { it.box.top }.thenBy { it.box.left })) {
            if (root !in items) continue
            val inside = items.filter { it !== root && it.box.left >= root.box.left + root.box.width * .04 &&
                (it.box.left + it.box.right) / 2 <= root.box.right + EPS &&
                it.y >= root.box.top - root.box.height * .15 && it.y <= root.box.bottom + root.box.height * .15 }
            val children = layout(inside).map(::sequence)
            val box = inside.fold(root.box) { bounds, node -> bounds.union(node.box) }
            items.removeAll((inside + root).toSet())
            items += Node(Kind.ROOT, box, root.y, median(inside.map { it.emX }), median(inside.map { it.emY }), children = children)
        }
        return items
    }

    private data class Fraction(val bar: Node, val above: List<Node>, val below: List<Node>) {
        val span get() = below.minOf { it.y } - above.maxOf { it.y }
    }

    private fun fraction(bar: Node, items: List<Node>): Fraction? {
        val available = powers(items.filter { it !== bar && it.kind != Kind.BAR &&
            min(it.box.right, bar.box.right) >= max(it.box.left, bar.box.left) - EPS })
        fun band(above: Boolean): List<Node> {
            val side = available.filter { if (above) it.y < bar.y - EPS else it.y > bar.y + EPS }
            val seed = side.minWithOrNull(compareBy<Node> { abs(it.y - bar.y) }.then(order)) ?: return emptyList()
            val members = side.filter { sameLevel(it, seed) }.toMutableSet()
            // El nivel incluye los exponentes de sus operandos, pero no otra banda del cuaderno.
            var changed: Boolean
            do {
                changed = false
                for (candidate in side) if (candidate !in members && members.any { isScript(it, candidate) }) {
                    members += candidate
                    changed = true
                }
            } while (changed)
            return members.sortedWith(order)
        }
        val above = band(true)
        val below = band(false)
        if (above.isEmpty() || below.isEmpty()) return null
        // Una barra sin escritura cercana a ambos lados sigue siendo una división en línea.
        if (bar.box.top - above.maxOf { it.box.bottom } > above.maxOf { it.emY } * 2 ||
            below.minOf { it.box.top } - bar.box.bottom > below.maxOf { it.emY } * 2) return null
        return Fraction(bar, above, below)
    }

    /** Conserva los símbolos originales al cerrar operandos que ya tienen potencias. */
    private fun origins(node: Node, input: List<Node>): List<Node> =
        if (node in input) listOf(node) else node.children.flatMap { origins(it, input) }

    /** Los niveles se comparan por pie y por centro: una cifra baja no es un exponente. */
    private fun sameWritingLevel(a: Node, b: Node): Boolean {
        val height = min(a.emY, b.emY)
        return abs(a.baseline - b.baseline) <= height * .22 + EPS || abs(a.y - b.y) <= height * .3 + EPS
    }

    private fun bands(input: List<Node>, broadRoots: Boolean = false): List<List<Node>> {
        val groups = mutableListOf<MutableList<Node>>()
        for (node in input.sortedWith(compareByDescending<Node> { it.emY }.then(order))) {
            val group = groups.filter { level ->
                val reference = level.maxBy { it.emY }
                if (broadRoots) sameLevel(reference, node) else sameWritingLevel(reference, node)
            }.minByOrNull { level -> abs(level.maxBy { it.emY }.baseline - node.baseline) }
            if (group == null) groups += mutableListOf(node) else group += node
        }
        return groups.map { it.sortedWith(order) }
    }

    /** Une cifras sólo entre vecinos de su nivel, aunque haya otros renglones en las mismas X. */
    private fun joinNumbers(items: MutableList<Node>) {
        while (true) {
            val points = items.filter { it.text in setOf(".", ",") }
            val levels = bands(items.filter { it.kind != Kind.BAR && it !in points }).map { it.toMutableList() }
            for (point in points) {
                val level = levels.filter { nodes -> nodes.zipWithNext().any { (a, b) ->
                    a.kind == Kind.NUMBER && b.kind == Kind.NUMBER &&
                        point.box.left >= a.box.right - EPS && point.box.right <= b.box.left + EPS &&
                        b.box.left - a.box.right <= max(a.emX, b.emX) * 2 + EPS &&
                        point.y >= min(a.box.top, b.box.top) - min(a.emY, b.emY) * .15 - EPS &&
                        point.y <= max(a.box.bottom, b.box.bottom) + min(a.emY, b.emY) * .25 + EPS
                } }.minByOrNull { nodes -> abs(nodes.filter { it.kind == Kind.NUMBER }.map { it.y }.average() - point.y) }
                level?.add(point)
                level?.sortWith(order)
            }
            var joined = false
            for (level in levels) {
                for (index in 0 until level.lastIndex) {
                    val a = level[index]
                    if (a.kind != Kind.NUMBER) continue
                    val point = level[index + 1].takeIf { it in points }
                    val b = level.getOrNull(index + if (point == null) 1 else 2) ?: continue
                    if (b.kind != Kind.NUMBER || !sameWritingLevel(a, b) ||
                        b.box.left - a.box.right > max(a.emX, b.emX) * (if (point == null) 1.1 else 2.0) + EPS) continue
                    val height = max(a.emY, b.emY)
                    val box = a.box.union(b.box).let { if (point == null) it else it.union(point.box) }
                    items.removeAll(listOfNotNull(a, point, b).toSet())
                    items += Node(Kind.NUMBER, box, max(a.baseline, b.baseline) - height / 2,
                        max(a.emX, b.emX), height, text = a.text + (if (point == null) "" else ".") + b.text)
                    joined = true
                    break
                }
                if (joined) break
            }
            if (!joined) return
        }
    }

    /** Una semilla vincula el nivel elevado a una base; el resto sigue por continuidad en ese nivel. */
    private fun powers(input: List<Node>): List<Node> {
        val parent = input.associateWith { candidate ->
            input.filter { it.canPower && isScript(it, candidate) }.maxWithOrNull(compareBy<Node> { it.box.right }.thenBy { it.box.left })
        }.toMutableMap()
        for (level in bands(input.filter { it.kind !in setOf(Kind.BAR, Kind.OPEN, Kind.CLOSE) && it.text != "=" })) {
            for ((previous, candidate) in level.zipWithNext()) {
                if (parent[candidate] == null && parent[previous] != null &&
                    candidate.box.left - previous.box.right <= max(previous.emX, candidate.emX) * 2 + EPS)
                    parent[candidate] = parent[previous]
            }
        }
        fun build(base: Node): Node {
            val exponent = input.filter { parent[it] === base }.map(::build)
            if (exponent.isEmpty()) return base
            return Node(Kind.POWER, exponent.fold(base.box) { bounds, node -> bounds.union(node.box) }, base.y, base.emX, base.emY,
                children = listOf(base, sequence(exponent)))
        }
        return input.filter { parent[it] == null }.map(::build).sortedWith(order)
    }

    private fun isScript(base: Node, candidate: Node): Boolean = base !== candidate && base.canPower &&
        candidate.kind !in setOf(Kind.BAR, Kind.OPEN, Kind.CLOSE) && candidate.text !in setOf("=", ".", ",") &&
        !sameWritingLevel(base, candidate) && candidate.emY <= base.emY * 1.05 + EPS &&
        candidate.y < base.y - base.emY * .18 + EPS &&
        base.y - candidate.y <= max(base.emY, base.box.height) * .95 + EPS &&
        candidate.box.left >= base.box.right - base.emX * .15 - EPS &&
        candidate.box.left - base.box.right <= max(base.emX, candidate.emX) * 2.5 + EPS

    private fun sameLevel(a: Node, b: Node): Boolean {
        fun reach(node: Node) = if (node.kind == Kind.ROOT) node.box.height / 2 else node.emY * .31
        return sameWritingLevel(a, b) || abs(a.y - b.y) <= reach(a) + reach(b) + EPS
    }

    private fun rows(input: List<Node>) = bands(input, broadRoots = true).sortedBy { row -> row.map { it.y }.average() }

    private fun sequence(nodes: List<Node>): Node {
        val sorted = nodes.sortedWith(order)
        val box = sorted.map { it.box }.reduceOrNull(Box::union) ?: Box(0.0, 0.0, 0.0, 0.0)
        return Node(Kind.SEQUENCE, box, sorted.map { it.y }.average(), median(sorted.map { it.emX }), median(sorted.map { it.emY }), children = sorted)
    }

    /** Un solo cursor cuenta todo lo emitido, incluidos separadores y paréntesis generados. */
    private fun emit(node: Node, out: StringBuilder) {
        when (node.kind) {
            Kind.SEQUENCE -> {
                var previous: Node? = null
                for (child in node.children) {
                    val prior = previous
                    if (prior != null && startsNumber(child)) {
                        if (prior.kind == Kind.NUMBER) out.append(' ')
                        else if (endsAtom(prior)) out.append('*')
                    }
                    // Dos identificadores contiguos serían un único nombre para el lexer.
                    if (prior != null && endsIdentifier(prior) && startsIdentifier(child)) out.append('*')
                    val wrap = prior?.text == "/" && child.kind == Kind.FRACTION
                    if (wrap) out.append('(')
                    emit(child, out)
                    if (wrap) out.append(')')
                    previous = child
                }
            }
            Kind.FRACTION -> {
                out.append('('); emit(node.children[0], out)
                out.append(")/("); emit(node.children[1], out); out.append(')')
            }
            Kind.ROOT -> {
                if (node.children.isEmpty()) fail("la raíz necesita un radicando", out.length)
                out.append("√("); emitLines(node.children, out); out.append(')')
            }
            Kind.GROUP -> {
                out.append(node.text ?: "("); emitLines(node.children, out); out.append(')')
            }
            Kind.POWER -> {
                val base = node.children[0]
                val exponent = node.children[1]
                if (base.kind == Kind.FRACTION) out.append('(')
                emit(base, out)
                if (base.kind == Kind.FRACTION) out.append(')')
                out.append('^')
                val single = exponent.children.singleOrNull()
                val wrap = single == null || single.kind !in setOf(Kind.ATOM, Kind.NUMBER) || single.text in setOf("+", "-", "*", "/")
                if (wrap) out.append('(')
                emit(exponent, out)
                if (wrap) out.append(')')
            }
            else -> out.append(node.text ?: fail("no se reconoce la etiqueta «${node.label}»", out.length))
        }
    }

    private fun emitLines(lines: List<Node>, out: StringBuilder) {
        for ((index, line) in lines.withIndex()) {
            if (index > 0) out.append('\n')
            emit(line, out)
        }
    }

    private fun startsNumber(node: Node): Boolean = when (node.kind) {
        Kind.NUMBER -> true
        Kind.POWER -> startsNumber(node.children[0])
        else -> false
    }
    private fun startsIdentifier(node: Node): Boolean = when (node.kind) {
        Kind.ATOM -> node.text?.singleOrNull()?.isLetter() == true
        Kind.POWER -> startsIdentifier(node.children[0])
        else -> false
    }
    private fun endsIdentifier(node: Node): Boolean = when (node.kind) {
        Kind.ATOM -> node.text?.singleOrNull()?.isLetter() == true
        // Un exponente compuesto termina con el paréntesis que agrega emit.
        Kind.POWER -> node.children[1].children.singleOrNull()?.let { it.kind == Kind.ATOM && endsIdentifier(it) } == true
        else -> false
    }
    private fun endsAtom(node: Node) = node.canPower || node.kind == Kind.CLOSE
    private fun kind(label: String, text: String?): Kind = when {
        label in setOf("Fracción", "Barra de fracción", "Barra") -> Kind.BAR
        text == "√" -> Kind.ROOT_MARK
        text in setOf("(", "f(") -> Kind.OPEN
        text == ")" -> Kind.CLOSE
        text?.all { it.isDigit() } == true -> Kind.NUMBER
        else -> Kind.ATOM
    }

    private fun spelling(label: String): String? = when (label) {
        "Más", "+" -> "+"; "Menos", "−", "-" -> "-"; "Por", "Multiplicación", "×", "·", "*" -> "*"
        "Dividido", "División", "÷", "Barra", "Fracción", "Barra de fracción", "/" -> "/"
        "Igual", "=" -> "="; "Raíz", "√" -> "√"; "Pi", "π" -> "π"
        "Abre paréntesis", "(", "Paréntesis abre" -> "("; "Cierra paréntesis", ")", "Paréntesis cierra" -> ")"
        "Coma", "," -> ","; "Punto", "." -> "."; "f( )" -> "f("
        else -> when (label.lowercase()) {
            "alfa" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"; "epsilon", "épsilon" -> "ε"
            "theta" -> "θ"; "lambda" -> "λ"; "mu" -> "μ"; "sigma" -> "σ"; "omega" -> "ω"; "rho" -> "ρ"; "pi" -> "π"
            else -> label.takeIf { it.matches(Regex("\\d+")) || it.length == 1 && it[0].isLetter() }
        }
    }

    private fun compareGeometry(a: Double, b: Double) = if (abs(a - b) <= EPS) 0 else a.compareTo(b)
    private fun median(values: List<Double>) = values.sorted().let { it.getOrNull(it.size / 2) ?: 1.0 }
    private fun fail(message: String, position: Int): Nothing = throw MathException(MathError.Syntax(message, position))
    private val order = compareBy<Node> { it.box.left }.thenBy { it.y }.thenBy { it.box.right }.thenBy { it.label }
    private const val EPS = 1e-4
}
