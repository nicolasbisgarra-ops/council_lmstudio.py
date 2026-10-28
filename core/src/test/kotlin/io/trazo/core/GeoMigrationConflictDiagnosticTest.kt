package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pasar de la biblioteca v3 a la v4: las seis formas nuevas llegan como en una biblioteca nueva y nunca le ganan a un patrón del usuario. */
class GeoMigrationConflictDiagnosticTest {
    private val v4 = GeoPatterns.all().filter { it.id in GeoPatterns.V4_IDS }

    /**
     * Las que el comparador todavía confunde con una forma o patrón activo (con CONFLICT_SCORE 0.84):
     * octágono ≈ círculo (0.91), trapecio ≈ rectángulo horizontal (0.85), arco ≈ raíz (0.84).
     * Llegan desactivadas pero enteras; si el comparador aprende a separarlas, sacarlas de acá.
     */
    private val ambiguous = setOf("octagono", "trapecio", "arco").mapTo(HashSet()) { GeoPatterns.PREFIX + it }

    @Test fun `a v3 library gets the six new shapes exactly as a new library does`() {
        assertEquals(6, v4.size)
        val fresh = PresetPatterns.install(emptyList(), 0)
        val installedV3 = fresh.filterNot { it.id in GeoPatterns.V4_IDS }
        val lib = PresetPatterns.install(installedV3, 3)
        assertEquals(installedV3, lib.take(installedV3.size))
        for (shape in v4) {
            val got = lib.single { it.id == shape.id }
            assertEquals(shape.id, fresh.single { it.id == shape.id }, got)
            assertEquals(shape.id, shape.id !in ambiguous, got.enabled)
            // Ninguna pierde ejemplos: o entra entera o entra desactivada con los suyos.
            assertEquals(shape.id, shape.gestureExamples, got.gestureExamples)
            val clashes = installedV3.any { it.enabled && PresetPatterns.conflictScore(shape, it) >= PresetPatterns.CONFLICT_SCORE }
            assertEquals(shape.id, shape.id in ambiguous, clashes)
        }
    }

    @Test fun `an active user pattern wins over a new shape that clashes with it`() {
        for (shape in v4) {
            val sameName = InkPattern("mio-${shape.id}", shape.name.uppercase(), listOf(gesture(listOf(0f to 0f, 100f to 100f))), outputInk = shape.outputInk)
            assertFalse(shape.id, PresetPatterns.install(listOf(sameName), 3).single { it.id == shape.id }.enabled)

            val sameGesture = shape.copy(id = "mi-${shape.id}", name = "Mío", builtIn = false, category = null)
            val installed = PresetPatterns.install(listOf(sameGesture), 3).single { it.id == shape.id }
            assertFalse(shape.id, installed.enabled && installed.gestureExamples.isNotEmpty() &&
                PresetPatterns.conflictScore(installed, sameGesture) >= PresetPatterns.CONFLICT_SCORE)
            // El patrón del usuario queda primero y sin tocar.
            assertEquals(sameGesture, PresetPatterns.install(listOf(sameGesture), 3).first())
        }
    }

    private fun gesture(pts: List<Pair<Float, Float>>) =
        listOf(InkStroke("g", pts.mapIndexed { i, (x, y) -> InkPoint(x, y, 0.8f, i * 8L) }))
}
