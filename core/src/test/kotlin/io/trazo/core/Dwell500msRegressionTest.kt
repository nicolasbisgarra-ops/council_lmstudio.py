package io.trazo.core

import io.trazo.core.fixtures.HandScript
import io.trazo.core.fixtures.SyntheticPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresiones específicas para una pausa global de 500 ms con lápiz sintético.
 *
 * No sustituyen los ensayos físicos con S Pen ni prueban la persistencia de
 * TrazoSettings (que pertenece al módulo Android).
 *
 * Este archivo es independiente de app/ui y scripts/ci-emulator.sh para
 * poder integrarlo sin chocar con el pulido visual de Claude.
 */
class Dwell500msRegressionTest {
    private fun setup(
        patterns: List<InkPattern> = DefaultPatterns.all(),
    ): Pair<InkController, SyntheticPen> {
        val controller = InkController(
            patterns = { patterns },
            ids = IdGenerator.sequential(),
        )
        controller.holdMillis = 500L
        return controller to SyntheticPen(controller)
    }

    private fun integral() = HandScript.integralGesture(100f, 100f, 150f)

    @Test fun `499 ms of stillness does not convert but 500 ms can`() {
        val (controller, pen) = setup()
        pen.begin(integral(), stillMs = 450)
        pen.advance(40) // 490 ms quieto
        assertTrue(pen.events.none { it is InkEvent.Converted })
        pen.advance(20) // 510 ms quieto
        assertEquals(1, pen.events.filterIsInstance<InkEvent.Converted>().size)
        assertTrue(controller.page.strokes.all { it.patternId == DefaultPatterns.INTEGRAL })
    }

    @Test fun `lifting at 490 ms preserves handwriting and later ticks do nothing`() {
        val (controller, pen) = setup()
        pen.stroke(integral(), holdMs = 490)
        assertTrue(pen.events.none { it is InkEvent.Converted })
        assertEquals(1, controller.page.strokes.size)
        assertNull(controller.page.strokes.single().patternId)
        pen.advance(650)
        assertTrue("No se debe convertir después de UP", pen.events.none { it is InkEvent.Converted })
        assertEquals(1, controller.page.strokes.size)
    }

    @Test fun `holding the integral at 550 ms converts exactly once and can undo`() {
        val (controller, pen) = setup()
        pen.stroke(integral(), holdMs = 550)
        val conversions = pen.events.filterIsInstance<InkEvent.Converted>()
        assertEquals(1, conversions.size)
        assertEquals(DefaultPatterns.INTEGRAL, conversions.single().pattern.id)
        assertTrue(controller.page.strokes.isNotEmpty())
        assertTrue(controller.page.strokes.all { it.patternId == DefaultPatterns.INTEGRAL })
        assertTrue(controller.undo())
        assertEquals(1, controller.page.strokes.size)
        assertNull(controller.page.strokes.single().patternId)
    }

    @Test fun `small pen tremor does not delay 500 ms conversion`() {
        val (_, pen) = setup()
        pen.stroke(integral(), holdMs = 550, tremor = 3f)
        assertEquals(1, pen.events.filterIsInstance<InkEvent.Converted>().size)
    }

    @Test fun `unknown drawing is not replaced after 500 ms`() {
        val (controller, pen) = setup()
        val zigzag = (0..12).map { i ->
            (100f + 20f * i) to if (i % 2 == 0) 100f else 160f
        }
        pen.stroke(zigzag, holdMs = 600)
        assertTrue(pen.events.none { it is InkEvent.Converted })
        assertTrue(pen.events.any { it is InkEvent.NotRecognized })
        assertEquals(1, controller.page.strokes.size)
        assertNull(controller.page.strokes.single().patternId)
    }

    @Test fun `a blank long press at 500 ms does not leave ink`() {
        val (controller, pen) = setup()
        pen.pressAndHold(300f, 300f, 600)
        assertTrue(controller.page.strokes.isEmpty())
        assertFalse(pen.events.any { it is InkEvent.Converted })
    }

    @Test fun `a pattern with 800 ms own hold still overrides global 500 ms`() {
        val custom = DefaultPatterns.integral().copy(holdMillis = 800L)
        val (_, pen) = setup(listOf(custom))
        pen.begin(integral(), stillMs = 650)
        assertTrue(pen.events.none { it is InkEvent.Converted })
        pen.advance(180) // 830 ms quieto
        val converted = pen.events.filterIsInstance<InkEvent.Converted>()
        assertEquals(1, converted.size)
        assertEquals(DefaultPatterns.INTEGRAL, converted.single().pattern.id)
    }
}
