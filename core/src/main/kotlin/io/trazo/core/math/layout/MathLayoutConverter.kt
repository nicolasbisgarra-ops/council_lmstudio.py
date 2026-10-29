package io.trazo.core.math.layout

import io.trazo.core.math.MathError
import io.trazo.core.math.MathException
import kotlin.math.max
import kotlin.math.min

/** Caja de un símbolo reconocido, en coordenadas de la hoja (Y crece hacia abajo). */
data class LayoutSymbol(
    val label: String,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
}

/** Convierte etiquetas y disposición espacial en las expresiones que acepta [io.trazo.core.math.MathParser]. */
object MathLayoutConverter {
    fun convert(symbols: List<LayoutSymbol>): String {
        if (symbols.isEmpty()) return ""
        val usable = symbols.filter { it.width >= 0f && it.height >= 0f }
        if (usable.isEmpty()) return ""
        val rows = rows(usable).map { it.toMutableList() }.toMutableList()
        // La barra conecta dos renglones locales: numerador y denominador.
        for (bar in usable.filter(::isBar)) {
            val touched = rows.filter { row -> row.any { it === bar || (overlapsX(it, bar) && kotlin.math.abs(it.centerY - bar.centerY) < max(bar.width, 1f)) } }
            if (touched.size > 1) {
                val merged = touched.flatten().distinct()
                rows.removeAll(touched.toSet())
                rows.add(merged.sortedBy(LayoutSymbol::minX).toMutableList())
            }
        }
        for (candidate in usable) {
            val base = usable.filter { it !== candidate && isExponent(it, candidate) }.maxByOrNull { it.maxX }
                ?: continue
            val baseRow = rows.firstOrNull { base in it } ?: continue
            val exponentRow = rows.firstOrNull { candidate in it } ?: continue
            if (baseRow !== exponentRow) {
                baseRow += exponentRow
                rows.remove(exponentRow)
            }
        }
        return rows.joinToString("\n") { render(it) }
    }

    private fun rows(input: List<LayoutSymbol>): List<List<LayoutSymbol>> {
        val sorted = input.sortedWith(compareBy<LayoutSymbol> { it.centerY }.thenBy { it.minX })
        val typical = sorted.map { it.height }.filter { it > 0f }.sorted().let { it.getOrNull(it.size / 2) ?: 1f }
        val groups = mutableListOf<MutableList<LayoutSymbol>>()
        for (s in sorted) {
            val group = groups.minByOrNull { g -> kotlin.math.abs(g.map { it.centerY }.average().toFloat() - s.centerY) }
            if (group != null && kotlin.math.abs(group.map { it.centerY }.average().toFloat() - s.centerY) <= typical * 0.62f) group += s
            else groups += mutableListOf(s)
        }
        return groups.map { it.sortedBy(LayoutSymbol::minX) }.sortedBy { it.map(LayoutSymbol::centerY).average() }
    }

    private fun render(input: List<LayoutSymbol>): String {
        val ordered = input.sortedBy(LayoutSymbol::minX).toMutableList()
        val bar = ordered.firstOrNull { isBar(it) }
        if (bar != null) {
            val above = ordered.filter { it !== bar && it.centerY < bar.centerY && overlapsX(it, bar) }
            val below = ordered.filter { it !== bar && it.centerY > bar.centerY && overlapsX(it, bar) }
            if (above.isEmpty() || below.isEmpty()) fail("la fracción necesita numerador y denominador", 0)
            val rest = ordered.filter { it !in above && it !in below && it !== bar }
            val fraction = "(${render(above)})/(${render(below)})"
            val result = StringBuilder()
            rest.filter { it.minX < bar.minX }.forEach { result.append(atom(it, result.length)) }
            result.append(fraction)
            rest.filter { it.minX >= bar.minX }.forEach { result.append(atom(it, result.length)) }
            return result.toString()
        }

        val result = StringBuilder()
        var i = 0
        while (i < ordered.size) {
            val current = ordered[i]
            if (current.label in setOf("Raíz", "√")) {
                val radicand = mutableListOf<LayoutSymbol>()
                var j = i + 1
                while (j < ordered.size && ordered[j].minX < current.maxX &&
                    kotlin.math.abs(ordered[j].centerY - current.centerY) <= current.height * 0.7f) {
                    radicand += ordered[j++]
                }
                if (radicand.isEmpty()) fail("la raíz no tiene radicando", result.length)
                result.append("√(").append(render(radicand)).append(')')
                i = j
                continue
            }
            // Cifras manuscritas próximas, de altura y línea base similares, forman un solo número.
            if (current.label in DIGITS) {
                if (i > 0 && isExponent(ordered[i - 1], current)) result.append('^')
                val digits = StringBuilder(current.label)
                var j = i + 1
                while (j < ordered.size && ordered[j].label in DIGITS && sameBaseline(current, ordered[j]) &&
                    ordered[j].minX - ordered[j - 1].maxX <= max(current.height, ordered[j].height) * 0.55f) {
                    digits.append(ordered[j].label)
                    j++
                }
                result.append(digits)
                i = j
                continue
            }
            // Un símbolo chico ubicado arriba y a la derecha es exponente del átomo anterior.
            if (result.isNotEmpty() && i > 0 && isExponent(ordered[i - 1], current)) {
                result.append('^').append(atom(current, result.length))
            } else result.append(atom(current, result.length))
            i++
        }
        return result.toString()
    }

    private fun atom(s: LayoutSymbol, position: Int): String = when (s.label.trim()) {
        "Más", "+" -> "+"
        "Menos", "−", "-" -> "-"
        "Por", "Multiplicación", "×", "·" -> "*"
        "Dividido", "División", "÷", "Barra" -> "/"
        "Igual", "=" -> "="
        "Raíz", "√" -> "√"
        "Pi", "π" -> "π"
        "Abre paréntesis", "(", "Paréntesis abre" -> "("
        "Cierra paréntesis", ")", "Paréntesis cierra" -> ")"
        "Coma" -> ","
        "Punto" -> "."
        "f( )" -> "f("
        else -> {
            val label = s.label.trim()
            when (label.lowercase()) {
                "alfa" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"
                "epsilon", "épsilon" -> "ε"; "theta" -> "θ"; "lambda" -> "λ"
                "mu" -> "μ"; "sigma" -> "σ"; "omega" -> "ω"; "rho" -> "ρ"
                "pi" -> "π"
                else -> if (label.matches(Regex("\\d+")) || (label.length == 1 && label[0].isLetter())) label
                else fail("no se reconoce la etiqueta «$label»", position)
            }
        }
    }

    private fun isBar(s: LayoutSymbol) = s.label in setOf("Fracción", "Barra de fracción") ||
        (s.label == "Barra" && s.width > s.height * 2f) || (s.width > max(s.height * 2f, 1f) && s.height < 12f)
    private fun overlapsX(a: LayoutSymbol, b: LayoutSymbol) = min(a.maxX, b.maxX) > max(a.minX, b.minX)
    private fun sameBaseline(a: LayoutSymbol, b: LayoutSymbol) = kotlin.math.abs(a.maxY - b.maxY) <= max(a.height, b.height) * 0.3f
    private fun isExponent(base: LayoutSymbol, candidate: LayoutSymbol) = candidate.height < base.height * 0.78f &&
        candidate.centerY < base.centerY - base.height * 0.18f && candidate.minX >= base.centerX
    private fun fail(message: String, position: Int): Nothing = throw MathException(MathError.Syntax(message, position.coerceAtLeast(0)))
    private val DIGITS = ('0'..'9').map(Char::toString).toSet()
}
