package io.trazo.core

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Untrusted `.trazo` reader. All budgets apply to bytes ACTUALLY read, not ZipEntry.size,
 * which may be -1 or fabricated. JSON is decoded only after the complete archive passes.
 */
internal object ArchiveImportGuard {
    const val MAX_NOTEBOOK_BYTES = 32 * 1024 * 1024
    const val MAX_PATTERNS_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 48 * 1024 * 1024
    const val MAX_COMPRESSED_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRIES = 128
    private const val MAX_IGNORED_BYTES = 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_DEPTH = 128
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024

    data class Parts(val notebook: ByteArray, val patterns: ByteArray?, val cover: ByteArray?, val coverName: String?)

    fun read(input: InputStream): Parts {
        val watched = TrailerInput(input, MAX_COMPRESSED_BYTES.toLong())
        var notebook: ByteArray? = null
        var patterns: ByteArray? = null
        var cover: ByteArray? = null
        var entryCount = 0
        var total = 0L
        var coverSeen = false
        var coverName: String? = null
        val seen = HashSet<String>()
        ZipInputStream(watched).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) exceeded("Demasiadas entradas ZIP (máximo $MAX_ENTRIES)")
                val name = entry.name
                if (name.length !in 1..128 || name.any { it == '/' || it == '\\' || it.code < 32 } || name == "." || name == "..") {
                    invalid("Nombre de entrada ZIP no permitido")
                }
                val canonical = name.lowercase(java.util.Locale.ROOT)
                if (canonical in setOf("notebook.json", "patterns.json", "manifest.json", "cover.webp", "cover.png") && name != canonical) {
                    invalid("Nombre ambiguo en el ZIP: $name")
                }
                if (!seen.add(name)) invalid("Entrada duplicada en ZIP: $name")
                val isCover = name == "cover.webp" || name == "cover.png"
                if (isCover && coverSeen) invalid("El ZIP contiene más de una portada")
                if (isCover) coverSeen = true
                val limit = when (name) {
                    "notebook.json" -> MAX_NOTEBOOK_BYTES
                    "patterns.json" -> MAX_PATTERNS_BYTES
                    "manifest.json" -> MAX_MANIFEST_BYTES
                    "cover.webp", "cover.png" -> MAX_COVER_BYTES
                    else -> MAX_IGNORED_BYTES
                }
                val discardWhenOversized = isCover
                // Data descriptors often hide the real size: never rely on this check alone.
                if (entry.size >= 0 && entry.size > limit && !discardWhenOversized) exceeded("Entrada demasiado grande: $name")
                val out = if (name in setOf("notebook.json", "patterns.json", "cover.webp", "cover.png")) ByteArrayOutputStream(minOf(limit, 8192)) else null
                val buffer = ByteArray(8192)
                var entrySize = 0L
                var keep = true
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    entrySize += n
                    total += n
                    if (total > MAX_TOTAL_BYTES) exceeded("El ZIP supera el límite global de $MAX_TOTAL_BYTES bytes")
                    if (entrySize > limit) {
                        if (!discardWhenOversized) exceeded("Entrada demasiado grande: $name")
                        keep = false // Still drain to check CRC, but do not keep allocating memory.
                    }
                    if (keep) out?.write(buffer, 0, n)
                }
                zip.closeEntry() // A malformed data descriptor / incorrect CRC must fail.
                when (name) {
                    "notebook.json" -> notebook = out!!.toByteArray().also { validateJsonStructure(it) }
                    "patterns.json" -> patterns = out!!.toByteArray().also { validateJsonStructure(it) }
                    "cover.webp", "cover.png" -> {
                        val bytes = if (keep) out!!.toByteArray() else null
                        cover = bytes
                        coverName = name
                    }
                }
            }
            // ZipInputStream stops at the central directory. Consume its remainder and check
            // that the archive has an intact end-of-central-directory record (incl. comments).
            watched.drainAndVerifyEnd(entryCount)
        }
        return Parts(notebook ?: invalid("Archivo sin notebook.json"), patterns, cover, coverName)
    }

    /** Reject excessive nesting before kotlinx.serialization builds large recursive JSON trees. */
    private fun validateJsonStructure(bytes: ByteArray) {
        val stack = ByteArray(MAX_DEPTH)
        var depth = 0
        var quoted = false
        var escaped = false
        for (b in bytes) {
            if (quoted) {
                if (escaped) escaped = false
                else if (b == '\\'.code.toByte()) escaped = true
                else if (b == '"'.code.toByte()) quoted = false
                continue
            }
            when (b.toInt().toChar()) {
                '"' -> quoted = true
                '{', '[' -> {
                    if (depth == MAX_DEPTH) exceeded("JSON anidado más de $MAX_DEPTH niveles")
                    stack[depth++] = b
                }
                '}', ']' -> {
                    if (depth == 0) invalid("JSON mal cerrado")
                    val open = stack[--depth].toInt().toChar()
                    if ((open == '{' && b.toInt().toChar() != '}') || (open == '[' && b.toInt().toChar() != ']')) invalid("JSON mal cerrado")
                }
            }
        }
        if (quoted || depth != 0) invalid("JSON incompleto")
    }

    class LimitExceeded(reason: String) : IllegalArgumentException(reason)
    private fun exceeded(reason: String): Nothing = throw LimitExceeded(reason)
    private fun invalid(reason: String): Nothing = throw IllegalArgumentException(reason)

    /**
     * Retains only the last 65,557 compressed bytes, enough for EOCD + ZIP comment (65,535).
     * The second budget prevents unbounded central-directory/comment garbage.
     */
    private class TrailerInput(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private val tail = ByteArray(65557)
        private var tailNext = 0
        private var retained = 0
        private var consumed = 0L

        override fun read(): Int {
            val b = `in`.read()
            if (b >= 0) record(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len)
            if (n > 0) record(b, off, n)
            return n
        }

        private fun record(b: ByteArray, offset: Int, length: Int) {
            consumed += length
            if (consumed > maxBytes) exceeded("ZIP comprimido demasiado grande")
            // No large intermediate copy; O(length), fixed <=64 KiB of retained memory.
            for (i in offset until offset + length) {
                tail[tailNext] = b[i]
                tailNext = (tailNext + 1) % tail.size
                if (retained < tail.size) retained++
            }
        }

        fun drainAndVerifyEnd(entries: Int) {
            val buffer = ByteArray(8192)
            while (read(buffer) != -1) { /* drain */ }
            val view = ByteArray(retained)
            val start = (tailNext + tail.size - retained) % tail.size
            for (i in view.indices) view[i] = tail[(start + i) % tail.size]
            // EOCD signature 50 4b 05 06. Its 16-bit comment length must end exactly at EOF.
            var matched = false
            for (i in 0..(view.size - 22).coerceAtLeast(-1)) {
                if (view[i] != 0x50.toByte() || view[i + 1] != 0x4b.toByte() || view[i + 2] != 0x05.toByte() || view[i + 3] != 0x06.toByte()) continue
                val commentLen = u16(view, i + 20)
                if (i + 22 + commentLen != view.size) continue
                if (u16(view, i + 4) != 0 || u16(view, i + 6) != 0) invalid("ZIP multidisco no soportado")
                if (u16(view, i + 8) != entries || u16(view, i + 10) != entries) invalid("Conteo de entradas ZIP inconsistente")
                val centralSize = u32(view, i + 12)
                val centralOffset = u32(view, i + 16)
                val eocdAbsolute = consumed - retained + i
                if (centralSize == 0L || centralSize > tail.size || centralOffset + centralSize != eocdAbsolute) {
                    invalid("Directorio central ZIP inconsistente")
                }
                val at = centralOffset - (consumed - retained)
                if (at >= 0 && at <= (view.size - 4).toLong()) {
                    val j = at.toInt()
                    if (view[j] != 0x50.toByte() || view[j + 1] != 0x4b.toByte() || view[j + 2] != 0x01.toByte() || view[j + 3] != 0x02.toByte()) {
                        invalid("Cabecera del directorio central dañada")
                    }
                }
                matched = true
                break
            }
            if (!matched) invalid("ZIP truncado o sin directorio central válido")
        }

        private fun u16(bytes: ByteArray, i: Int): Int = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
        private fun u32(bytes: ByteArray, i: Int): Long = (u16(bytes, i).toLong() or (u16(bytes, i + 2).toLong() shl 16))
    }
}
