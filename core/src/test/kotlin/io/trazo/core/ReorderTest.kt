package io.trazo.core

import io.trazo.core.settings.ToolPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {
    @Test fun `mover cinco a la segunda posicion desplaza el resto`() {
        assertEquals(listOf(1, 5, 2, 3, 4, 6), moveItem((1..6).toList(), 4, 1))
    }

    @Test fun `mover hacia abajo usa el indice final sin restarle uno`() {
        assertEquals(listOf(1, 3, 4, 5, 2, 6), moveItem((1..6).toList(), 1, 4))
    }

    @Test fun `mover al principio conserva el orden de los anteriores`() {
        assertEquals(listOf(6, 1, 2, 3, 4, 5), moveItem((1..6).toList(), 5, 0))
    }

    @Test fun `mover al final conserva el orden de los siguientes`() {
        assertEquals(listOf(2, 3, 4, 5, 6, 1), moveItem((1..6).toList(), 0, 5))
    }

    @Test fun `intercambia vecinos en ambos sentidos`() {
        val input = listOf(1, 2, 3, 4)
        val expected = listOf(1, 3, 2, 4)
        assertEquals(expected, moveItem(input, 1, 2))
        assertEquals(expected, moveItem(input, 2, 1))
    }

    @Test fun `una lista vacia no tiene origen valido`() {
        for (from in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            for (to in listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)) {
                assertEquals(emptyList<Int>(), moveItem(emptyList<Int>(), from, to))
            }
        }
    }

    @Test fun `un unico elemento queda igual con cualquier destino`() {
        val input = listOf("unico")
        for (to in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals(input, moveItem(input, 0, to))
        }
    }

    @Test fun `origen y destino iguales no cambian la lista`() {
        val input = (1..6).toList()
        for (index in input.indices) assertEquals(input, moveItem(input, index, index))
    }

    @Test fun `origen negativo no cambia la lista aunque el destino sea valido`() {
        val input = listOf(1, 2, 3)
        assertEquals(input, moveItem(input, -1, 2))
        assertEquals(input, moveItem(input, Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test fun `origen pasado del final no cambia la lista`() {
        val input = listOf(1, 2, 3)
        assertEquals(input, moveItem(input, input.size, 0))
        assertEquals(input, moveItem(input, Int.MAX_VALUE, Int.MIN_VALUE))
    }

    @Test fun `destino negativo se acota al principio`() {
        val input = listOf(1, 2, 3)
        for (to in listOf(-1, Int.MIN_VALUE)) {
            assertEquals(listOf(3, 1, 2), moveItem(input, 2, to))
        }
    }

    @Test fun `destino pasado del final se acota al ultimo indice`() {
        val input = listOf(1, 2, 3)
        for (to in listOf(input.size, Int.MAX_VALUE)) {
            assertEquals(listOf(2, 3, 1), moveItem(input, 0, to))
        }
    }

    @Test fun `destino acotado que coincide con el origen no cambia nada`() {
        val input = listOf(1, 2, 3)
        assertEquals(input, moveItem(input, 0, Int.MIN_VALUE))
        assertEquals(input, moveItem(input, input.lastIndex, Int.MAX_VALUE))
    }

    @Test fun `duplicados se mueven por indice y no por valor`() {
        val input = listOf("a", "b", "a", "c")
        assertEquals(listOf("a", "a", "b", "c"), moveItem(input, 2, 1))
    }

    @Test fun `puede mover elementos nulos sin perder duplicados`() {
        val input = listOf(null, "a", null, "b")
        assertEquals(listOf(null, null, "a", "b"), moveItem(input, 2, 1))
        assertEquals(listOf("a", null, "b", null), moveItem(input, 0, 3))
    }

    @Test fun `no modifica la entrada mutable ni comparte su contenedor al mover`() {
        val input = mutableListOf(1, 2, 3, 4)
        val result = moveItem(input, 3, 1)
        assertEquals(listOf(1, 2, 3, 4), input)
        input.clear()
        assertEquals(listOf(1, 4, 2, 3), result)
    }

    @Test fun `acepta una sublista sin modificar la lista que la respalda`() {
        val backing = mutableListOf(0, 1, 2, 3, 4, 5)
        assertEquals(listOf(4, 1, 2, 3), moveItem(backing.subList(1, 5), 3, 0))
        assertEquals((0..5).toList(), backing)
    }

    @Test fun `conserva las instancias con sus claves contenido y seleccion`() {
        data class Fila(val clave: String, val contenido: String, val seleccionada: Boolean)
        val input = listOf(Fila("a", "texto", false), Fila("b", "texto", true), Fila("c", "otro", false))
        val result = moveItem(input, 1, 0)
        assertSame(input[1], result[0])
        assertSame(input[0], result[1])
        assertSame(input[2], result[2])
        assertTrue(result[0].seleccionada)
    }

    @Test fun `todas las combinaciones preservan la permutacion y el orden del resto`() {
        for (size in 6..10) {
            val input = (0 until size).toList()
            for (from in input.indices) {
                for (to in listOf(Int.MIN_VALUE) + (-1..size).toList() + Int.MAX_VALUE) {
                    val destination = to.coerceIn(0, input.lastIndex)
                    val result = moveItem(input, from, to)
                    val context = "size=$size from=$from to=$to"
                    assertEquals(context, input.size, result.size)
                    assertEquals(context, input, result.sorted())
                    assertEquals(context, input[from], result[destination])
                    assertEquals(context, input.filterIndexed { index, _ -> index != from },
                        result.filterIndexed { index, _ -> index != destination })
                    assertEquals(context, (0 until size).toList(), input)
                }
            }
        }
    }

    @Test fun `mover de vuelta al origen restaura el orden`() {
        val input = (0..7).toList()
        for (from in input.indices) {
            for (to in input.indices) {
                assertEquals(input, moveItem(moveItem(input, from, to), to, from))
            }
        }
    }

    @Test fun `mueve cien mil elementos sin perder cantidad ni orden`() {
        val input = (0 until 100_000).toList()
        val result = moveItem(input, input.lastIndex, 1)
        assertEquals(100_000, result.size)
        assertEquals(0, result.first())
        assertEquals(99_999, result[1])
        assertEquals(input.subList(1, input.lastIndex), result.drop(2))
        assertEquals(99_999, input.last())
    }

    @Test fun `contador muestra posiciones desde uno`() {
        assertEquals("1 / 6", positionLabel(0, 6))
        assertEquals("3 / 6", positionLabel(2, 6))
        assertEquals("6 / 6", positionLabel(5, 6))
        assertEquals("1 / 1", positionLabel(0, 1))
    }

    @Test fun `contador sin elementos muestra cero aun con indices extremos`() {
        for (index in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals("0 / 0", positionLabel(index, 0))
        }
    }

    @Test fun `contador acota indices negativos a la primera posicion`() {
        assertEquals("1 / 6", positionLabel(-1, 6))
        assertEquals("1 / 6", positionLabel(Int.MIN_VALUE, 6))
    }

    @Test fun `contador acota indices pasados del final a la ultima posicion`() {
        assertEquals("6 / 6", positionLabel(6, 6))
        assertEquals("6 / 6", positionLabel(Int.MAX_VALUE, 6))
    }

    @Test fun `contador con total negativo se trata como lista vacia`() {
        assertEquals("0 / 0", positionLabel(0, -1))
        assertEquals("0 / 0", positionLabel(Int.MAX_VALUE, Int.MIN_VALUE))
    }

    @Test fun `contador evita desbordes con el mayor total posible`() {
        assertEquals("2147483647 / 2147483647", positionLabel(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun `reordenar cualquier barra normalizada conserva pluma visibilidad y serializacion`() {
        val keys = ToolPreferences.defaultToolbar
        for (mask in 0 until (1 shl keys.size)) {
            val input = ToolPreferences.normalizeToolbar(keys.filterIndexed { index, _ -> mask and (1 shl index) != 0 })
            for (from in input.indices) {
                for (to in -1..input.size) {
                    val result = moveItem(input, from, to)
                    assertTrue("PEN" in result)
                    assertEquals(input.size, result.size)
                    assertEquals(input.toSet(), result.toSet())
                    assertEquals(result, ToolPreferences.normalizeToolbar(result))
                    assertEquals(result, ToolPreferences.readToolbar(ToolPreferences.serializeToolbar(result)))
                }
            }
        }
    }

    @Test fun `reordenar cualquier ruleta normalizada conserva limites y serializacion`() {
        val keys = ToolPreferences.defaultWheel
        for (mask in 0 until (1 shl keys.size)) {
            val input = ToolPreferences.normalizeWheel(keys.filterIndexed { index, _ -> mask and (1 shl index) != 0 })
            for (from in input.indices) {
                for (to in -1..input.size) {
                    val result = moveItem(input, from, to)
                    assertTrue(result.size in ToolPreferences.MIN_WHEEL_ITEMS..ToolPreferences.MAX_WHEEL_ITEMS)
                    assertEquals(input.size, result.size)
                    assertEquals(input.toSet(), result.toSet())
                    assertEquals(result, ToolPreferences.normalizeWheel(result))
                    assertEquals(result, ToolPreferences.readWheel(ToolPreferences.serializeWheel(result)))
                }
            }
        }
    }
}
