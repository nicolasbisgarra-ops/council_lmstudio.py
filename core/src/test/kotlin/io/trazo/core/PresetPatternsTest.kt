package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Biblioteca de partida: signos en tinta vectorial, patrones del usuario incluidos y cómo se instalan sin pisar nada. */
class PresetPatternsTest {
    private val signs = PresetPatterns.signs()
    private val bundled = PresetPatterns.bundled()
    private fun sign(key: String) = signs.single { it.id == PresetPatterns.PREFIX + key }

    @Test fun `every sign is pressure ink with its own section`() {
        assertTrue("${signs.size} signos", signs.size >= 85)
        assertEquals(signs.size, signs.map { it.id }.toSet().size)
        for (p in signs) {
            assertTrue(p.id, p.id.startsWith(PresetPatterns.PREFIX))
            assertTrue(p.id, p.builtIn)
            assertTrue(p.id, PatternCategory.of(p) in PatternCategory.ORDER && p.category != null)
            assertTrue(p.id, p.gestureExamples.all { it.size <= 4 })
            for (s in p.outputInk + p.gestureExamples.flatten()) {
                assertTrue(p.id, s.points.all { it.x.isFinite() && it.y.isFinite() && it.pressure in 0.3f..1f })
                assertEquals(p.id, s.points.indices.map { it * 8L }, s.points.map { it.timeMs })
            }
            // La salida es manuscrita: los trazos largos se afinan en las puntas.
            for (s in p.outputInk.filter { it.points.size > 4 }) {
                assertTrue(p.id, s.points.first().pressure < 0.4f && s.points.maxOf { it.pressure } > 0.9f)
            }
            p.stretch?.let { assertTrue(p.id, it.from in 0f..1f && it.to in 0f..1f && it.from < it.to) }
        }
    }

    @Test fun `numbers from 0 to 10 and the usual sections are there`() {
        assertEquals((0..10).map { "$it" }, signs.filter { it.category == PatternCategory.NUMBERS }.map { it.name })
        val sections = signs.groupBy { it.category }
        for (c in listOf(PatternCategory.OPERATORS, PatternCategory.GROUPING, PatternCategory.CALCULUS, PatternCategory.GREEK, PatternCategory.SETS, PatternCategory.ARROWS)) {
            assertTrue(c, (sections[c]?.size ?: 0) >= 6)
        }
        // Las flechas horizontales se estiran hasta donde terminó el gesto, como la barra de la raíz.
        assertEquals(Axis.HORIZONTAL, sign("flecha-derecha").stretch?.axis)
        assertEquals(Axis.VERTICAL, sign("flecha-abajo").stretch?.axis)
        // Los que se confunden con trazos comunes vienen desactivados: ⊥ es un «1» con la raya de una fracción debajo.
        for (key in listOf("menos", "por", "perpendicular")) assertFalse(key, sign(key).enabled)
    }

    @Test fun `the user's patterns come bundled with names and only examples that can be recognised`() {
        assertEquals(listOf("a", "b", "Igual", "x", "3", "Más", "2", "f( )", "y", "f(x)", "g("), bundled.map { it.name })
        assertTrue(bundled.none { it.builtIn || it.category != null || it.id.startsWith(PresetPatterns.PREFIX) })
        assertTrue(bundled.all { p -> p.gestureExamples.all { it.size <= 4 } })
        assertEquals(1, bundled.single { it.name == "f( )" }.gestureExamples.size)
        // La raíz trae, además de los ejemplos de fábrica, los dos que agregó el usuario.
        val root = PresetPatterns.all().single { it.id == DefaultPatterns.ROOT }
        val factory = DefaultPatterns.root()
        assertEquals(factory.gestureExamples, root.gestureExamples.take(factory.gestureExamples.size))
        assertEquals(factory.gestureExamples.size + 2, root.gestureExamples.size)
        assertEquals(factory.copy(gestureExamples = root.gestureExamples), root)
        assertEquals(DefaultPatterns.integral(), PresetPatterns.all().single { it.id == DefaultPatterns.INTEGRAL })
    }

    @Test fun `a new library gets the core first, then the user's patterns, then the signs`() {
        val lib = PresetPatterns.install(emptyList(), 0)
        assertEquals(PresetPatterns.all().map { it.id }, lib.map { it.id })
        assertEquals(DefaultPatterns.core().map { it.id }, lib.take(3).map { it.id })
        assertEquals(bundled.map { it.id }, lib.drop(3).take(bundled.size).map { it.id })
        // Ni el núcleo ni los patrones del usuario pierden nada al instalarse.
        assertEquals(PresetPatterns.all().take(3 + bundled.size), lib.take(3 + bundled.size))
        assertEquals(lib.size, lib.map { it.id }.toSet().size)
    }

    @Test fun `upgrading an existing library only adds the signs and keeps everything else as it was`() {
        val mine = bundled.first().copy(id = "mio", name = "Mío")
        val oldRoot = DefaultPatterns.root().copy(enabled = false)
        val existing = listOf(DefaultPatterns.integral(), mine, oldRoot)
        val lib = PresetPatterns.install(existing, 1)
        assertEquals(existing, lib.take(existing.size))
        assertEquals(signs.map { it.id }, lib.drop(existing.size).map { it.id })
        // La fracción que el usuario borró y sus patrones incluidos no vuelven en una actualización.
        assertTrue(lib.none { it.id == DefaultPatterns.FRACTION || it.id in bundled.map { b -> b.id } })
    }

    @Test fun `the user's unnamed patterns get their names on upgrade and nothing else changes`() {
        val igual = bundled.single { it.name == "Igual" }
        val onDevice = igual.copy(name = PatternUpgrades.UNNAMED, gestureExamples = igual.gestureExamples.take(2), enabled = false)
        assertEquals(onDevice.copy(name = "Igual"), PatternUpgrades.upgrade(listOf(onDevice)).single())
        // Uno al que el usuario ya le puso nombre, o uno propio que se llama «Patrón», quedan igual.
        val named = onDevice.copy(name = "Mi igual")
        assertSame(named, PatternUpgrades.upgrade(listOf(named)).single())
        val other = onDevice.copy(id = "otro")
        assertSame(other, PatternUpgrades.upgrade(listOf(other)).single())
        // Ya con su nombre, el signo «Igual» se instala desactivado en su tablet.
        val lib = PresetPatterns.install(PatternUpgrades.upgrade(listOf(onDevice.copy(enabled = true))), 1)
        assertFalse(lib.single { it.id == sign("igual").id }.enabled)
    }

    @Test fun `installing twice changes nothing and deleted signs stay deleted`() {
        val lib = PresetPatterns.install(emptyList(), 0)
        assertEquals(lib, PresetPatterns.install(lib, PresetPatterns.VERSION))
        val withoutPi = lib.filterNot { it.id == sign("pi").id }
        assertEquals(withoutPi, PresetPatterns.install(withoutPi, PresetPatterns.VERSION))
        assertEquals(PresetPatterns.VERSION, PresetPatterns.since(sign("pi").id))
        assertEquals(1, PresetPatterns.since(DefaultPatterns.ROOT))
        assertEquals(null, PresetPatterns.since("mio"))
    }

    @Test fun `an id the library already has is never replaced`() {
        val redrawn = sign("pi").copy(name = "Mi pi", enabled = false, gestureExamples = sign("pi").gestureExamples.take(1))
        val lib = PresetPatterns.install(listOf(redrawn), 1)
        assertSame(redrawn, lib.first())
        assertEquals(1, lib.count { it.id == redrawn.id })
    }

    @Test fun `a sign whose gesture the user already uses for something else comes disabled`() {
        val plus = sign("mas")
        val myPlus = plus.copy(id = "mi-mas", name = "Mi más", builtIn = false, category = null)
        val installed = PresetPatterns.install(listOf(myPlus), 1).single { it.id == plus.id }
        assertFalse(installed.enabled)
        assertEquals(plus.gestureExamples, installed.gestureExamples)
        // Un patrón desactivado no le quita nada a nadie.
        val off = PresetPatterns.install(listOf(myPlus.copy(enabled = false)), 1).single { it.id == plus.id }
        assertEquals(plus, off)
    }

    @Test fun `a sign named like one of the user's active patterns comes disabled`() {
        val pi = sign("pi")
        val myPi = InkPattern("mi-pi", " PI ", listOf(gesture(listOf(0f to 0f, 100f to 100f))), outputInk = pi.outputInk)
        assertFalse(PresetPatterns.install(listOf(myPi), 1).single { it.id == pi.id }.enabled)
        assertSame(myPi, PresetPatterns.sameNameAs(pi, listOf(myPi)))
        // Un patrón del usuario desactivado, u otro signo con el mismo nombre, no cuentan.
        assertEquals(pi, PresetPatterns.install(listOf(myPi.copy(enabled = false)), 1).single { it.id == pi.id })
        assertEquals(null, PresetPatterns.sameNameAs(pi, listOf(pi.copy(id = "preset-otro-pi"))))
        // Los signos que el usuario ya tenía con su letra («2», «3», «Igual», «Más») arrancan desactivados.
        val lib = PresetPatterns.install(emptyList(), 0)
        for (key in listOf("2", "3", "igual", "mas")) assertFalse(key, lib.single { it.id == PresetPatterns.PREFIX + key }.enabled)
    }

    @Test fun `only the colliding examples are dropped`() {
        val line = gesture(listOf(0f to 50f, 100f to 50f))
        val zig = gesture(listOf(0f to 0f, 50f to 100f, 100f to 0f))
        val circle = gesture((0..36).map { i -> 50f + 45f * kotlin.math.cos(i * 0.1745f) to 50f + 45f * kotlin.math.sin(i * 0.1745f) })
        val p = InkPattern("p", "P", listOf(line, zig, circle), outputInk = circle)
        val active = InkPattern("q", "Q", listOf(gesture(listOf(0f to 52f, 100f to 49f))), outputInk = line)
        val kept = PresetPatterns.withoutConflicts(p, listOf(active))
        assertEquals(listOf(zig, circle), kept.gestureExamples)
        assertTrue(kept.enabled)
        assertTrue(PresetPatterns.conflictScore(p, active) >= PresetPatterns.CONFLICT_SCORE)
        assertTrue(PresetPatterns.conflictScore(kept, active) < PresetPatterns.CONFLICT_SCORE)
        // Contra sí mismo (mismo id) no hay conflicto; un patrón ya desactivado no se toca.
        assertSame(p, PresetPatterns.withoutConflicts(p, listOf(p)))
        val disabled = p.copy(enabled = false)
        assertSame(disabled, PresetPatterns.withoutConflicts(disabled, listOf(active)))
    }

    @Test fun `restoring brings the core back first and adds the missing signs without touching the rest`() {
        val lib = PresetPatterns.install(emptyList(), 0)
        val mine = bundled.first()
        val customPi = sign("pi").copy(enabled = false)
        val broken = lib.filterNot { it.id == DefaultPatterns.ROOT || it.id == sign("sigma").id }
            .map { if (it.id == customPi.id) customPi else it }
            .map { if (it.id == DefaultPatterns.INTEGRAL) it.copy(name = "Mi integral", enabled = false) else it }
        val restored = PresetPatterns.restore(broken)
        assertEquals(PresetPatterns.all().take(3), restored.take(3))
        assertSame(customPi, restored.single { it.id == customPi.id })
        assertEquals(mine, restored.single { it.id == mine.id })
        assertNotNull(restored.singleOrNull { it.id == sign("sigma").id })
        assertEquals(restored.size, restored.map { it.id }.toSet().size)
        assertEquals(lib.map { it.id }.toSet(), restored.map { it.id }.toSet())
    }

    @Test fun `signs survive the json round trip`() {
        val lib = PresetPatterns.install(emptyList(), 0)
        assertEquals(lib, TrazoFormat.decodePatterns(TrazoFormat.encodePatterns(lib)))
    }

    private fun gesture(pts: List<Pair<Float, Float>>) =
        listOf(InkStroke("g", pts.mapIndexed { i, (x, y) -> InkPoint(x, y, 0.8f, i * 8L) }))
}
