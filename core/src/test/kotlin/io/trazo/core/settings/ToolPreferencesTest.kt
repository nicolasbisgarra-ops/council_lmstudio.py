package io.trazo.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPreferencesTest {
    @Test fun `preferencias viejas conservan todas las herramientas y acciones en su orden`() {
        assertEquals(listOf("PEN", "HIGHLIGHTER", "ERASER", "LASSO", "SHAPE", "PAN"), ToolPreferences.readToolbar(null))
        assertEquals(ToolPreferences.defaultToolbar + listOf("UNDO", "REDO"), ToolPreferences.readWheel(null))
    }

    @Test fun `listas vacias o corruptas vuelven a fabrica`() {
        for (raw in listOf("", " ", ",,,", "desconocido", "[PEN,ERASER]")) {
            assertEquals(ToolPreferences.defaultToolbar, ToolPreferences.readToolbar(raw))
            assertEquals(ToolPreferences.defaultWheel, ToolPreferences.readWheel(raw))
        }
        assertEquals(ToolPreferences.defaultToolbar, ToolPreferences.normalizeToolbar(emptyList()))
        assertEquals(ToolPreferences.defaultWheel, ToolPreferences.normalizeWheel(emptyList()))
    }

    @Test fun `barra repone la pluma obligatoria sin mostrar otras herramientas ocultas`() {
        assertEquals(listOf("PEN", "ERASER"), ToolPreferences.readToolbar("ERASER"))
        assertEquals(listOf("PAN", "PEN"), ToolPreferences.readToolbar("PAN,PEN"))
        assertEquals(listOf("PEN"), ToolPreferences.readToolbar("PEN"))
    }

    @Test fun `descarta claves desconocidas y duplicados conservando la primera posicion`() {
        assertEquals(listOf("LASSO", "PEN", "ERASER"), ToolPreferences.readToolbar(" LASSO ,PEN,LASSO,NO_EXISTE,ERASER,PEN"))
        assertEquals(listOf("UNDO", "PAN", "REDO"), ToolPreferences.readWheel("UNDO,NO_EXISTE,PAN,UNDO,REDO"))
    }

    @Test fun `ruleta acepta tres y no exige pluma`() {
        assertEquals(listOf("UNDO", "ERASER", "REDO"), ToolPreferences.readWheel("UNDO,ERASER,REDO"))
        assertEquals(ToolPreferences.defaultWheel, ToolPreferences.readWheel("UNDO,REDO"))
        assertEquals(ToolPreferences.defaultWheel, ToolPreferences.readWheel("UNDO,UNDO,REDO,DESCONOCIDO"))
    }

    @Test fun `ruleta queda entre minimo y maximo aun con entradas repetidas o sobrantes`() {
        val raw = (ToolPreferences.defaultWheel.reversed() + List(12) { "DESCONOCIDO_$it" } + ToolPreferences.defaultWheel)
        val normalized = ToolPreferences.normalizeWheel(raw)
        assertEquals(ToolPreferences.defaultWheel.reversed(), normalized)
        assertEquals(3, ToolPreferences.MIN_WHEEL_ITEMS)
        assertEquals(10, ToolPreferences.MAX_WHEEL_ITEMS)
        assertTrue(normalized.size in ToolPreferences.MIN_WHEEL_ITEMS..ToolPreferences.MAX_WHEEL_ITEMS)
    }

    @Test fun `serializacion conserva orden y visibilidad entre sesiones`() {
        val toolbar = listOf("PAN", "PEN", "SHAPE")
        val wheel = listOf("REDO", "ERASER", "UNDO", "PEN")
        assertEquals("PAN,PEN,SHAPE", ToolPreferences.serializeToolbar(toolbar))
        assertEquals("REDO,ERASER,UNDO,PEN", ToolPreferences.serializeWheel(wheel))
        assertEquals(toolbar, ToolPreferences.readToolbar(ToolPreferences.serializeToolbar(toolbar)))
        assertEquals(wheel, ToolPreferences.readWheel(ToolPreferences.serializeWheel(wheel)))
    }

    @Test fun `tambien normaliza al escribir para no persistir listas invalidas`() {
        assertEquals("PEN,ERASER", ToolPreferences.serializeToolbar(listOf("ERASER", "ERASER", "UNDO")))
        assertEquals(ToolPreferences.defaultWheel.joinToString(","), ToolPreferences.serializeWheel(listOf("UNDO")))
    }

    @Test fun `normalizar es idempotente en cualquier subconjunto y orden`() {
        // Recorre todas las selecciones posibles; invierte el orden para cubrir configuraciones propias.
        val keys = ToolPreferences.defaultWheel
        for (mask in 0 until (1 shl keys.size)) {
            val input = keys.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.reversed()
            val toolbar = ToolPreferences.normalizeToolbar(input)
            val wheel = ToolPreferences.normalizeWheel(input)
            assertEquals(toolbar, ToolPreferences.normalizeToolbar(toolbar))
            assertEquals(wheel, ToolPreferences.normalizeWheel(wheel))
            assertTrue("PEN" in toolbar)
            assertEquals(toolbar.distinct(), toolbar)
            assertEquals(wheel.distinct(), wheel)
            assertTrue(wheel.size in ToolPreferences.MIN_WHEEL_ITEMS..ToolPreferences.MAX_WHEEL_ITEMS)
        }
    }
}
