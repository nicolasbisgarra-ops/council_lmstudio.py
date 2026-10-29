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

/** Convierte las cajas reconocidas en una expresión, resolviendo las estructuras de afuera hacia adentro. */
object MathLayoutConverter {
    private data class Token(val x: Float, val right: Float, val y: Float, val text: String)

    fun convert(symbols: List<LayoutSymbol>): String {
        if (symbols.isEmpty()) return ""
        if (symbols.any { !it.minX.isFinite() || !it.minY.isFinite() || !it.maxX.isFinite() || !it.maxY.isFinite() || it.width < 0f || it.height < 0f })
            fail("la caja del símbolo no es válida", 0)
        return rows(symbols).joinToString("\n") { render(it) }
    }

    private fun render(input: List<LayoutSymbol>): String {
        if (input.isEmpty()) return ""
        val items = input.toMutableSet()
        val tokens = mutableListOf<Token>()
        val scale = typicalHeight(input)

        // Resolver primero la estructura de mayor extensión espacial. Cada recursión reduce el conjunto.
        for (root in input.filter(::isRoot).filter { candidate -> input.any { isBar(it) && it.minX >= candidate.minX && it.maxX <= candidate.maxX && it.centerY >= candidate.minY && it.centerY <= candidate.maxY } }.sortedByDescending { it.width + it.height }) {
            if (root !in items) continue
            val inside = items.filter { it !== root && it.minX >= root.minX + scale * .12f &&
                it.centerX <= root.maxX + scale * .12f && abs(it.centerY - root.centerY) <= max(root.height * .85f, scale * .8f) }
            if (inside.isEmpty()) continue
            val text = "√(${render(inside)})"
            tokens += Token(root.minX, inside.maxOf { it.maxX }, root.centerY, text)
            items.removeAll(inside.toSet()); items.remove(root)
        }

        // Una barra agrupa todos los elementos de arriba y abajo que la atraviesan. Barras anidadas
        // se procesan de mayor extensión a menor; sus operandos se vuelven a analizar recursivamente.
        for (bar in input.filter(::isBar).sortedByDescending { it.width }) {
            if (bar !in items) continue
            val margin = scale * .05f
            val reach = max(bar.width * 1.3f, scale * 2.2f)
            val above = items.filter { it !== bar && it.centerY < bar.centerY && bar.centerY - it.centerY <= reach && overlapsX(it, bar, margin) }
            val below = items.filter { it !== bar && it.centerY > bar.centerY && it.centerY - bar.centerY <= reach && overlapsX(it, bar, margin) }
            if (above.isEmpty() || below.isEmpty()) continue
            val numerator = shifted(above, 1) { render(it) }
            val denominator = shifted(below, numerator.length + 4) { render(it) }
            val x0 = min(bar.minX, (above + below).minOf { it.minX })
            val x1 = max(bar.maxX, (above + below).maxOf { it.maxX })
            tokens += Token(x0, x1, bar.centerY, "($numerator)/($denominator)")
            items.removeAll((above + below + bar).toSet())
        }

        for (root in input.filter(::isRoot).sortedByDescending { it.width + it.height }) {
            if (root !in items) continue
            val inside = items.filter { it !== root && it.minX >= root.minX + scale * .12f &&
                it.centerX <= root.maxX + scale * .12f && abs(it.centerY - root.centerY) <= max(root.height * .85f, scale * .8f) }
            if (inside.isEmpty()) continue
            val radicand = shifted(inside, 2) { render(it) }
            tokens += Token(root.minX, inside.maxOf { it.maxX }, root.centerY, "√($radicand)")
            items.removeAll(inside.toSet()); items.remove(root)
        }

        // Exponentes son subárboles completos y mantienen sus paréntesis si contienen operadores.
        for (candidate in items.toList()) {
            val baseToken = tokens.filter { candidate.label !in setOf("Más", "+", "Menos", "−", "-", "Igual", "=") && candidate.minX >= it.x && candidate.minX - it.right <= scale * 1.5f &&
                candidate.centerY < it.y - scale * .18f && it.y - candidate.centerY <= scale * 2.2f }
                .maxByOrNull { it.right } ?: continue
            tokens.remove(baseToken)
            val suffix = atom(candidate, tokens, input)
            val baseText = if ('/' in baseToken.text) "(${baseToken.text})" else baseToken.text
            tokens += baseToken.copy(right = candidate.maxX, text = "$baseText^$suffix")
            items.remove(candidate)
        }
        for (base in items.sortedBy { it.minX }.toList()) {
            if (base !in items) continue
            val exponentToken = tokens.filter { (base.label.trim() in DIGITS || base.label.trim().length == 1 && base.label.trim()[0].isLetter()) && it.x >= base.centerX && it.x - base.maxX <= scale * 2.5f &&
                it.y < base.centerY - base.height * .18f && base.centerY - it.y <= max(scale * 2.2f, base.height * .95f) }
                .maxByOrNull { it.right }
            if (exponentToken != null) {
                tokens.remove(exponentToken)
                val baseText = atom(base, tokens, input)
                val exp = exponentToken.text
                tokens += Token(base.minX, exponentToken.right, base.centerY, "$baseText^($exp)")
                items.remove(base)
                continue
            }
            val exponent = items.filter { it !== base && isExponent(base, it, scale) }
            if (exponent.isEmpty()) continue
            val baseText = atom(base, tokens, input)
            val expText = render(exponent)
            val grouped = exponent.size > 1 || exponent.any { isBar(it) || isRoot(it) } || expText.any { it in "+-*/" }
            tokens += Token(base.minX, (listOf(base) + exponent).maxOf { it.maxX }, base.centerY,
                "$baseText^${if (grouped) "($expText)" else expText}")
            items.remove(base); items.removeAll(exponent.toSet())
        }
        for (symbol in items) tokens += Token(symbol.minX, symbol.maxX, symbol.centerY, atom(symbol, tokens, input))

        val ordered = tokens.sortedWith(compareBy<Token> { it.x }.thenBy { it.y })
        val out = StringBuilder()
        var previous: Token? = null
        for (token in ordered) {
            if (out.isNotEmpty()) {
                val prev = previous!!
                if (prev.text.lastOrNull()?.isDigit() == true && token.text.firstOrNull()?.isDigit() == true) {
                    if (token.x - prev.right > scale * .55f) out.append(' ')
                } else if (prev.text.lastOrNull() == '/' && token.text.startsWith('(')) {
                    out.append('(')
                    out.append(token.text).append(')')
                    previous = token
                    continue
                } else if (token.text.firstOrNull()?.isDigit() == true && prev.text.lastOrNull() in listOf(')', 'x', 'y', 'π', 'α', 'β')) out.append('*')
            }
            out.append(token.text)
            previous = token
        }
        return out.toString()
    }

    private inline fun <T> shifted(symbols: List<LayoutSymbol>, offset: Int, render: (List<LayoutSymbol>) -> T): T = try {
        render(symbols)
    } catch (e: MathException) {
        val syntax = e.error as? MathError.Syntax ?: throw e
        fail(syntax.detail, offset + syntax.position)
    }

    private fun atom(s: LayoutSymbol, tokens: List<Token>, all: List<LayoutSymbol>): String {
        val priorTokens = tokens.filter { it.x < s.minX }
        val position = if (priorTokens.isNotEmpty()) priorTokens.sumOf { it.text.length } else all.filter { it !== s && it.maxX <= s.minX }.sumOf {
            when (it.label.trim()) { "Más", "+", "Menos", "−", "-", "Igual", "=", "Por", "×", "Dividido", "÷" -> 1; else -> it.label.trim().length }
        }
        return when (s.label.trim()) {
            "Más", "+" -> "+"; "Menos", "−", "-" -> "-"; "Por", "Multiplicación", "×", "·" -> "*"
            "Dividido", "División", "÷", "Barra" -> "/"; "Igual", "=" -> "="; "Raíz", "√" -> "√"
            "Pi", "π" -> "π"; "Abre paréntesis", "(", "Paréntesis abre" -> "("
            "Cierra paréntesis", ")", "Paréntesis cierra" -> ")"; "Coma" -> ","; "Punto" -> "."; "f( )" -> "f("
            else -> {
                val label = s.label.trim()
                when (label.lowercase()) {
                    "alfa" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"; "epsilon", "épsilon" -> "ε"
                    "theta" -> "θ"; "lambda" -> "λ"; "mu" -> "μ"; "sigma" -> "σ"; "omega" -> "ω"; "rho" -> "ρ"; "pi" -> "π"
                    else -> if (label.matches(Regex("\\d+")) || (label.length == 1 && label[0].isLetter())) label
                    else fail("no se reconoce la etiqueta «$label»", position)
                }
            }
        }
    }

    private fun rows(input: List<LayoutSymbol>): List<List<LayoutSymbol>> {
        val scale = typicalHeight(input)
        val sorted = input.sortedBy { it.centerY }
        val groups = mutableListOf<MutableList<LayoutSymbol>>()
        for (s in sorted) {
            val group = groups.minByOrNull { abs(it.map(LayoutSymbol::centerY).average().toFloat() - s.centerY) }
            if (group != null && abs(group.map(LayoutSymbol::centerY).average().toFloat() - s.centerY) <= scale * .62f) group += s
            else groups += mutableListOf(s)
        }
        // Las cajas de una fracción o de un radicando pueden ocupar varias bandas verticales.
        // La estructura espacial vuelve a unirlas antes de decidir los saltos de línea.
        var changed = true
        while (changed) {
            changed = false
            for (bar in input.filter(::isBar)) {
                val related = input.filter { it === bar || (overlapsX(it, bar, scale * .2f) &&
                    abs(it.centerY - bar.centerY) <= max(bar.width * 1.3f, scale * 2.2f)) }
                changed = mergeGroups(groups, related) || changed
            }
            for (root in input.filter(::isRoot)) {
                val related = input.filter { it === root || (it.minX >= root.minX + scale * .12f &&
                    it.centerX <= root.maxX + scale * .12f && abs(it.centerY - root.centerY) <= max(root.height * .85f, scale * .8f)) }
                changed = mergeGroups(groups, related) || changed
            }
        }
        // Un exponente puede estar en una banda vertical distinta, pero solo si está cerca de la base.
        for (candidate in input) for (base in input) if (isExponent(base, candidate, scale)) {
            val b = groups.firstOrNull { base in it }; val e = groups.firstOrNull { candidate in it }
            if (b != null && e != null && b !== e) { b += e; groups.remove(e) }
        }
        return groups.map { it.distinct().sortedBy(LayoutSymbol::minX) }.sortedBy { it.map(LayoutSymbol::centerY).average() }
    }

    private fun mergeGroups(groups: MutableList<MutableList<LayoutSymbol>>, related: List<LayoutSymbol>): Boolean {
        val matching = groups.filter { group -> group.any { it in related } }
        if (matching.size < 2) return false
        val target = matching.first()
        matching.drop(1).forEach { target.addAll(it); groups.remove(it) }
        return true
    }

    private fun typicalHeight(input: List<LayoutSymbol>) = input.map { it.height }.filter { it > 0f }.sorted().let { it.getOrNull(it.size / 2) ?: 1f }
    private fun isRoot(s: LayoutSymbol) = s.label in setOf("Raíz", "√")
    private fun isBar(s: LayoutSymbol) = s.label in setOf("Fracción", "Barra de fracción") || (s.label == "Barra" && s.width > s.height * 2f)
    private fun overlapsX(a: LayoutSymbol, b: LayoutSymbol, margin: Float) = min(a.maxX, b.maxX + margin) >= max(a.minX, b.minX - margin)
    private fun isExponent(base: LayoutSymbol, candidate: LayoutSymbol, scale: Float): Boolean =
        base !== candidate && candidate.height < base.height * .78f && candidate.centerY < base.centerY - base.height * .18f &&
            candidate.minX >= base.centerX && candidate.minX - base.maxX <= scale * 2.5f && base.centerY - candidate.centerY <= max(scale * 2.2f, base.height * .95f)
    private fun fail(message: String, position: Int): Nothing = throw MathException(MathError.Syntax(message, position.coerceAtLeast(0)))
    private val DIGITS = ('0'..'9').map(Char::toString).toSet()
}
