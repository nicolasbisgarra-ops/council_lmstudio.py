package io.trazo.core.math.tex

import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.PresetPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TexMathTest {
    private fun sym(k: String, kind: TexNode.Kind = TexNode.Kind.ORD) = TexNode.Sym(k, kind)

    @Test fun `separa formulas de la prosa`() {
        val parts = TexMath.split("Paso 1: $$\\frac{1}{2}$$ y luego \\(x\\) vale $3$.")
        assertEquals(
            listOf(
                TexPart.Prose("Paso 1: "), TexPart.Formula("\\frac{1}{2}", true), TexPart.Prose(" y luego "),
                TexPart.Formula("x", false), TexPart.Prose(" vale "), TexPart.Formula("3", false), TexPart.Prose("."),
            ),
            parts,
        )
    }

    @Test fun `un signo pesos suelto no abre formula`() {
        assertEquals(listOf(TexPart.Prose("Cuesta $ 5 y $ 6")), TexMath.split("Cuesta $ 5 y $ 6"))
        assertEquals(listOf(TexPart.Prose("sin cerrar \\[ x")), TexMath.split("sin cerrar \\[ x"))
    }

    @Test fun `un bloque display sin cerrar llega hasta el final o el renglon en blanco`() {
        val reply = "Resultado:\n\n$$\n\\begin{aligned}\nA &= \\boxed{e^2+1} \\\\[8pt]\n&\\approx 8{,}39\n\\end{aligned}"
        val parts = TexMath.split(reply)
        assertEquals(TexPart.Prose("Resultado:\n\n"), parts.first())
        val f = parts.last() as TexPart.Formula
        assertTrue(f.display)
        assertTrue(f.source.startsWith("\\begin{aligned}") && f.source.endsWith("\\end{aligned}"))
        val mid = TexMath.split("$$ x^2\n\nY sigue la prosa.")
        assertEquals(listOf(TexPart.Formula("x^2", true), TexPart.Prose("\n\nY sigue la prosa.")), mid)
    }

    @Test fun `lee fracciones raices y exponentes`() {
        val n = TexMath.parse("\\frac{x^2}{\\sqrt[3]{y_1}}") as TexNode.Row
        val f = n.items.single() as TexNode.Frac
        assertEquals(TexNode.Scripts(sym("x"), sym("2"), null), f.num)
        val r = f.den as TexNode.Root
        assertEquals(TexNode.Row(listOf(sym("3"))), r.index)
        assertEquals(TexNode.Scripts(sym("y"), null, sym("1")), r.body)
    }

    @Test fun `lee signos con su clase`() {
        val n = TexMath.parse("-a + b \\leq \\pi") as TexNode.Row
        assertEquals(
            listOf(sym("Menos", TexNode.Kind.BIN), sym("a"), sym("Más", TexNode.Kind.BIN), sym("b"), sym("Menor o igual", TexNode.Kind.REL), sym("pi")),
            n.items,
        )
    }

    @Test fun `lee matrices y parentesis que crecen`() {
        val m = (TexMath.parse("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}") as TexNode.Row).items.single() as TexNode.Matrix
        assertEquals(listOf(listOf(sym("1"), sym("2")), listOf(sym("3"), sym("4"))), m.rows)
        assertEquals("Abre paréntesis", m.open)
        val f = (TexMath.parse("\\left[ \\frac{1}{2} \\right)") as TexNode.Row).items.single() as TexNode.Fenced
        assertEquals("Abre corchete", f.open)
        assertEquals("Cierra paréntesis", f.close)
        val c = (TexMath.parse("\\begin{cases} x & x>0 \\\\ 0 & \\text{si no} \\end{cases}") as TexNode.Row).items.single() as TexNode.Matrix
        assertEquals(2, c.rows.size)
        assertEquals(TexNode.Word("si no", prose = true), c.rows[1][1])
    }

    @Test fun `lo desconocido o roto no falla`() {
        for (s in listOf("\\foo{1}", "}", "\\frac{1}", "x^", "\\left(", "\\end{x}", "\\sqrt[", "$", "\\", "{{{", "a_{b^{c}")) {
            TexMath.parse(s)
        }
        assertEquals(TexNode.Word("foo"), (TexMath.parse("\\foo") as TexNode.Row).items.single())
    }

    @Test fun `limites e integrales`() {
        val s = (TexMath.parse("\\lim_{x \\to 0} \\int_0^1 f'(x)\\,dx") as TexNode.Row).items
        assertEquals(TexNode.BigOp("", below = true, word = "lím"), (s[0] as TexNode.Scripts).base)
        assertEquals(TexNode.BigOp("Integral", below = false), (s[1] as TexNode.Scripts).base)
        assertEquals(TexNode.Scripts(sym("f"), TexNode.Row(listOf(sym("′"))), null), s[2])
    }

    // ---- Armado con tinta ------------------------------------------------------------------------------

    private val ink = TexInk(PresetPatterns.signs())

    @Test fun `arma con los patrones de la biblioteca`() {
        val b = ink.layout("2 + 3", 40f)
        assertTrue(b.texts.isEmpty())
        assertTrue(b.strokes.isNotEmpty())
        assertTrue(b.strokes.all { it.patternId!!.startsWith(PresetPatterns.PREFIX) })
        // Las cifras van de la línea hacia arriba, del alto pedido.
        assertEquals(40f, b.ascent, 6f)
        assertTrue(b.width > 80f)
    }

    @Test fun `la fraccion apila numerador sobre denominador`() {
        val one = ink.layout("2", 40f)
        val f = ink.layout("\\frac{1}{2}", 40f)
        assertTrue(f.ascent > one.ascent)
        assertTrue(f.descent > 12f)
        val bar = f.strokes.filter { it.patternId == null }.single()
        assertEquals(-20f, bar.points.first().y, 0.01f)
    }

    @Test fun `el exponente sube y es mas chico`() {
        val b = ink.layout("x^2", 40f)
        val two = b.strokes.filter { it.patternId == PresetPatterns.PREFIX + "2" }
        val x = b.strokes.filter { it.patternId != PresetPatterns.PREFIX + "2" }
        val tb = two.map { it.bounds }.reduce { a, c -> a.union(c) }
        val xb = x.map { it.bounds }.reduce { a, c -> a.union(c) }
        assertTrue(tb.bottom < xb.bottom)
        assertTrue(tb.height < xb.height * 1.1f)
        assertTrue(tb.left >= xb.right - 1f)
    }

    @Test fun `los parentesis crecen con lo de adentro`() {
        val small = ink.layout("\\left( 2 \\right)", 40f)
        val big = ink.layout("\\left( \\frac{1}{\\frac{2}{3}} \\right)", 40f)
        assertTrue(big.height > small.height * 1.5f)
    }

    @Test fun `una letra del usuario reemplaza a la de la biblioteca`() {
        val mine = InkPattern("user-x", "x", listOf(listOf(InkStroke("g", listOf(InkPoint(0f, 0f), InkPoint(10f, 10f))))),
            listOf(InkStroke("o", listOf(InkPoint(0f, 0f), InkPoint(30f, 30f)))))
        val b = TexInk(PresetPatterns.signs() + mine).layout("x", 40f)
        assertEquals(listOf("user-x"), b.strokes.map { it.patternId }.distinct())
        assertEquals(0f, b.descent, 0.5f)
    }

    @Test fun `lo que no tiene patron queda como texto`() {
        val b = ink.layout("\\text{si } x \\ne 0", 40f)
        assertEquals("si ", b.texts.first().text)
        assertTrue(b.strokes.isNotEmpty())
        val all = listOf("\\begin{bmatrix}1&2\\\\3&4\\end{bmatrix}", "\\sum_{i=1}^n i^2", "\\sqrt{x+1}", "\\vec{v}", "\\lim_{x\\to 0}", "\\int_0^1 x\\,dx", "a \\cdot b \\ldots")
        for (s in all) {
            val box = ink.layout(s, 30f)
            assertTrue(s, box.width > 0f && box.height > 0f)
            assertTrue(s, box.texts.isEmpty())
        }
    }

    // ---- Resoluciones apiladas ----------------------------------------------------------------------------

    @Test fun `aligned apila renglones de a pares`() {
        val src = "\\begin{aligned} I &= \\int_0^1 x^2\\,dx \\\\[8pt] &= \\left[ \\frac{x^3}{3} \\right]_0^1 \\\\[6pt] &= \\boxed{\\frac{1}{3}} \\end{aligned}"
        val m = (TexMath.parse(src) as TexNode.Row).items.single() as TexNode.Matrix
        assertEquals(TexNode.Align.PAIRS, m.align)
        assertEquals(3, m.rows.size)
        assertEquals(TexNode.Row(emptyList()), m.rows[1][0])
        val second = m.rows[1][1] as TexNode.Row
        assertEquals(sym("Igual", TexNode.Kind.REL), second.items[0])
        val sc = second.items[1] as TexNode.Scripts
        assertTrue(sc.base is TexNode.Fenced)
        assertEquals(sym("1"), sc.sup)
        assertEquals(sym("0"), sc.sub)
        assertTrue((m.rows[2][1] as TexNode.Row).items[1] is TexNode.Boxed)
    }

    @Test fun `renglones sueltos sin entorno tambien se apilan`() {
        val m = TexMath.parse("x &= 2 + 3 \\\\ &= 5") as TexNode.Matrix
        assertEquals(TexNode.Align.PAIRS, m.align)
        assertEquals(2, m.rows.size)
        assertEquals(TexNode.Word("sen"), (TexMath.parse("\\operatorname{sen} x") as TexNode.Row).items[0])
        assertEquals(TexNode.Row(listOf(sym("x"))), TexMath.parse("\\displaystyle x").let { r -> TexNode.Row((r as TexNode.Row).items.filter { it !is TexNode.Space }) })
    }

    private val typeset = TexInk(emptyList(), typeset = TexMeasure { text, _, size -> text.length * size * 0.5f })

    @Test fun `en letra de imprenta las variables van en cursiva`() {
        val b = typeset.layout("2x + \\operatorname{sen} \\alpha", 28f)
        assertTrue(b.strokes.isEmpty())
        val fonts = b.texts.associate { it.text to it.font }
        assertEquals(TexFont.UPRIGHT, fonts["2"])
        assertEquals(TexFont.ITALIC, fonts["x"])
        assertEquals(TexFont.UPRIGHT, fonts["+"])
        assertEquals(TexFont.UPRIGHT, fonts["sen"])
        assertEquals(TexFont.ITALIC, fonts["α"])
        // El cuerpo de la letra es más grande que el alto de la cifra.
        assertEquals(40f, b.texts.first().size, 0.01f)
    }

    @Test fun `en letra de imprenta el aligned alinea los iguales`() {
        val b = typeset.layout("\\begin{aligned} f(x) &= 3x^2 \\\\ &= 12 \\end{aligned}", 20f)
        val equals = b.texts.filter { it.text == "=" }
        assertEquals(2, equals.size)
        assertEquals(equals[0].x, equals[1].x, 0.01f)
        assertTrue(equals[1].baseline > equals[0].baseline + 20f)
    }

    @Test fun `en letra de imprenta lo alto se dibuja`() {
        val boxed = typeset.layout("\\boxed{5}", 20f)
        assertEquals(1, boxed.strokes.size)
        assertTrue(boxed.width > typeset.layout("5", 20f).width)
        val integral = typeset.layout("\\displaystyle \\int_0^{\\pi/2} \\sin x \\, dx", 20f)
        assertTrue(integral.strokes.isNotEmpty())
        assertTrue(integral.height > 40f)
        val small = typeset.layout("\\left( x \\right)", 20f)
        assertTrue(small.strokes.isEmpty())
        val tall = typeset.layout("\\left( \\frac{1}{2} \\right)", 20f)
        assertEquals(2, tall.strokes.count { it.points.size > 5 })
        for (s in listOf("\\sum_{k=1}^n k", "\\prod_i a_i", "\\left\\{ \\frac{a}{b} \\right.", "\\left| \\frac{a}{b} \\right|", "\\iint f", "\\oint f", "\\sqrt{2}")) {
            val box = typeset.layout(s, 20f)
            assertTrue(s, box.width > 0f && box.height > 0f)
        }
    }

    @Test fun `una respuesta tipica del tutor se lee entera`() {
        val reply = """
### 1\. Planteo

$$
\begin{aligned}
\int_0^{\pi/2} x\,\operatorname{sen} x\,dx &= \left[ -x\cos x \right]_0^{\pi/2} + \int_0^{\pi/2} \cos x\,dx \\[8pt]
&= 0 + \left[ \operatorname{sen} x \right]_0^{\pi/2} \\[8pt]
&= \boxed{1}
\end{aligned}
$$

* Observación: \( u = x \Rightarrow du = dx \)
"""
        val formulas = TexMath.split(reply).filterIsInstance<TexPart.Formula>()
        assertEquals(listOf(true, false), formulas.map { it.display })
        val m = (TexMath.parse(formulas[0].source) as TexNode.Row).items.single() as TexNode.Matrix
        assertEquals(3, m.rows.size)
        assertTrue(m.rows.all { it.size == 2 })
        fun words(n: TexNode): List<String> = when (n) {
            is TexNode.Word -> listOf(n.text)
            is TexNode.Row -> n.items.flatMap(::words)
            is TexNode.Matrix -> n.rows.flatten().flatMap(::words)
            is TexNode.Scripts -> listOfNotNull(n.base, n.sup, n.sub).flatMap(::words)
            is TexNode.Fenced -> words(n.body)
            is TexNode.Frac -> words(n.num) + words(n.den)
            is TexNode.Boxed -> words(n.body)
            else -> emptyList()
        }
        assertEquals(setOf("sen", "cos"), words(m).toSet())
        val b = typeset.layout(formulas[0].source, 20f)
        assertTrue(b.height > 100f)
    }
}
