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

/** Convierte etiquetas y disposición espacial en las expresiones que acepta MathParser. */
object MathLayoutConverter {
    fun convert(symbols: List<LayoutSymbol>): String {
        if (symbols.isEmpty()) return ""
        if (symbols.any { !it.minX.isFinite() || !it.minY.isFinite() || !it.maxX.isFinite() || !it.maxY.isFinite() || it.width < 0f || it.height < 0f })
            fail("la caja del símbolo no es válida", 0)
        val rows = rows(symbols)
        val roots = symbols.filter { it.label in setOf("Raíz", "√") }
        if (roots.isNotEmpty() && roots.size == 1) {
            val root = roots.single()
            val radicand = symbols.filter { it !== root && it.minX < root.maxX && abs(it.centerY - root.centerY) <= root.height * 0.7f }
            if (radicand.isEmpty()) fail("la raíz no tiene radicando", 2)
            return try { "√(${render(radicand)})" } catch (e: MathException) {
                val err = e.error as? MathError.Syntax ?: throw e
                fail(err.message, 2 + err.position)
            }
        }
        // Exponentes cercanos pertenecen al mismo renglón aunque estén dibujados arriba.
        val mergedRows = rows.map { it.toMutableList() }.toMutableList()
        for (candidate in symbols) {
            val base = symbols.filter { it !== candidate && isExponent(it, candidate) }.maxByOrNull { it.maxX } ?: continue
            val baseRow = mergedRows.firstOrNull { base in it } ?: continue
            val expRow = mergedRows.firstOrNull { candidate in it } ?: continue
            if (baseRow !== expRow) { baseRow += expRow; mergedRows.remove(expRow) }
        }
        val fractions = mutableListOf<Pair<Float, String>>()
        val consumed = mutableSetOf<LayoutSymbol>()
        val bars = symbols.filter(::isBar).sortedByDescending { it.width }
        for (bar in bars) {
            if (bar in consumed) continue
            val scale = typicalHeight(symbols)
            val margin = scale * 0.18f
            val verticalReach = max(scale * 2f, bar.width * 1.25f)
            val above = symbols.filter { it !== bar && it !in consumed && it.label !in setOf("Raíz", "√") &&
                bar.centerY - it.centerY <= verticalReach && it.centerY < bar.centerY && overlapsX(it, bar, margin) }
            val below = symbols.filter { it !== bar && it !in consumed && it.label !in setOf("Raíz", "√") &&
                it.centerY - bar.centerY <= verticalReach && it.centerY > bar.centerY && overlapsX(it, bar, margin) }
            if (above.isEmpty() || below.isEmpty()) continue
            val numerator = render(above)
            val denominator = render(below)
            val fraction = "($numerator)/($denominator)"
            fractions += bar.minX to fraction
            consumed.addAll(above); consumed.addAll(below); consumed.add(bar)
        }
        if (fractions.isNotEmpty()) {
            val roots = symbols.filter { it.label in setOf("Raíz", "√") }
            val fractionCenterY = consumed.map { it.centerY }.average().toFloat()
            val typical = typicalHeight(symbols)
            val sides = symbols.filter { it !in consumed && it !in roots && abs(it.centerY - fractionCenterY) <= typical * 0.75f }
            val firstBarX = fractions.minOf { it.first }
            val lastBarRight = symbols.filter { it in consumed && isBar(it) }.maxOf { it.maxX }
            val left = sides.filter { it.maxX <= firstBarX + typical * 0.1f }
            val right = sides.filter { it.minX >= lastBarRight - typical * 0.1f }
            val between = sides - left.toSet() - right.toSet()
            val ordered = buildList {
                if (left.isNotEmpty()) add(left.minOf { it.minX } to render(left))
                if (between.isNotEmpty()) add(between.minOf { it.minX } to render(between))
                addAll(fractions.map { (x, token) ->
                    val needsGrouping = left.any { it.label in setOf("Dividido", "División", "÷") && it.minX < x }
                    x to if (needsGrouping) "($token)" else token
                })
                if (right.isNotEmpty()) add(right.minOf { it.minX } to render(right))
            }.sortedBy { it.first }
            val lineY = (consumed + sides).map { it.centerY }.average().toFloat()
            val otherLines = rows.filter { row -> row.none { it in consumed } }
            val text = emit(ordered)
            val combined = (otherLines.map { row -> row.map { it.centerY }.average().toFloat() to render(row) } + (lineY to text))
                .sortedBy { it.first }.joinToString("\n") { it.second }
            return combined
        }
        return mergedRows.joinToString("\n") { render(it) }
    }

    private fun rows(input: List<LayoutSymbol>): List<List<LayoutSymbol>> {
        val sorted = input.sortedWith(compareBy<LayoutSymbol> { it.centerY }.thenBy { it.minX })
        val typical = typicalHeight(input)
        val groups = mutableListOf<MutableList<LayoutSymbol>>()
        for (s in sorted) {
            val group = groups.minByOrNull { g -> abs(g.map { it.centerY }.average().toFloat() - s.centerY) }
            if (group != null && abs(group.map { it.centerY }.average().toFloat() - s.centerY) <= typical * 0.62f) group += s
            else groups += mutableListOf(s)
        }
        return groups.map { it.sortedBy(LayoutSymbol::minX) }.sortedBy { it.map(LayoutSymbol::centerY).average() }
    }

    private fun typicalHeight(input: List<LayoutSymbol>): Float = input.map { it.height }.filter { it > 0f }.sorted().let { it.getOrNull(it.size / 2) ?: 1f }

    private fun render(input: List<LayoutSymbol>): String {
        if (input.isEmpty()) return ""
        val ordered = input.sortedBy(LayoutSymbol::minX).toMutableList()
        val bar = ordered.filter(::isBar).maxByOrNull { it.width }
        if (bar != null) {
            val margin = typicalHeight(input) * 0.18f
            val above = input.filter { it !== bar && it.centerY < bar.centerY && overlapsX(it, bar, margin) }
            val below = input.filter { it !== bar && it.centerY > bar.centerY && overlapsX(it, bar, margin) }
            if (above.isEmpty() || below.isEmpty()) fail("la fracción necesita numerador y denominador", 0)
            val used = (above + below + bar).toSet()
            val fraction = "(${render(above)})/(${render(below)})"
            return emit(input.filterNot { it in used }.map { it.minX to atom(it, 0) } + (bar.minX to fraction))
        }
        val result = StringBuilder()
        var i = 0
        while (i < ordered.size) {
            val current = ordered[i]
            if (current.label in setOf("Raíz", "√")) {
                val radicand = ordered.drop(i + 1).takeWhile { it.minX < current.maxX && abs(it.centerY - current.centerY) <= current.height * 0.7f }
                if (radicand.isEmpty()) fail("la raíz no tiene radicando", result.length)
                try { result.append("√(").append(render(radicand)).append(')') }
                catch (e: MathException) { val err = e.error as? MathError.Syntax ?: throw e; fail(err.message, result.length + 2 + err.position) }
                i += radicand.size + 1
                continue
            }
            if (current.label in DIGITS) {
                if (i > 0 && result.isNotEmpty() && result.last().isDigit() && ordered[i - 1].label in DIGITS &&
                    current.minX - ordered[i - 1].maxX > max(current.height, ordered[i - 1].height) * 0.55f) result.append(' ')
                if (i > 0 && isExponent(ordered[i - 1], current)) result.append('^')
                val digits = StringBuilder(current.label)
                var j = i + 1
                while (j < ordered.size && ordered[j].label in DIGITS && sameBaseline(current, ordered[j]) &&
                    ordered[j].minX - ordered[j - 1].maxX <= max(current.height, ordered[j].height) * 0.55f) {
                    digits.append(ordered[j].label); j++
                }
                result.append(digits); i = j; continue
            }
            if (result.isNotEmpty() && i > 0 && isExponent(ordered[i - 1], current)) result.append('^')
            val atomText = atom(current, result.length)
            if (result.isNotEmpty() && isDigitTail(result.last()) && atomText.firstOrNull()?.isDigit() == true) result.append(' ')
            result.append(atomText); i++
        }
        return result.toString()
    }

    private fun emit(items: List<Pair<Float, String>>): String {
        val out = StringBuilder()
        for ((_, token) in items.sortedBy { it.first }) {
            if (out.isNotEmpty() && isDigitTail(out.last()) && token.firstOrNull()?.isDigit() == true) out.append(' ')
            if (out.isNotEmpty() && token.firstOrNull()?.isDigit() == true && out.last() in setOf(')', 'x', 'y', 'π', 'α', 'β')) out.append('*')
            out.append(token)
        }
        return out.toString()
    }

    private fun isDigitTail(c: Char) = c.isDigit() || c == ' '
    private fun atom(s: LayoutSymbol, position: Int): String = when (s.label.trim()) {
        "Más", "+" -> "+"; "Menos", "−", "-" -> "-"; "Por", "Multiplicación", "×", "·" -> "*"
        "Dividido", "División", "÷", "Barra" -> "/"; "Igual", "=" -> "="; "Raíz", "√" -> "√"
        "Pi", "π" -> "π"; "Abre paréntesis", "(", "Paréntesis abre" -> "("
        "Cierra paréntesis", ")", "Paréntesis cierra" -> ")"; "Coma" -> ","; "Punto" -> "."; "f( )" -> "f("
        else -> { val label = s.label.trim(); when (label.lowercase()) {
            "alfa" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"; "epsilon", "épsilon" -> "ε"
            "theta" -> "θ"; "lambda" -> "λ"; "mu" -> "μ"; "sigma" -> "σ"; "omega" -> "ω"; "rho" -> "ρ"; "pi" -> "π"
            else -> if (label.matches(Regex("\\d+")) || (label.length == 1 && label[0].isLetter())) label else fail("no se reconoce la etiqueta «$label»", position)
        } }
    }
    private fun isBar(s: LayoutSymbol) = s.label in setOf("Fracción", "Barra de fracción") ||
        (s.label == "Barra" && s.width > s.height * 2f)
    private fun overlapsX(a: LayoutSymbol, b: LayoutSymbol, margin: Float) = min(a.maxX, b.maxX + margin) >= max(a.minX, b.minX - margin)
    private fun sameBaseline(a: LayoutSymbol, b: LayoutSymbol) = abs(a.maxY - b.maxY) <= max(a.height, b.height) * 0.3f
    private fun isExponent(base: LayoutSymbol, candidate: LayoutSymbol): Boolean {
        val h = max(base.height, candidate.height)
        return candidate.height < base.height * 0.78f && candidate.centerY < base.centerY - base.height * 0.18f &&
            candidate.minX >= base.centerX && candidate.minX - base.maxX <= h * 1.5f
    }
    private fun fail(message: String, position: Int): Nothing = throw MathException(MathError.Syntax(message, position.coerceAtLeast(0)))
    private val DIGITS = ('0'..'9').map(Char::toString).toSet()
}
