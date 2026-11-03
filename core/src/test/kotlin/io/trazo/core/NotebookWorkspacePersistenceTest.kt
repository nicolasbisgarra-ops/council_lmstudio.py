package io.trazo.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La tinta fuera del A4 sigue en coordenadas de documento al guardar un área de trabajo 3 × 3. */
class NotebookWorkspacePersistenceTest {
    @Test
    fun `la tinta negativa y positiva del espacio 3x3 sobrevive JSON lazy y archivo ZIP`() {
        val controller = InkController(ids = IdGenerator.sequential("workspace")).apply {
            recognitionEnabled = false
        }
        controller.penDown(-1000f, -1414f, 0.7f, 10L, PointerKind.STYLUS)
        controller.penMove(500f, 707f, 0.6f, 20L)
        controller.penUp(2000f, 2828f, 0.8f, 30L)

        val original = controller.page.strokes.single()
        assertEquals(-1000f, original.points.first().x, 0f)
        assertEquals(-1414f, original.points.first().y, 0f)
        assertEquals(2000f, original.points.last().x, 0f)
        assertEquals(2828f, original.points.last().y, 0f)

        val notebook = Notebook("workspace", "Espacio 3 × 3", listOf(Page("page", listOf(original))))

        val jsonRoundTrip = TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(notebook))
        assertEquals(original, jsonRoundTrip.pages.single().strokes.single())

        val lazyRoundTrip = TrazoFormat.decodeLazy(
            TrazoFormat.encodeLazy(LazyNotebook.of(notebook)),
        )
        assertFalse(lazyRoundTrip.slots.single().isLoaded)
        val lazySavedAgain = TrazoFormat.decodeLazy(TrazoFormat.encodeLazy(lazyRoundTrip))
        assertFalse(lazySavedAgain.slots.single().isLoaded)
        assertEquals(original, lazySavedAgain.page(0).strokes.single())

        val archive = ByteArrayOutputStream().use { output ->
            TrazoFormat.exportArchive(notebook, emptyList(), output)
            TrazoFormat.importArchive(ByteArrayInputStream(output.toByteArray()))
        }
        assertEquals(original, archive.notebook.pages.single().strokes.single())

        val roundTrips = listOf(
            jsonRoundTrip.pages.single().strokes.single(),
            lazySavedAgain.page(0).strokes.single(),
            archive.notebook.pages.single().strokes.single(),
        )
        roundTrips.forEach { stroke ->
            assertEquals(-1000f, stroke.points.first().x, 0f)
            assertEquals(-1414f, stroke.points.first().y, 0f)
            assertEquals(2000f, stroke.points.last().x, 0f)
            assertEquals(2828f, stroke.points.last().y, 0f)
        }
        assertTrue(roundTrips.all { it == original })
    }
}
