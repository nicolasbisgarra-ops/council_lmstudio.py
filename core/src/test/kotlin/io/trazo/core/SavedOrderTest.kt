package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class SavedOrderTest {
    private data class Row(val id: String, val content: String = id, val selected: Boolean = false)
    private val original = listOf(Row("a"), Row("b", selected = true), Row("c"))

    @Test fun `sin archivo previo migra el orden actual`() {
        assertEquals(original, applySavedOrder(original, emptyList()) { it.id })
    }

    @Test fun `un orden completo respeta la posicion final y las instancias`() {
        val result = applySavedOrder(original, listOf("c", "a", "b")) { it.id }
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertSame(original[1], result[2])
        assertEquals(true, result[2].selected)
    }

    @Test fun `ignora ids borrados y repetidos sin duplicar filas`() {
        val result = applySavedOrder(original, listOf("b", "b", "borrado", "a", "a")) { it.id }
        assertEquals(listOf("b", "a", "c"), result.map { it.id })
    }

    @Test fun `los nuevos van al final en su orden actual`() {
        val current = listOf(Row("nuevo2"), original[0], Row("nuevo1"), original[1])
        assertEquals(listOf("b", "a", "nuevo2", "nuevo1"), applySavedOrder(current, listOf("b", "a")) { it.id }.map { it.id })
    }

    @Test fun `aplicar ids conserva el contenido actualizado durante el gesto`() {
        val updated = original.map { if (it.id == "b") it.copy(content = "sesión renovada") else it }
        val result = applySavedOrder(updated, listOf("c", "b", "a")) { it.id }
        assertSame(updated[1], result[1])
        assertEquals("sesión renovada", result[1].content)
    }

    @Test fun `guardar y releer ids mantiene el orden tras cambios en las fechas`() {
        val moved = moveItem(original, 2, 0)
        val savedIds = moved.map { it.id }
        val recent = original.reversed()
        assertEquals(moved, applySavedOrder(recent, savedIds) { it.id })
    }

    @Test fun `lista vacia con ids viejos queda vacia`() {
        assertEquals(emptyList<Row>(), applySavedOrder(emptyList<Row>(), listOf("a")) { it.id })
    }

    @Test fun `cien mil ids se aplican sin perder filas ni mutar la entrada`() {
        val input = List(100_000) { Row(it.toString()) }
        val ids = input.asReversed().map { it.id }
        val result = applySavedOrder(input, ids) { it.id }
        assertEquals(input.asReversed(), result)
        assertSame(input[0], result.last())
        assertEquals("0", input[0].id)
    }

    @Test fun `mover slots persiste el orden y la hoja abierta sin decodificar las demas`() {
        val notebook = Notebook("cuaderno", "Prueba", List(6) { Page((it + 1).toString()) }, lastPage = 4)
        val lazy = TrazoFormat.decodeLazy(TrazoFormat.encodeNotebook(notebook))
        val moved = lazy.copy(slots = moveItem(lazy.slots, 4, 1), lastPage = 1)
        val reopened = TrazoFormat.decodeLazy(TrazoFormat.encodeLazy(moved))
        assertEquals(listOf("1", "5", "2", "3", "4", "6"), reopened.slots.map { it.id })
        assertEquals("5", reopened.slots[reopened.openPage].id)
        assertFalse(moved.slots.any { it.isLoaded })
        assertFalse(reopened.slots.any { it.isLoaded })
        assertSame(lazy.slots[4], moved.slots[1])
    }
}
