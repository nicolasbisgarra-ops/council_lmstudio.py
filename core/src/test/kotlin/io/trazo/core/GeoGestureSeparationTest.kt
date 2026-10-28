package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardián de colisiones reales, sin alterar umbrales ni patrones del usuario. */
class GeoGestureSeparationTest {
    private val targetSlugs = listOf("octagono", "trapecio", "arco")
    private val matcher = GestureMatcher()

    @Test fun `three formerly ambiguous shapes have usable two stroke hand gestures`() {
        val all = GeoPatterns.all()
        for (slug in targetSlugs) {
            val shape = all.single { it.id == GeoPatterns.PREFIX + slug }
            assertEquals(slug, 1, shape.gestureExamples.size)
            val gesture = shape.gestureExamples.single()
            assertEquals(slug, 2, gesture.size)
            assertTrue(slug, gesture.all { it.points.size >= 2 })
            val jitter = gesture.mapIndexed { i, stroke ->
                stroke.copy(id = "pen-$i", points = stroke.points.mapIndexed { k, pt ->
                    pt.copy(x = pt.x + ((k * 3) % 5 - 2) * 0.12f,
                        y = pt.y + ((k * 7) % 9 - 4) * 0.12f)
                })
            }
            val result = matcher.recognize(jitter, listOf(shape))
            assertTrue("El gesto humano de $slug no coincide: $result", result is MatchResult.Found)
        }
    }

    @Test fun `new library and v3 upgrade install all six safely below conflict threshold`() {
        val fresh = PresetPatterns.install(emptyList(), 0)
        val existingV3 = fresh.filterNot { it.id in GeoPatterns.V4_IDS }
        val fromV3 = PresetPatterns.install(existingV3, 3)
        val targets = GeoPatterns.all().filter { it.id in GeoPatterns.V4_IDS }
        for (target in targets) {
            val originalInFresh = fresh.single { it.id == target.id }
            val originalAfterUpgrade = fromV3.single { it.id == target.id }
            assertTrue("$target quedó desactivado en una biblioteca nueva", originalInFresh.enabled)
            assertTrue("$target quedó desactivado al migrar v3", originalAfterUpgrade.enabled)
            val competitors = fromV3.filter { it.enabled && it.id != target.id }
            val maxConflict = competitors.maxOfOrNull { PresetPatterns.conflictScore(target, it, matcher) } ?: 0f
            assertTrue("${target.id} conflicto máximo=$maxConflict (límite ${PresetPatterns.CONFLICT_SCORE})",
                maxConflict < PresetPatterns.CONFLICT_SCORE)
        }
    }

    @Test fun `old library v4 IDs are never reinstalled or silently overwritten`() {
        assertEquals(5, PresetPatterns.VERSION)
        for (slug in targetSlugs) assertEquals(4, PresetPatterns.since(GeoPatterns.PREFIX + slug))
        val old = PresetPatterns.install(emptyList(), 0)
        val deletedId = GeoPatterns.PREFIX + "octagono"
        val oldWithDeletion = old.filterNot { it.id == deletedId }
        assertEquals(oldWithDeletion, PresetPatterns.install(oldWithDeletion, 4))
        val oldWithManualChanges = old.map { p ->
            if (p.id == GeoPatterns.PREFIX + "arco") p.copy(enabled = false, name = "Arco propio") else p
        }
        assertEquals(oldWithManualChanges, PresetPatterns.install(oldWithManualChanges, 4))
    }
}
