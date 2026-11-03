package io.trazo.app.data

import android.app.Application
import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.TrazoFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class NotebookPatternImportTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `equivalent archive pattern reuses local pattern and repeated imports do not duplicate`() {
        val repository = repository("equivalent")
        val local = pattern(
            id = "local-custom-pattern",
            name = "Local label",
            inkPrefix = "local-ink",
            offset = 0f,
            strokeTime = 100L,
            pointTime = 5L,
            enabled = false,
            unknown = "x",
        )
        repository.upsertPattern(local)
        val storedLocal = repository.patterns.value.single { it.id == local.id }
        val patternCount = repository.patterns.value.size

        val incoming = pattern(
            id = "archive-custom-pattern",
            name = "Archive label",
            inkPrefix = "archive-ink",
            offset = 0f,
            strokeTime = 900L,
            pointTime = 77L,
            enabled = true,
            unknown = "x",
        )
        assertNotEquals(local.id, incoming.id)
        assertNotEquals(local.gestureExamples.single().single().id, incoming.gestureExamples.single().single().id)
        assertNotEquals(local.gestureExamples.single().single().groupId, incoming.gestureExamples.single().single().groupId)
        assertNotEquals(local.gestureExamples.single().single().startTime, incoming.gestureExamples.single().single().startTime)
        assertNotEquals(local.gestureExamples.single().single().points.first().timeMs, incoming.gestureExamples.single().single().points.first().timeMs)
        assertNotEquals(local.outputInk.single().id, incoming.outputInk.single().id)
        assertNotEquals(local.outputInk.single().groupId, incoming.outputInk.single().groupId)
        assertNotEquals(local.outputInk.single().startTime, incoming.outputInk.single().startTime)
        assertNotEquals(local.outputInk.single().points.first().timeMs, incoming.outputInk.single().points.first().timeMs)
        assertNotEquals(local.updatedAt, incoming.updatedAt)
        assertEquals("x", local.unknown)
        assertEquals(local.unknown, incoming.unknown)
        val archive = notebook("archive-equal-book", incoming.id)
        val bytes = export(archive, listOf(incoming))

        val first = repository.importNotebook(ByteArrayInputStream(bytes))
        assertEquals("content-equivalent pattern must reuse the local entry", patternCount, repository.patterns.value.size)
        assertEquals(local.id, first.pages.single().strokes.single().patternId)
        assertEquals("note group metadata is preserved", "note-group", first.pages.single().strokes.single().groupId)
        assertEquals(storedLocal.name, repository.patterns.value.single { it.id == local.id }.name)
        assertFalse(repository.patterns.value.single { it.id == local.id }.enabled)

        val second = repository.importNotebook(ByteArrayInputStream(bytes))
        assertNotEquals("a duplicate notebook gets its own id", first.id, second.id)
        assertEquals(patternCount, repository.patterns.value.size)
        assertEquals(local.id, second.pages.single().strokes.single().patternId)
        assertEquals(local.id, repository.loadNotebook(second.id)?.pages?.single()?.strokes?.single()?.patternId)
    }

    @Test
    fun `same imported pattern id with different content gets a new id referenced by the note`() {
        val repository = repository("collision")
        val local = pattern(
            id = "colliding-pattern-id",
            name = "Local collision",
            inkPrefix = "old-local-ink",
            offset = 0f,
            strokeTime = 10L,
            pointTime = 1L,
        )
        repository.upsertPattern(local)
        val oldLocal = repository.patterns.value.single { it.id == local.id }
        val patternCount = repository.patterns.value.size

        val incoming = pattern(
            id = local.id,
            name = "Incoming collision",
            inkPrefix = "new-archive-ink",
            offset = 31f,
            strokeTime = 500L,
            pointTime = 50L,
        )
        val bytes = export(notebook("archive-collision-book", incoming.id), listOf(incoming))

        val imported = repository.importNotebook(ByteArrayInputStream(bytes))
        val notePatternId = imported.pages.single().strokes.single().patternId
        assertNotEquals("a different pattern must not overwrite the local ID", local.id, notePatternId)
        val importedPattern = repository.patterns.value.single { it.id == notePatternId }
        assertEquals("Incoming collision (importado)", importedPattern.name)
        assertEquals(patternCount + 1, repository.patterns.value.size)
        assertEquals(oldLocal, repository.patterns.value.single { it.id == local.id })
        assertEquals(notePatternId, repository.loadNotebook(imported.id)?.pages?.single()?.strokes?.single()?.patternId)

        val repeated = repository.importNotebook(ByteArrayInputStream(bytes))
        assertEquals(patternCount + 1, repository.patterns.value.size)
        assertEquals("reimport should point to the already imported equivalent", notePatternId, repeated.pages.single().strokes.single().patternId)
    }

    private fun repository(name: String): TrazoRepository =
        TrazoRepository(temporary.newFolder(name))

    private fun export(notebook: Notebook, patterns: List<InkPattern>): ByteArray =
        ByteArrayOutputStream().also { TrazoFormat.exportArchive(notebook, patterns, it) }.toByteArray()

    private fun notebook(id: String, referencedPatternId: String): Notebook {
        val noteStroke = InkStroke(
            id = "note-ink",
            points = listOf(InkPoint(700f, 800f, pressure = 0.5f, timeMs = 12L), InkPoint(710f, 810f, pressure = 0.9f, timeMs = 42L)),
            startTime = 2_000L,
            groupId = "note-group",
            patternId = referencedPatternId,
        )
        return Notebook(
            id = id,
            name = "Imported notebook",
            pages = listOf(Page("archive-page", strokes = listOf(noteStroke))),
            paper = Paper.PLAIN,
            createdAt = 1_000L,
            updatedAt = 1_100L,
        )
    }

    private fun pattern(
        id: String,
        name: String,
        inkPrefix: String,
        offset: Float,
        strokeTime: Long,
        pointTime: Long,
        enabled: Boolean = true,
        unknown: String? = null,
    ): InkPattern {
        fun stroke(label: String, x: Float, y: Float, dx: Float, dy: Float) = InkStroke(
            id = "$inkPrefix-$label",
            points = listOf(
                InkPoint(x + offset, y, pressure = 0.6f, timeMs = pointTime),
                InkPoint(x + dx + offset, y + dy, pressure = 0.9f, timeMs = pointTime + 14L),
            ),
            startTime = strokeTime,
            groupId = "$inkPrefix-group-$label",
        )

        val gesture = stroke("gesture", 4f, 8f, 12f, 18f)
        val output = stroke("output", 1f, 2f, 8f, 11f)
        return InkPattern(
            id = id,
            name = name,
            gestureExamples = listOf(listOf(gesture)),
            outputInk = listOf(output),
            enabled = enabled,
            updatedAt = strokeTime + pointTime,
            unknown = unknown,
        )
    }
}
