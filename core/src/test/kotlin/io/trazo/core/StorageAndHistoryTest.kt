package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HistoryTest {
    private fun st(id: String, x: Float = 0f) = InkStroke(id, listOf(InkPoint(x, 0f), InkPoint(x + 1f, 1f)))

    @Test fun `revert restores exact z-order`() {
        val page = PageState(listOf(st("a"), st("b"), st("c"), st("d")))
        val edit = StrokeEdit(listOf(st("b"), st("d")), listOf(st("x")), StrokeEdit.Kind.CONVERT)
        val pos = page.apply(edit)
        assertEquals(listOf("a", "c", "x"), page.strokes.map { it.id })
        page.revert(edit, pos)
        assertEquals(listOf("a", "b", "c", "d"), page.strokes.map { it.id })
    }

    @Test fun `same id replaces in place`() {
        val page = PageState(listOf(st("a"), st("b"), st("c")))
        page.apply(StrokeEdit(listOf(st("b")), listOf(st("b", 50f)), StrokeEdit.Kind.TRANSFORM))
        assertEquals(listOf("a", "b", "c"), page.strokes.map { it.id })
        assertEquals(50f, page.strokes[1].points[0].x)
    }

    @Test fun `undo redo cycle`() {
        val page = PageState()
        val h = History()
        h.perform(page, StrokeEdit(emptyList(), listOf(st("a")), StrokeEdit.Kind.DRAW))
        h.perform(page, StrokeEdit(emptyList(), listOf(st("b")), StrokeEdit.Kind.DRAW))
        h.undo(page)
        assertEquals(listOf("a"), page.strokes.map { it.id })
        h.redo(page)
        assertEquals(listOf("a", "b"), page.strokes.map { it.id })
        h.undo(page); h.undo(page)
        assertTrue(page.strokes.isEmpty())
        assertNull(h.undo(page))
    }
}

class EditingTest {
    private val ids = IdGenerator.sequential("e")
    private val line = InkStroke("l", (0..100).map { InkPoint(it.toFloat(), 0f, 0.5f, it.toLong()) }, width = 2f)

    @Test fun `partial erase splits stroke in two`() {
        val pieces = Eraser.erase(line, 50f, 0f, 5f, ids)!!
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].points.last().x < 45f)
        assertTrue(pieces[1].points.first().x > 55f)
        assertTrue(pieces.all { it.width == 2f && it.color == line.color })
    }

    @Test fun `erase at end trims`() {
        val pieces = Eraser.erase(line, 100f, 0f, 5f, ids)!!
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].points.last().x < 95f)
    }

    @Test fun `far eraser does not touch`() {
        assertNull(Eraser.erase(line, 50f, 50f, 5f, ids))
        assertTrue(!Eraser.hits(line, 50f, 50f, 5f))
        assertTrue(Eraser.hits(line, 50f, 3f, 5f))
    }

    @Test fun `lasso polygon selects inside strokes only`() {
        val a = InkStroke("a", listOf(InkPoint(10f, 10f), InkPoint(20f, 20f)))
        val b = InkStroke("b", listOf(InkPoint(200f, 200f), InkPoint(220f, 220f)))
        val poly = listOf(InkPoint(0f, 0f), InkPoint(50f, 0f), InkPoint(50f, 50f), InkPoint(0f, 50f))
        assertEquals(listOf("a"), Lasso.select(listOf(a, b), poly).map { it.id })
    }
}

class StorageTest {
    private fun sample(): Notebook {
        val c = InkController(patterns = { DefaultPatterns.all() }, ids = IdGenerator.sequential())
        c.insertPattern(DefaultPatterns.root(), 300f, 300f, 100f)
        val strokes = c.page.strokes + InkStroke("hand", listOf(InkPoint(1f, 2f, 0.33f, 0), InkPoint(5f, 9f, 0.8f, 12)), color = 0xFF1E4FA0.toInt(), width = 4.5f)
        return Notebook("nb", "Cálculo", listOf(Page("p1", strokes), Page("p2")), Paper.GRID, 1L, 2L)
    }

    @Test fun `T16 notebook json round trip is lossless`() {
        val n = sample()
        assertEquals(n, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(n)))
    }

    @Test fun `T16 editable zip export and import keep points, colors and groups`() {
        val n = sample()
        val bytes = ByteArrayOutputStream().also { TrazoFormat.exportArchive(n, DefaultPatterns.all(), it) }.toByteArray()
        val back = TrazoFormat.importArchive(ByteArrayInputStream(bytes))
        assertEquals(n, back.notebook)
        assertEquals(DefaultPatterns.all(), back.patterns)
        val groups = back.notebook.pages[0].strokes.mapNotNull { it.groupId }.toSet()
        assertEquals(1, groups.size)
    }

    @Test fun `pattern library round trip`() {
        val p = DefaultPatterns.all()
        assertEquals(p, TrazoFormat.decodePatterns(TrazoFormat.encodePatterns(p)))
    }

    @Test fun `newer schema is rejected instead of silently misread`() {
        val text = TrazoFormat.encodeNotebook(sample()).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        assertThrows(UnsupportedSchemaException::class.java) { TrazoFormat.decodeNotebook(text) }
    }

    @Test fun `archive rejects duplicate notebook entries`() {
        val notebook = TrazoFormat.encodeNotebook(sample()).toByteArray(Charsets.UTF_8)
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notebook.json")); zip.write(notebook); zip.closeEntry()
                zip.putNextEntry(ZipEntry("notebook.jsox")); zip.write(notebook); zip.closeEntry()
            }
        }.toByteArray()
        // Same byte length keeps all local-header and central-directory offsets valid.
        val bytes = zipBytes.toString(Charsets.ISO_8859_1).replace("notebook.jsox", "notebook.json")
            .toByteArray(Charsets.ISO_8859_1)
        assertThrows(UnsupportedSchemaException::class.java) {
            TrazoFormat.importArchive(ByteArrayInputStream(bytes))
        }
    }

    @Test fun `archive rejects a compressed oversized unknown entry`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("unknown.bin"))
                val chunk = ByteArray(8192)
                repeat((1024 * 1024 / chunk.size) + 1) { zip.write(chunk) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(bytes.size < 32 * 1024) // The test input is highly compressible.
        assertThrows(IOException::class.java) {
            TrazoFormat.importArchive(ByteArrayInputStream(bytes))
        }
    }

    @Test fun `archive rejects notebook json beyond its streaming limit`() {
        val json = TrazoFormat.encodeNotebook(sample()).toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notebook.json"))
                zip.write(json)
                val chunk = ByteArray(8192) { ' '.code.toByte() }
                val padding = TrazoFormat.MAX_NOTEBOOK_ENTRY_BYTES + 1 - json.size
                repeat((padding + chunk.size - 1) / chunk.size) { zip.write(chunk) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IOException::class.java) {
            TrazoFormat.importArchive(ByteArrayInputStream(bytes))
        }
    }

    @Test fun `archive rejects patterns json beyond its streaming limit`() {
        val notebook = TrazoFormat.encodeNotebook(sample()).toByteArray(Charsets.UTF_8)
        val patterns = TrazoFormat.encodePatterns(emptyList()).toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notebook.json")); zip.write(notebook); zip.closeEntry()
                zip.putNextEntry(ZipEntry("patterns.json")); zip.write(patterns)
                val chunk = ByteArray(8192) { ' '.code.toByte() }
                val padding = TrazoFormat.MAX_PATTERNS_ENTRY_BYTES + 1 - patterns.size
                repeat((padding + chunk.size - 1) / chunk.size) { zip.write(chunk) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IOException::class.java) {
            TrazoFormat.importArchive(ByteArrayInputStream(bytes))
        }
    }

    @Test fun `standalone pattern import enforces byte cap while reading`() {
        val prefix = TrazoFormat.encodePatterns(emptyList()).toByteArray(Charsets.UTF_8)
        val stream = object : java.io.InputStream() {
            var position = 0
            override fun read(): Int = if (position++ < prefix.size) prefix[position - 1].toInt() and 0xff else ' '.code
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= TrazoFormat.MAX_PATTERNS_ENTRY_BYTES + 1) return -1
                val count = minOf(length, TrazoFormat.MAX_PATTERNS_ENTRY_BYTES + 1 - position)
                for (i in 0 until count) buffer[offset + i] = if (position + i < prefix.size) prefix[position + i] else ' '.code.toByte()
                position += count
                return count
            }
        }
        assertThrows(IOException::class.java) { TrazoFormat.decodePatterns(stream) }
    }

    @Test fun `unknown fields are ignored for forward compatibility`() {
        val text = TrazoFormat.encodeNotebook(sample()).replaceFirst("{", "{\"futureField\":true,")
        assertEquals(sample(), TrazoFormat.decodeNotebook(text))
    }
}
