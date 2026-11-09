package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorTextTest {
    @Test
    fun fractionsRootsAndPowers() {
        assertEquals(listOf("x = (-b ± √(b² - 4ac))/(2a)"), TutorText.plain("\$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\$"))
        assertEquals(listOf("3/4 · 2 ⇒ 3/2"), TutorText.plain("\$\\frac{3}{4}\\cdot 2 \\Rightarrow \\frac32\$"))
        assertEquals(listOf("e^(x+1)"), TutorText.plain("\$e^{x+1}\$"))
    }

    @Test
    fun alignedBlocksBecomeOneStepPerLine() {
        val tex = """
            ### Paso 1
            Restamos **3** en ambos lados:
            $$
            \begin{aligned}
            2x + 3 &= 7 \\[8pt]
            2x &= 4 \\
            x &= \boxed{2}
            \end{aligned}
            $$
        """.trimIndent()
        assertEquals(
            listOf("Paso 1", "Restamos 3 en ambos lados:", "", "2x + 3 = 7", "2x = 4", "x = 2"),
            TutorText.plain(tex),
        )
    }

    @Test
    fun textAndUnknownCommands() {
        assertEquals(listOf("si x ≥ 0, entonces 50%"), TutorText.plain("\$\\text{si } x \\geq 0\$, entonces 50\\%"))
        assertEquals(listOf("• uno", "• dos"), TutorText.plain("- uno\n* dos"))
        assertEquals(listOf("foo(x)"), TutorText.plain("\$\\foo(x)\$"))
    }

    @Test
    fun handwriteBelowGoesUnderTheLastInk() {
        val ink = InkStroke("a", listOf(InkPoint(100f, 100f, 0.5f, 0), InkPoint(160f, 140f, 0.5f, 1)))
        val out = MathNotes.handwriteBelow(listOf(ink), emptyList(), listOf("x = 2"))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.bounds.top > 140f })
        assertTrue(MathNotes.handwriteBelow(listOf(ink), emptyList(), listOf(" ")).isEmpty())
    }
}
