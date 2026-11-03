package io.trazo.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class ArchiveImportGuardTest {
    private val nb = """{"format":"trazo-notebook","schemaVersion":1,"notebook":{"id":"nb","pages":[{"id":"p"}]}}""".toByteArray()
    private val patterns = """{"format":"trazo-patterns","schemaVersion":1,"patterns":[]}""".toByteArray()

    private fun zip(vararg parts: Pair<String, ByteArray>, comment: String? = null): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                if (comment != null) z.setComment(comment)
                for ((name, bytes) in parts) {
                    z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
                }
            }
        }.toByteArray()

    private fun read(bytes: ByteArray) = ArchiveImportGuard.read(ByteArrayInputStream(bytes))

    private inline fun <reified E : Throwable> shouldFail(noinline work: () -> Unit) {
        try { work(); fail("Expected ${E::class.java.simpleName}") }
        catch (ex: Throwable) { if (ex !is E) throw ex }
    }

    @Test fun notebookOnlyOldArchive() {
        val result = read(zip("notebook.json" to nb))
        assertArrayEquals(nb, result.notebook)
        assertNull(result.patterns)
        assertNull(result.cover)
    }
    @Test fun fullValidArchiveAndComment() {
        val cover = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3)
        val archive = read(zip("manifest.json" to "{}".toByteArray(), "notebook.json" to nb, "patterns.json" to patterns, "cover.webp" to cover, comment = "old exporter"))
        assertArrayEquals(nb, archive.notebook)
        assertArrayEquals(patterns, archive.patterns!!)
        assertArrayEquals(cover, archive.cover!!)
        assertEquals("cover.webp", archive.coverName)
    }
    @Test fun unknownSmallEntryIsIgnored() {
        assertArrayEquals(nb, read(zip("extra-metadata" to "hi".toByteArray(), "notebook.json" to nb)).notebook)
    }
    @Test fun missingNotebookRejected() { shouldFail<IllegalArgumentException> { read(zip("patterns.json" to patterns)) } }
    @Test fun duplicateNotebookRejected() {
        // Built with Python zipfile which permits duplicate names (Java ZipOutputStream forbids them).
        val raw = java.util.Base64.getDecoder().decode("UEsDBBQAAAAIAACXXl1Dv6ajBAAAAAIAAAANAAAAbm90ZWJvb2suanNvbquuBQBQSwMEFAAAAAgAAJdeXUO/pqMEAAAAAgAAAA0AAABub3RlYm9vay5qc29uq64FAFBLAQIUAxQAAAAIAACXXl1Dv6ajBAAAAAIAAAANAAAAAAAAAAAAAACAAQAAAABub3RlYm9vay5qc29uUEsBAhQDFAAAAAgAAJdeXUO/pqMEAAAAAgAAAA0AAAAAAAAAAAAAAIABLwAAAG5vdGVib29rLmpzb25QSwUGAAAAAAIAAgB2AAAAXgAAAAAA")
        shouldFail<IllegalArgumentException> { read(raw) }
    }
    @Test fun twoCoverFormatsRejected() { shouldFail<IllegalArgumentException> { read(zip("notebook.json" to nb, "cover.webp" to byteArrayOf(1), "cover.png" to byteArrayOf(2))) } }
    @Test fun dotPathRejected() { shouldFail<IllegalArgumentException> { read(zip("./notebook.json" to nb)) } }
    @Test fun caseAmbiguityRejected() { shouldFail<IllegalArgumentException> { read(zip("NOTEBOOK.JSON" to nb, "notebook.json" to nb)) } }
    @Test fun traversalRejected() { shouldFail<IllegalArgumentException> { read(zip("../notebook.json" to nb)) } }
    @Test fun backslashPathRejected() { shouldFail<IllegalArgumentException> { read(zip("a\\notebook.json" to nb)) } }
    @Test fun tooManyEntriesRejected() {
        val parts = (1..ArchiveImportGuard.MAX_ENTRIES).map { "unknown-$it" to byteArrayOf(1) }.toTypedArray()
        shouldFail<IllegalArgumentException> { read(zip("notebook.json" to nb, *parts)) }
    }
    @Test fun oversizedNotebookDeflateBombRejected() {
        val huge = ByteArray(ArchiveImportGuard.MAX_NOTEBOOK_BYTES + 1) { ' '.code.toByte() }
        shouldFail<IllegalArgumentException> { read(zip("notebook.json" to huge)) }
    }
    @Test fun oversizedPatternsRejected() {
        val huge = ByteArray(ArchiveImportGuard.MAX_PATTERNS_BYTES + 1) { ' '.code.toByte() }
        shouldFail<IllegalArgumentException> { read(zip("notebook.json" to nb, "patterns.json" to huge)) }
    }
    @Test fun oversizedUnknownRejected() {
        shouldFail<IllegalArgumentException> { read(zip("notebook.json" to nb, "unknown" to ByteArray(1024 * 1024 + 1))) }
    }
    @Test fun invalidCoverGracefullyFallsBack() {
        val big = ByteArray(2 * 1024 * 1024 + 1) { 'x'.code.toByte() }
        assertNull(read(zip("notebook.json" to nb, "cover.webp" to big)).cover)
    }
    @Test fun truncatedEOCDRejected() {
        val full = zip("notebook.json" to nb)
        shouldFail<IllegalArgumentException> { read(full.copyOf(full.size - 5)) }
    }
    @Test fun truncatedEntryRejected() {
        val full = zip("notebook.json" to nb)
        shouldFail<Throwable> { read(full.copyOf(18)) }
    }
    @Test fun forgedCentralOffsetRejected() {
        val bytes = zip("notebook.json" to nb)
        val at = bytes.size - 22
        check(bytes[at] == 0x50.toByte() && bytes[at + 3] == 0x06.toByte())
        bytes[at + 16] = (bytes[at + 16].toInt() xor 0x01).toByte()
        shouldFail<IllegalArgumentException> { read(bytes) }
    }
    @Test fun missingCentralHeaderRejected() {
        val bytes = zip("notebook.json" to nb)
        val at = bytes.indices.first { i -> i+3 < bytes.size && bytes[i] == 0x50.toByte() && bytes[i+1]==0x4b.toByte() && bytes[i+2]==0x01.toByte() && bytes[i+3]==0x02.toByte() }
        bytes[at+2] = 0x09
        shouldFail<IllegalArgumentException> { read(bytes) }
    }
    @Test fun malformedJsonNestingRejected() {
        val deep = "[".repeat(129) + "0" + "]".repeat(129)
        shouldFail<IllegalArgumentException> { read(zip("notebook.json" to deep.toByteArray())) }
    }
    @Test fun bracketInsideStringsDoesNotCountTowardsDepth() {
        val item = "{\"x\":\"" + "[[[[{{{{".repeat(100) + "\"}"
        assertArrayEquals(item.toByteArray(), read(zip("notebook.json" to item.toByteArray())).notebook)
    }
    @Test fun unbalancedJsonRejected() {
        shouldFail<IllegalArgumentException> { read(zip("notebook.json" to "{[}".toByteArray())) }
    }
    @Test fun wrongCRCRejected() {
        val bytes = zip("notebook.json" to nb)
        // Modify compressed data descriptor CRC (signature PK0708), leaving the payload intact.
        val signature = byteArrayOf(0x50, 0x4b, 0x07, 0x08)
        val at = bytes.indices.firstOrNull { idx -> idx + 7 < bytes.size && bytes.copyOfRange(idx, idx + 4).contentEquals(signature) }
            ?: error("ZIP data descriptor absent")
        bytes[at + 4] = (bytes[at + 4].toInt() xor 0x01).toByte()
        shouldFail<java.util.zip.ZipException> { read(bytes) }
    }
    @Test fun deterministicFuzzNeverReturnsUnverifiedRandomInput() {
        val rng = Random(71)
        repeat(100) {
            val b = ByteArray(rng.nextInt(1500) + 1).also(rng::nextBytes)
            try { read(b); fail("Random garbage accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.util.zip.ZipException) { }
        }
    }
}
