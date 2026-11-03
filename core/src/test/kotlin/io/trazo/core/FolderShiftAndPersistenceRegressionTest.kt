package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs against the real NotebookOrder from Folders.kt under Gradle/core:test. */
class FolderShiftAndPersistenceRegressionTest {
    private val full = listOf("a", "x", "b", "y", "c")
    private val visible = listOf("a", "b", "c")

    private fun shift(source: List<String>, subset: List<String>, id: String, delta: Int): List<String> {
        val i = subset.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in subset.indices) return source
        val moved = subset.toMutableList().apply { add(j, removeAt(i)) }
        return NotebookOrder.withSubset(source, moved)
    }

    @Test fun firstDownDoesNotMoveHidden() = assertEquals(listOf("b", "x", "a", "y", "c"), shift(full, visible, "a", +1))
    @Test fun middleUpDoesNotMoveHidden() = assertEquals(listOf("b", "x", "a", "y", "c"), shift(full, visible, "b", -1))
    @Test fun middleDownDoesNotMoveHidden() = assertEquals(listOf("a", "x", "c", "y", "b"), shift(full, visible, "b", +1))
    @Test fun lastUpDoesNotMoveHidden() = assertEquals(listOf("a", "x", "c", "y", "b"), shift(full, visible, "c", -1))
    @Test fun boundariesAreNoop() {
        assertEquals(full, shift(full, visible, "a", -1))
        assertEquals(full, shift(full, visible, "c", +1))
    }
    @Test fun hiddenSlotsStayAtExactIndices() {
        for (id in visible) for (d in listOf(-1, 1)) {
            val result = shift(full, visible, id, d)
            assertEquals("x", result[1])
            assertEquals("y", result[3])
            assertEquals(full.toSet(), result.toSet())
        }
    }
    @Test fun everyNeighborSwapCanBeUndone() {
        for (i in 0..1) {
            val after = shift(full, visible, visible[i], 1)
            assertEquals(full, NotebookOrder.withSubset(after, visible))
        }
    }
}
