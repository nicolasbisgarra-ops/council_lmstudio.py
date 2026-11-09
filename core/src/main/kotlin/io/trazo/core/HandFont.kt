package io.trazo.core

import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Letra manuscrita de un solo trazo para escribir respuestas en la hoja como si fueran a mano: cada carácter
 * es una lista de trazos en una grilla con la línea de base en 0, mayúsculas y cifras de 0 a −10, minúsculas
 * hasta −6 y descendentes hasta +4. Al escribir se inclina como la letra del usuario, se suaviza y cada
 * carácter varía un poco (siempre igual para el mismo texto).
 */
internal object HandFont {
    class G(val w: Float, val strokes: List<List<Pair<Float, Float>>>)

    private fun g(w: Float, spec: String) = G(w, spec.split('|').map { s ->
        s.trim().split(' ').filter { it.isNotBlank() }.map { p -> p.split(',').let { it[0].toFloat() to it[1].toFloat() } }
    })

    private fun join(a: G, extra: String, w: Float = a.w) = G(w, a.strokes + g(w, extra).strokes)

    val glyphs: Map<Char, G> = buildMap {
        put('0', g(5f, "2.5,-10 0.8,-9 0,-5 0.8,-1 2.5,0 4.2,-1 5,-5 4.2,-9 2.5,-10"))
        put('1', g(4f, "1,-8 3,-10 3,0"))
        put('2', g(5f, "0.3,-8 1.5,-9.7 3.5,-9.7 4.6,-8 4.2,-6 0,0 5,0"))
        put('3', g(5f, "0.3,-9 2,-10 4,-9.5 4.5,-7.8 3.6,-5.8 2,-5.5 3.8,-5 4.8,-3 4.2,-0.9 2.3,0 0.2,-1"))
        put('4', g(5f, "3.8,0 3.8,-10 0,-3 5,-3"))
        put('5', g(5f, "4.6,-10 1,-10 0.6,-5.8 2.5,-6.3 4.3,-5.5 4.9,-3 4.2,-0.9 2.3,0 0.2,-1"))
        put('6', g(5f, "4.3,-9.5 2.6,-10 1,-8.8 0.1,-5 0.6,-1.2 2.4,0 4.3,-0.9 4.9,-3 4.1,-5.2 2.4,-5.8 0.6,-4.5"))
        put('7', g(5f, "0,-10 5,-10 1.8,0"))
        put('8', g(5f, "2.5,-5.5 0.8,-6.5 0.6,-8.6 2.5,-10 4.4,-8.6 4.2,-6.5 2.5,-5.5 0.4,-4.3 0,-2 1,-0.4 2.5,0 4,-0.4 5,-2 4.6,-4.3 2.5,-5.5"))
        put('9', g(5f, "4.5,-6 2.6,-4.8 0.6,-5.5 0,-7.8 1.2,-9.7 3.2,-9.9 4.6,-8.5 4.8,-5 4,-1.2 2.3,0 0.6,-0.8"))

        put('a', g(5f, "4.4,-4.6 3,-6 1.2,-5.6 0.2,-3 0.8,-0.6 2.2,0 3.6,-1 4.4,-3.5 4.4,-6 4.4,-1 5,0"))
        put('b', g(5f, "0.5,-10 0.5,0 | 0.5,-3.5 1.8,-5.6 3.3,-6 4.6,-4.8 4.9,-2.6 4,-0.5 2.4,0 0.5,-0.8"))
        put('c', g(4.5f, "4.3,-5.2 3,-6 1.3,-5.6 0.2,-3 0.9,-0.7 2.5,0 4.3,-0.8"))
        put('d', g(5f, "4.3,-4.8 3,-6 1.2,-5.6 0.2,-3 0.8,-0.6 2.2,0 3.6,-1 4.3,-3 | 4.3,-10 4.3,-1 4.9,0"))
        put('e', g(4.8f, "0.4,-3 4.4,-3.4 4,-5.3 2.5,-6 0.9,-5.3 0.2,-3 0.8,-0.8 2.4,0 4.2,-0.8"))
        put('f', g(4f, "4,-9.5 3,-10 1.9,-9.6 1.5,-8 1.5,0 | 0,-6 3.5,-6"))
        put('g', g(5f, "4.3,-4.8 3,-6 1.2,-5.6 0.3,-3.4 1,-1.4 2.4,-1 4.3,-2.6 | 4.3,-6 4.3,2.5 3.4,3.8 1.8,4 0.4,3.2"))
        put('h', g(5f, "0.5,-10 0.5,0 | 0.5,-3.8 1.8,-5.6 3.2,-6 4.3,-5 4.5,-3 4.5,0"))
        put('i', g(2f, "1,-6 1,-0.5 1.6,0 | 1,-8.6 1.05,-8.4"))
        put('ı', g(2f, "1,-6 1,-0.5 1.6,0"))
        put('j', g(3f, "2.2,-6 2.2,2.5 1.5,3.8 0.2,3.6 | 2.2,-8.6 2.25,-8.4"))
        put('k', g(4.5f, "0.5,-10 0.5,0 | 4,-6 0.5,-2.6 | 1.8,-3.6 4.4,0"))
        put('l', g(2.2f, "1,-10 1,-0.8 1.8,0"))
        put('m', g(6.6f, "0.4,-6 0.4,0 | 0.4,-4.2 1.4,-5.8 2.6,-5.8 3.3,-4.4 3.3,0 | 3.3,-4.4 4.1,-5.8 5.3,-5.8 6.2,-4.4 6.2,0"))
        put('n', g(4.8f, "0.4,-6 0.4,0 | 0.4,-4 1.7,-5.7 3,-6 4.1,-5 4.4,-3.4 4.4,0"))
        put('o', g(5f, "2.5,-6 0.9,-5.3 0.2,-3 0.9,-0.7 2.5,0 4.1,-0.7 4.8,-3 4.1,-5.3 2.5,-6"))
        put('p', g(5f, "0.5,-6 0.5,4 | 0.5,-4 1.8,-5.7 3.3,-6 4.6,-4.8 4.9,-2.8 4,-0.6 2.4,0 0.5,-1"))
        put('q', g(5f, "4.3,-4.8 3,-6 1.2,-5.6 0.2,-3 0.8,-0.6 2.2,0 3.6,-1 4.3,-3 | 4.3,-6 4.3,4 5,3.3"))
        put('r', g(3.8f, "0.5,-6 0.5,0 | 0.5,-3.6 1.6,-5.5 3,-6 3.8,-5.7"))
        put('s', g(4.2f, "3.9,-5.4 2.4,-6 0.9,-5.6 0.5,-4.4 1.3,-3.4 3.2,-2.7 4,-1.6 3.4,-0.4 1.9,0 0.2,-0.6"))
        put('t', g(3.6f, "1.6,-9 1.6,-1 2.3,0 3.5,-0.3 | 0,-6 3.4,-6"))
        put('u', g(5f, "0.4,-6 0.4,-2 1.2,-0.3 2.4,0 3.6,-0.6 4.3,-2.2 | 4.3,-6 4.3,-0.6 4.9,0"))
        put('v', g(4.4f, "0,-6 2.2,0 4.4,-6"))
        put('w', g(6f, "0,-6 1.5,0 3,-4.6 4.5,0 6,-6"))
        put('x', g(4.4f, "0.2,-6 4.2,0 | 4.2,-6 0.2,0"))
        put('y', g(4.6f, "0.2,-6 2.4,-0.4 | 4.4,-6 2,1.8 1.2,3.5 0,4"))
        put('z', g(4.6f, "0.3,-6 4.3,-6 0.2,0 4.4,0"))

        put('A', g(5.6f, "0,0 2.8,-10 5.6,0 | 1,-3.5 4.6,-3.5"))
        put('B', g(5.2f, "0.5,0 0.5,-10 3.2,-10 4.5,-9 4.4,-6.8 3,-5.5 0.5,-5.5 | 3,-5.5 4.6,-4.6 5,-2.6 4.3,-0.6 2.8,0 0.5,0"))
        put('C', g(5.2f, "5,-8.7 3.6,-10 1.8,-9.6 0.4,-7.5 0,-5 0.5,-2.2 1.9,-0.4 3.6,0 5,-1.2"))
        put('D', g(5.4f, "0.5,0 0.5,-10 2.6,-10 4.4,-8.8 5.2,-5 4.5,-1.4 2.6,0 0.5,0"))
        put('E', g(5f, "4.6,-10 0.5,-10 0.5,0 4.8,0 | 0.5,-5.2 3.8,-5.2"))
        put('F', g(4.8f, "4.6,-10 0.5,-10 0.5,0 | 0.5,-5.2 3.6,-5.2"))
        put('G', g(5.4f, "5,-8.6 3.6,-10 1.8,-9.6 0.4,-7.5 0,-5 0.5,-2.2 1.9,-0.4 3.6,0 5,-1 5.1,-4.4 3,-4.4"))
        put('H', g(5.5f, "0.5,-10 0.5,0 | 5,-10 5,0 | 0.5,-5.2 5,-5.2"))
        put('I', g(2.4f, "1.2,-10 1.2,0 | 0,-10 2.4,-10 | 0,0 2.4,0"))
        put('J', g(4.4f, "4,-10 4,-2.3 3.2,-0.4 1.8,0 0.5,-0.8 0,-2.2"))
        put('K', g(5f, "0.5,-10 0.5,0 | 4.8,-10 0.5,-4 | 2,-5.6 5,0"))
        put('L', g(4.6f, "0.5,-10 0.5,0 4.5,0"))
        put('M', g(6.5f, "0.3,0 0.6,-10 3.2,-3.5 5.8,-10 6.2,0"))
        put('N', g(5.5f, "0.5,0 0.5,-10 5,0 5,-10"))
        val o = g(6f, "3,-10 1,-8.8 0,-5 1,-1.2 3,0 5,-1.2 6,-5 5,-8.8 3,-10")
        put('O', o)
        put('Q', join(o, "3.6,-2.4 6,0.6"))
        val p = g(5f, "0.5,0 0.5,-10 3.2,-10 4.6,-9 4.9,-7.4 4.4,-5.6 3,-4.8 0.5,-4.8")
        put('P', p)
        put('R', join(p, "2.6,-4.8 5,0", 5.2f))
        put('S', g(5f, "4.6,-9 3.2,-10 1.4,-9.8 0.4,-8.5 0.8,-6.5 2.6,-5.4 4.4,-4.3 4.9,-2.3 4.1,-0.6 2.4,0 0.3,-1"))
        put('T', g(5.2f, "0,-10 5.2,-10 | 2.6,-10 2.6,0"))
        put('U', g(5.5f, "0.5,-10 0.5,-3 1.2,-0.8 2.8,0 4.4,-0.8 5,-3 5,-10"))
        put('V', g(5.4f, "0,-10 2.7,0 5.4,-10"))
        put('W', g(7.2f, "0,-10 1.8,0 3.6,-7 5.4,0 7.2,-10"))
        put('X', g(5.2f, "0.2,-10 5,0 | 5,-10 0.2,0"))
        put('Y', g(5.2f, "0,-10 2.6,-5 5.2,-10 | 2.6,-5 2.6,0"))
        put('Z', g(5.4f, "0.3,-10 5,-10 0.2,0 5.2,0"))

        put('+', g(5.2f, "0.2,-4 5,-4 | 2.6,-6.4 2.6,-1.6"))
        val minus = g(5f, "0.4,-4 4.6,-4")
        put('-', minus); put('−', minus)
        val eq = g(5f, "0.3,-5.2 4.7,-5.2 | 0.3,-2.8 4.7,-2.8")
        put('=', eq)
        put('≠', join(eq, "3.6,-7 1.4,-1"))
        put('×', g(4.8f, "0.6,-6.2 4.2,-2 | 4.2,-6.2 0.6,-2"))
        put('·', g(2f, "1,-4.1 1.05,-3.9"))
        put('÷', g(5f, "0.3,-4 4.7,-4 | 2.5,-6.2 2.55,-6 | 2.5,-2 2.55,-1.8"))
        put('*', g(4f, "0.6,-8.5 3.4,-5.5 | 3.4,-8.5 0.6,-5.5 | 2,-9 2,-5"))
        put('/', g(3.6f, "3.6,-10.5 0,1"))
        put('(', g(2.6f, "2.4,-10.6 0.8,-8 0.2,-4.5 0.8,-1 2.4,1.6"))
        put(')', g(2.6f, "0.2,-10.6 1.8,-8 2.4,-4.5 1.8,-1 0.2,1.6"))
        put('[', g(2.6f, "2.4,-10.6 0.4,-10.6 0.4,1.6 2.4,1.6"))
        put(']', g(2.6f, "0.2,-10.6 2.2,-10.6 2.2,1.6 0.2,1.6"))
        put('{', g(2.8f, "2.6,-10.6 1.4,-10 1.2,-5.8 0.2,-4.5 1.2,-3.2 1.4,1 2.6,1.6"))
        put('}', g(2.8f, "0.2,-10.6 1.4,-10 1.6,-5.8 2.6,-4.5 1.6,-3.2 1.4,1 0.2,1.6"))
        put('.', g(1.4f, "0.6,-0.3 0.75,-0.1"))
        put(',', g(1.6f, "1,-0.6 0.4,1.8"))
        put(':', g(1.8f, "0.8,-5.4 0.95,-5.2 | 0.8,-0.4 0.95,-0.2"))
        put(';', g(1.8f, "1,-5.4 1.15,-5.2 | 1,-0.6 0.4,1.8"))
        val bang = g(2f, "1,-10 1,-2.6 | 1,-0.3 1.1,-0.1")
        put('!', bang)
        val question = g(4.4f, "0.3,-8.3 1.5,-9.8 3.2,-9.9 4.2,-8.5 3.9,-6.6 2.3,-5.2 2.2,-2.8 | 2.2,-0.3 2.3,-0.1")
        put('?', question)
        put('¡', flip(bang)); put('¿', flip(question))
        put('\'', g(1.4f, "0.8,-10 0.6,-7.8"))
        put('"', g(2.8f, "0.8,-10 0.6,-7.8 | 2.2,-10 2,-7.8"))
        put('<', g(4.6f, "4.4,-7 0.3,-4 4.4,-1"))
        put('>', g(4.6f, "0.3,-7 4.4,-4 0.3,-1"))
        put('≤', g(4.6f, "4.4,-8 0.3,-5.2 4.4,-2.4 | 0.3,-0.6 4.4,-0.6"))
        put('≥', g(4.6f, "0.3,-8 4.4,-5.2 0.3,-2.4 | 0.3,-0.6 4.4,-0.6"))
        put('≈', g(5.2f, "0.2,-4.8 1.4,-5.6 2.6,-4.8 3.8,-4 5,-4.8 | 0.2,-2.4 1.4,-3.2 2.6,-2.4 3.8,-1.6 5,-2.4"))
        put('^', g(4f, "0.3,-7.5 2,-10 3.7,-7.5"))
        put('_', g(5f, "0,1 5,1"))
        put('%', g(5.2f, "0.2,0 5,-10 | 1.2,-10 0.3,-8.8 0.6,-7 1.6,-6.8 2.1,-8.4 1.2,-10 | 4,-3.2 3.1,-2 3.4,-0.2 4.4,0 4.9,-1.6 4,-3.2"))
        put('√', g(8f, "0,-4.6 1,-5.2 2.4,0 4.4,-11 8,-11"))
        put('π', g(5f, "0,-5.6 1,-6 5,-6 | 1.6,-6 1.2,0 | 3.8,-6 3.8,-1 4.5,0"))
        put('∞', g(7f, "3.5,-3 2.2,-4.6 0.8,-4.5 0.2,-3 0.8,-1.5 2.2,-1.4 3.5,-3 4.8,-4.6 6.2,-4.5 6.8,-3 6.2,-1.5 4.8,-1.4 3.5,-3"))
        put('→', g(6f, "0,-4 6,-4 | 4.4,-5.4 6,-4 4.4,-2.6"))
        put('⇒', g(6.2f, "0,-5 5.4,-5 | 0,-3 5.4,-3 | 4.2,-6.4 6.2,-4 4.2,-1.6"))
        put('°', g(2.2f, "0.9,-10 0.2,-9.3 0.5,-8.4 1.3,-8.2 1.8,-9 1.4,-9.9 0.9,-10"))
        put('|', g(2f, "1,-10.5 1,1.5"))
        put('±', g(5f, "0.3,-5 4.7,-5 | 2.5,-7.2 2.5,-2.8 | 0.3,-0.8 4.7,-0.8"))
        put('#', g(4.8f, "1.6,-9 0.8,0 | 3.8,-9 3,0 | 0,-6.2 4.8,-6.2 | 0,-2.8 4.6,-2.8"))

        put('α', g(5f, "4.8,-6 4,-3 3.3,-1 2,0 0.7,-0.6 0.2,-3 0.9,-5.4 2.2,-6 3.4,-5 4.1,-2 4.9,0"))
        put('β', g(5f, "0.6,4 0.6,-7.6 1.4,-9.6 2.8,-10 4,-9 3.9,-7 2.4,-5.8 | 2.4,-5.8 4.2,-4.8 4.6,-2.6 3.7,-0.5 2,0 0.6,-1"))
        put('γ', g(4.6f, "0,-5.8 1,-6 2.4,-3 2.4,4 | 2.4,-2 4.4,-6"))
        put('δ', g(4.8f, "3.8,-9.4 2.4,-10 1.2,-9.4 1.4,-8 3.6,-6 4.6,-3.4 4,-0.8 2.4,0 0.8,-0.8 0.2,-3 1,-5.3 3.4,-6.2"))
        put('ε', g(4.4f, "4.2,-5.4 2.6,-6 1.1,-5.4 1.1,-3.6 2.6,-3.2 | 2.6,-3.2 0.6,-2.6 0.4,-0.9 1.9,0 4.2,-0.6"))
        put('θ', g(4.8f, "2.4,-10 0.8,-8.8 0.2,-5 0.8,-1.2 2.4,0 4,-1.2 4.6,-5 4,-8.8 2.4,-10 | 0.2,-5 4.6,-5"))
        put('λ', g(4.6f, "0.6,-10 1.6,-9.6 4.6,0 | 2.8,-5.6 0.2,0"))
        put('μ', g(5.2f, "0.6,-6 0.6,4 | 0.6,-2 1.4,-0.3 2.6,0 3.8,-0.6 4.4,-2.2 | 4.4,-6 4.4,-0.6 5,0"))
        put('σ', g(5.2f, "5.2,-6 2.6,-6 0.9,-5.2 0.2,-3 0.9,-0.7 2.5,0 4.1,-0.7 4.7,-3 4,-5.2 2.6,-6"))
        put('τ', g(4.6f, "0,-5.6 0.8,-6 4.6,-6 | 2.4,-6 2.2,-1 3,0"))
        put('φ', g(5.2f, "2.6,-9 2.6,4 | 1.6,-6 0.4,-4.6 0.4,-1.6 1.6,-0.2 2.6,0 3.8,-0.2 4.9,-1.6 4.9,-4.6 3.8,-6 2.6,-6"))
        put('ω', g(6f, "1.2,-6 0.2,-3.4 0.6,-0.8 1.8,0 3,-1.4 3,-3.4 | 3,-1.4 4.2,0 5.4,-0.8 5.8,-3.4 4.8,-6"))
        put('Δ', g(5.6f, "0,0 2.8,-10 5.6,0 0,0"))
        put('Σ', g(5.2f, "4.8,-10 0.2,-10 2.8,-5 0.2,0 5,0"))

        // Más griegas.
        put('ρ', g(4.8f, "0.6,4 0.6,-3 1.2,-5.4 2.6,-6 4,-5.4 4.6,-3 4,-0.6 2.6,0 1.2,-0.6 0.6,-2"))
        put('η', g(4.8f, "0,-5.4 0.6,-6 0.8,0 | 0.8,-4.6 2.2,-6 3.6,-5.6 4.2,-4.2 4.2,4"))
        put('κ', g(4.4f, "0.6,-6 0.6,0 | 4,-6 0.6,-2.6 | 1.8,-3.6 4.4,0"))
        put('ν', g(4.4f, "0,-6 0.8,-5.6 2,0 3.6,-3.4 4.2,-6"))
        put('ξ', g(4.4f, "3.8,-10 1.6,-9.6 1.2,-8 2.8,-7 | 2.8,-7 0.8,-6.2 0.2,-3.4 1.4,-1 3.4,0 3.8,1.6 2.6,3 1.6,4"))
        put('ψ', g(5.6f, "0,-6 0.6,-2.4 2,-0.6 2.8,-0.4 3.6,-0.6 5,-2.4 5.6,-6 | 2.8,-9 2.8,4"))
        put('χ', g(4.8f, "0,-6 0.8,-5.6 4,3.6 4.8,4 | 4.8,-6 0,4"))
        put('ζ', g(4.2f, "0.8,-10 4,-10 1.2,-5.6 0.2,-2.6 1.2,-0.6 3.2,0 3.8,1.6 2.6,3 1.6,4"))
        put('Ω', g(6.2f, "0,0 2,0 2,-1.4 0.6,-3.6 0.4,-6.6 1.6,-9.2 3.1,-10 4.6,-9.2 5.8,-6.6 5.6,-3.6 4.2,-1.4 4.2,0 6.2,0"))
        put('Π', g(5.6f, "0,-10 5.6,-10 | 1,-10 1,0 | 4.6,-10 4.6,0"))
        put('Φ', g(6f, "3,-10 3,0 | 3,-8 1,-7.2 0,-5 1,-2.8 3,-2 5,-2.8 6,-5 5,-7.2 3,-8"))
        put('Θ', g(6f, "3,-10 1,-9 0,-5 1,-1 3,0 5,-1 6,-5 5,-9 3,-10 | 1.4,-5 4.6,-5"))
        put('Λ', g(5.6f, "0,0 2.8,-10 5.6,0"))
        put('Γ', g(4.6f, "0.6,0 0.6,-10 4.6,-10"))

        // Cálculo, conjuntos y lógica.
        put('∫', g(4f, "4,-11.4 3,-12 2.2,-11 1.8,-6 1.8,0 1.4,3 0.6,4 0,3.4"))
        put('∂', g(4.8f, "0.8,-9 2.2,-10 3.8,-9.2 4.6,-6 4.2,-2 2.6,0 1,-0.6 0.2,-2.6 1,-4.8 2.6,-5.4 4.4,-4.4"))
        put('∇', g(5.6f, "0,-10 5.6,-10 2.8,0 0,-10"))
        put('∈', g(4.6f, "4.6,-7.6 2.2,-7.6 0.6,-6.8 0,-5 0.6,-3.2 2.2,-2.4 4.6,-2.4 | 0,-5 4,-5"))
        put('∪', g(5f, "0,-8 0,-3 1,-0.6 2.5,0 4,-0.6 5,-3 5,-8"))
        put('∩', g(5f, "0,0 0,-5 1,-7.4 2.5,-8 4,-7.4 5,-5 5,0"))
        put('⊂', g(4.6f, "4.6,-8 2.2,-8 0.6,-7 0,-5 0.6,-3 2.2,-2 4.6,-2"))
        put('∅', g(5.4f, "2.7,-9 1,-8 0.4,-5 1,-2 2.7,-1 4.4,-2 5,-5 4.4,-8 2.7,-9 | 5.4,-10 0,0"))
        put('∀', g(5.4f, "0,-10 2.7,0 5.4,-10 | 1.2,-5.4 4.2,-5.4"))
        put('∃', g(4.4f, "0,-10 4.4,-10 4.4,0 0,0 | 0.6,-5 4.4,-5"))
        put('∴', g(4.4f, "2.2,-6 2.35,-5.8 | 0.4,-1 0.55,-0.8 | 4,-1 4.15,-0.8"))
        put('∠', g(5.6f, "5.6,-9 0,0 5.6,0"))
        put('⊥', g(5.6f, "2.8,-10 2.8,0 | 0,0 5.6,0"))
        put('∥', g(2.6f, "0.6,-10 0.6,0 | 2,-10 2,0"))
        put('≡', g(5f, "0,-7 5,-7 | 0,-5 5,-5 | 0,-3 5,-3"))
        put('∝', g(5.6f, "5.6,-6.6 4.4,-7 3,-6 1.6,-4 0.6,-3 0,-5 0.6,-7 1.6,-6 3,-4 4.4,-3 5.6,-3.4"))
        put('←', g(6f, "6,-4 0,-4 | 1.6,-5.4 0,-4 1.6,-2.6"))
        put('↔', g(7f, "0,-4 7,-4 | 1.6,-5.4 0,-4 1.6,-2.6 | 5.4,-5.4 7,-4 5.4,-2.6"))
        put('⇔', g(7.4f, "1.2,-5 6.2,-5 | 1.2,-3 6.2,-3 | 2,-6.4 0,-4 2,-1.6 | 5.4,-6.4 7.4,-4 5.4,-1.6"))
        put('↑', g(3f, "1.5,0 1.5,-10 | 0,-8.4 1.5,-10 3,-8.4"))
        put('↓', g(3f, "1.5,-10 1.5,0 | 0,-1.6 1.5,0 3,-1.6"))

        // Signos de texto que faltaban.
        put('~', g(5f, "0,-4 1,-5 2,-5 3,-4 4,-4 5,-5"))
        put('&', g(5.4f, "5.4,0 1,-6.4 0.8,-8.6 2,-10 3.4,-9.4 3.4,-7.8 0.6,-5 0,-2.6 0.8,-0.6 2.4,0 4,-0.8 5.2,-3"))
        put('@', g(7f, "4.6,-6 3.2,-6.4 2.2,-5.4 2.2,-3.6 3.2,-3 4.6,-3.6 4.8,-6 4.8,-3.4 5.6,-3 6.6,-4 7,-5.6 6.2,-8.4 4,-9.6 1.6,-9 0.2,-6.6 0.2,-3.4 1.6,-1.2 4,-0.6 6,-1.4"))
        put('$', g(4.6f, "4.4,-8.4 3,-9 1.4,-9 0.4,-8 0.6,-6.4 2.4,-5.4 4,-4.4 4.4,-2.6 3.4,-1.2 1.6,-1 0,-1.8 | 2.3,-10.4 2.3,0.4"))
        put('€', g(5.4f, "5.2,-9 3.6,-10 1.8,-9.2 0.8,-5 1.8,-0.8 3.6,0 5.2,-1 | 0,-6 3.8,-6 | 0,-4 3.4,-4"))
        put('§', g(4.2f, "3.8,-9.4 2.4,-10 1,-9.4 1,-8 3.4,-6.4 3.8,-4.8 3,-3.6 | 1.2,-6.6 0.4,-5.2 1,-4 3.2,-2.6 3.2,-1 1.8,0 0.4,-0.6"))
    }

    /** ¿ y ¡: el signo dado vuelta, con el punto arriba. */
    private fun flip(a: G) = G(a.w, a.strokes.map { s -> s.map { (x, y) -> (a.w - x) to (-10f - y + 2f) } })

    private val aliases = mapOf('–' to "-", '—' to "-", '∙' to "·", '…' to "...", '⋅' to "·", ' ' to " ",
        '∑' to "Σ", '∏' to "Π", 'µ' to "μ", '’' to "'", '‘' to "'", '“' to "\"", '”' to "\"", 'Ø' to "∅", '∆' to "Δ",
        '\u2126' to "Ω", '⟹' to "⇒", '⟺' to "⇔", '⟶' to "→")
    private val superscripts = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7',
        '⁸' to '8', '⁹' to '9', '⁻' to '-', '⁺' to '+',
    )

    /** Un carácter a dibujar: su forma (null si no hay), su escala, cuánto sube y sus marcas (acentos). */
    private class Unit(val g: G?, val scale: Float, val raise: Float, val marks: List<List<Pair<Float, Float>>>)

    private fun units(text: String): List<Unit> {
        val out = mutableListOf<Unit>()
        val s = Normalizer.normalize(text.map { aliases[it] ?: it.toString() }.joinToString(""), Normalizer.Form.NFD)
        var i = 0
        while (i < s.length) {
            var c = s[i++]
            val marks = mutableListOf<Char>()
            while (i < s.length && Character.getType(s[i]) == Character.NON_SPACING_MARK.toInt()) marks += s[i++]
            val sup = superscripts[c]
            if (sup != null) {
                out += Unit(glyphs[sup], 0.55f, 5.5f, emptyList())
                continue
            }
            if (c == ' ') { out += Unit(null, 1f, 0f, emptyList()); continue }
            if (c == 'i' && marks.isNotEmpty()) c = 'ı'
            val g = glyphs[c] ?: glyphs[c.lowercaseChar()]
            out += Unit(g ?: G(4f, emptyList()), 1f, 0f, if (g == null) emptyList() else marks.flatMap { mark(it, g.w, c) })
        }
        return out
    }

    private fun mark(m: Char, w: Float, base: Char): List<List<Pair<Float, Float>>> {
        val cx = w / 2f
        val top = if (base.isLowerCase() || base == 'ı') -6f else -10f
        fun dot(x: Float, y: Float) = listOf(x to y, (x + 0.15f) to (y + 0.2f))
        return when (m) {
            '́' -> listOf(listOf((cx - 0.5f) to (top - 1.2f), (cx + 1.2f) to (top - 3f)))
            '̀' -> listOf(listOf((cx + 0.5f) to (top - 1.2f), (cx - 1.2f) to (top - 3f)))
            '̂' -> listOf(listOf((cx - 1.4f) to (top - 1.2f), cx to (top - 2.8f), (cx + 1.4f) to (top - 1.2f)))
            '̃' -> listOf(listOf((cx - 1.6f) to (top - 1.6f), (cx - 0.8f) to (top - 2.6f), cx to (top - 1.8f),
                (cx + 0.9f) to (top - 1.2f), (cx + 1.8f) to (top - 2.2f)))
            '̈' -> listOf(dot(cx - 1f, top - 1.8f), dot(cx + 1f, top - 1.8f))
            '̧' -> listOf(listOf(cx to 0f, (cx + 0.8f) to 0.8f, cx to 1.8f))
            else -> emptyList()
        }
    }

    /**
     * La inclinación de la letra del usuario (cuánto se corre a la derecha por unidad de subida), medida en
     * los tramos casi verticales de sus trazos escritos a mano. Sin datos, una inclinación leve.
     */
    fun slant(strokes: List<InkStroke>, patterns: Map<String, InkPattern>): Float {
        val samples = mutableListOf<Pair<Float, Float>>() // (dx/dy hacia arriba, largo)
        for (s in strokes) {
            if (s.brush == Brush.HIGHLIGHTER || s.calc != null) continue
            val pid = s.patternId
            if (pid != null && (pid.startsWith(PresetPatterns.PREFIX) || patterns[pid]?.id?.startsWith(PresetPatterns.PREFIX) == true)) continue
            var a = s.points.first()
            for (b in s.points.drop(1)) {
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = hypot(dx, dy)
                if (len < 4f) continue
                if (abs(dy) > 2f * abs(dx)) samples += (-dx / dy) to len
                a = b
            }
        }
        if (samples.isEmpty()) return 0.08f
        val sorted = samples.sortedBy { it.first }
        val half = sorted.sumOf { it.second.toDouble() } / 2.0
        var acc = 0.0
        for ((r, w) in sorted) {
            acc += w
            if (acc >= half) return r.coerceIn(-0.4f, 0.4f)
        }
        return sorted.last().first.coerceIn(-0.4f, 0.4f)
    }

    /** El ancho que ocupa [text] a altura de mayúscula [h]. */
    fun width(text: String, h: Float): Float = units(text).sumOf { advance(it, h / 10f, 1f).toDouble() }.toFloat()

    private fun advance(u: Unit, k: Float, s: Float) = if (u.g == null) 3.5f * k else (u.g.w * s * u.scale + 0.9f) * k

    /**
     * [text] escrito a mano con la línea de base en [baseline] desde [x]; mayúsculas de [h] de alto.
     * Devuelve los trazos y el x donde termina.
     */
    fun write(text: String, x: Float, baseline: Float, h: Float, style: InkStyle, slant: Float, seed: Int, ids: IdGenerator, group: String): Pair<List<InkStroke>, Float> {
        val k = h / 10f
        val rnd = Random(seed)
        val out = mutableListOf<InkStroke>()
        var cx = x
        val brush = if (style.brush == Brush.HIGHLIGHTER) Brush.PEN else style.brush
        // La línea de base se va moviendo de a poco, como a pulso, sin alejarse más de media unidad.
        var drift = 0f
        for (u in units(text)) {
            val s = 1f + (rnd.nextFloat() - 0.5f) * 0.06f
            drift = (drift + (rnd.nextFloat() - 0.5f) * 0.3f).coerceIn(-0.5f, 0.5f)
            val dy = (rnd.nextFloat() - 0.5f) * 0.3f + drift
            // Cada letra se inclina un poco distinto.
            val sl = slant + (rnd.nextFloat() - 0.5f) * 0.06f
            val g = u.g
            if (g != null) {
                for (raw in g.strokes + u.marks) {
                    if (raw.isEmpty()) continue
                    val pts = raw.map { (gx, gy) ->
                        val jx = (rnd.nextFloat() - 0.5f) * 0.24f
                        val jy = (rnd.nextFloat() - 0.5f) * 0.24f
                        val fx = (gx + jx) * s * u.scale
                        val fy = (gy + jy) * s * u.scale - u.raise + dy
                        (cx + (fx - fy * sl) * k) to (baseline + fy * k)
                    }
                    val smooth = chaikin(chaikin(pts))
                    val n = smooth.size
                    val points = smooth.mapIndexed { idx, (px, py) ->
                        val t = if (n > 1) idx / (n - 1f) else 0.5f
                        InkPoint(px, py, 0.85f + 0.15f * sin(PI.toFloat() * t), idx * 8L)
                    }
                    out += InkStroke(ids.next(), points, style.color, style.width, groupId = group, brush = brush)
                }
            }
            // El avance no lleva la escala al azar: así [width] mide lo mismo que ocupa la escritura.
            cx += advance(u, k, 1f)
        }
        return out to cx
    }

    /** Suaviza una polilínea dejando sus extremos donde están. */
    private fun chaikin(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (p.size < 3) return p
        val out = ArrayList<Pair<Float, Float>>(p.size * 2)
        out += p.first()
        for (i in 0 until p.size - 1) {
            val (ax, ay) = p[i]
            val (bx, by) = p[i + 1]
            if (i > 0) out += (0.75f * ax + 0.25f * bx) to (0.75f * ay + 0.25f * by)
            if (i < p.size - 2) out += (0.25f * ax + 0.75f * bx) to (0.25f * ay + 0.75f * by)
        }
        out += p.last()
        return out
    }
}
