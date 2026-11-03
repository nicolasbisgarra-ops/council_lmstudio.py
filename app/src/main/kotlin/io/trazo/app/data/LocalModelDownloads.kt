package io.trazo.app.data

import io.trazo.app.ai.HttpUrlModelTransfer
import io.trazo.app.ai.validateModelSourceUrl
import io.trazo.app.ai.isPinnedHuggingFaceUrl

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException

/** An immutable, user-approved HTTPS source. Hugging Face URLs must pin the 40-digit commit. */
data class ModelDownloadRequest(
    val url: String,
    val fileName: String,
    val sha256: String? = null,
    val expectedBytes: Long? = null,
) {
    init {
        validateModelSourceUrl(url)
        require(fileName == File(fileName).name && fileName.none { it == '\\' || it == '/' }) { "Invalid filename" }
        require(fileName.substringAfterLast('.', "").lowercase() in setOf("task", "gguf", "mmproj")) { "Unsupported model format" }
        require(sha256 == null || SHA256.matches(sha256)) { "Expected SHA256 must be 64 hex digits" }
        require(expectedBytes == null || expectedBytes > 0) { "Expected length must be positive" }
        // A mutable or independent URL must have a content digest. A pinned Hugging Face
        // revision is also safe, provided the origin cannot silently change the bytes.
        require(sha256 != null || isPinnedHuggingFaceUrl(url)) {
            "Provide an expected SHA256 for non-pinned HTTPS downloads"
        }
    }
}

internal val SHA256 = Regex("[0-9a-fA-F]{64}")

/** Implementations can be supplied by JVM tests; source never logs bearer URLs. */
internal interface ModelHttpTransfer {
    fun open(url: String, offset: Long, ifRange: String?): ModelHttpResponse
}

internal interface ModelHttpResponse : Closeable {
    val status: Int
    fun header(name: String): String?
    val body: InputStream
}

data class ModelDownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

/**
 * Durable download with an exact content boundary. Only the completed, checked model appears
 * in the model catalog. Interruption preserves a resumable .part + atomic .properties state.
 */
internal class LocalModelDownloader(
    private val directory: File,
    private val http: ModelHttpTransfer = HttpUrlModelTransfer(),
    private val maxBytes: Long = 8L * 1024L * 1024L * 1024L,
    private val reserveBytes: Long = 32L * 1024L * 1024L,
) {
    @Synchronized
    fun download(
        request: ModelDownloadRequest,
        checkActive: () -> Unit = {},
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): File {
        require(directory.isDirectory || directory.mkdirs()) { "Model directory unavailable" }
        val specId = sha256Bytes((request.url + "\n" + request.fileName + "\n" + (request.sha256 ?: "")).toByteArray()).take(24)
        val stem = safeModelStem(request.fileName.substringBeforeLast('.')).take(52)
        val ext = request.fileName.substringAfterLast('.').lowercase()
        val final = File(directory, "$stem-$specId.$ext")
        val part = File(directory, "$stem-$specId.part")
        val stateFile = File(directory, "$stem-$specId.properties")
        val stateId = sha256Bytes((request.url + "\n" + (request.sha256 ?: "")).toByteArray())
        require(request.expectedBytes == null || request.expectedBytes <= maxBytes) { "Model exceeds size limit" }
        if (final.exists()) {
            if (verify(final, request)) return final
            throw IOException("Existing model differs from requested digest; remove manually")
        }
        if (part.isSymbolicLink() || final.isSymbolicLink() || stateFile.isSymbolicLink()) throw IOException("Unsafe model path")
        var state = readState(stateFile)?.takeIf { it.id == stateId }
        if (state == null || !part.isFile || part.length() <= 0 || state.validator == null) {
            part.delete()
            stateFile.delete()
            state = null
        }
        // One retry is enough when a server ignores Range or the remote object changed.
        repeat(2) { attempt ->
            checkActive()
            val offset = if (state != null) part.length() else 0L
            val validator = state?.validator
            http.open(request.url, offset, validator).use { response ->
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw IOException("Encoded download body is not resumable")
                val status = response.status
                if (status == 416 || (status == 206 && offset == 0L)) {
                    clearPartial(part, stateFile)
                    state = null
                    if (attempt == 0) return@use else throw IOException("Server returned an invalid Range response")
                }
                if (status != 200 && status != 206) throw IOException("Download failed: HTTP $status")
                val append = status == 206 && offset > 0
                val range = response.header("Content-Range")
                val total = if (append) {
                    val m = range?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) }
                        ?: throw IOException("Invalid Content-Range")
                    val start = m.groupValues[1].toLongOrNull() ?: throw IOException("Invalid Content-Range")
                    val end = m.groupValues[2].toLongOrNull() ?: throw IOException("Invalid Content-Range")
                    val size = m.groupValues[3].toLongOrNull() ?: throw IOException("Invalid Content-Range")
                    if (start != offset || end < start || size <= end) throw IOException("Range does not match partial file")
                    if (state?.total != null && state?.total != size) {
                        clearPartial(part, stateFile)
                        state = null
                        if (attempt == 0) return@use else throw IOException("Resumed object has changed length")
                    }
                    val newEtag = response.header("ETag")
                    val changedEtag = state?.etag != null && newEtag != null && state?.etag != newEtag
                    val newLastModified = response.header("Last-Modified")
                    val changedDate = state?.etag == null && state?.lastModified != null &&
                        newLastModified != null && state?.lastModified != newLastModified
                    if (changedEtag || changedDate) {
                        clearPartial(part, stateFile); state = null
                        if (attempt == 0) return@use else throw IOException("Remote object changed during download")
                    }
                    size
                } else {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                }
                val expected = request.expectedBytes
                if (total != null && (total > maxBytes || (expected != null && total != expected))) {
                    throw IOException("Downloaded model has unexpected size")
                }
                val etag = response.header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
                val lastMod = response.header("Last-Modified")?.takeIf { it.isNotBlank() }
                val nextState = DownloadState(stateId, etag ?: if (append) state?.etag else null, lastMod ?: if (append) state?.lastModified else null, total)
                writeState(stateFile, nextState)
                state = nextState
                var count = if (append) offset else 0L
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    try {
                        while (true) {
                            checkActive()
                            val n = response.body.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            if (n.toLong() > maxBytes - count || (total != null && n.toLong() > total - count)) {
                                throw IOException("Download exceeds maximum bytes")
                            }
                            val free = directory.usableSpace
                            if (free > 0 && n.toLong() + reserveBytes > free) throw IOException("Disk space below reserve")
                            output.write(buffer, 0, n)
                            count += n
                            onProgress(ModelDownloadProgress(count, total))
                        }
                    } finally { output.fd.sync() }
                }
                if (total != null && count != total) throw IOException("Incomplete download; can resume")
                if (expected != null && count != expected) throw IOException("Unexpected downloaded size")
                checkActive()
                if (!verify(part, request)) {
                    clearPartial(part, stateFile)
                    throw IOException("Model header or SHA256 validation failed; partial discarded")
                }
                atomicMove(part, final)
                stateFile.delete()
                return final
            }
            // Retry only after explicit discard due to invalid resume response.
        }
        throw IOException("Could not resume download safely")
    }

    private fun verify(file: File, request: ModelDownloadRequest): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > maxBytes) return false
        if (request.expectedBytes != null && file.length() != request.expectedBytes) return false
        val header = FileInputStream(file).use { input ->
            val bytes = ByteArray(8)
            val n = input.read(bytes)
            bytes.copyOf(if (n > 0) n else 0)
        }
        if (!hasValidModelHeader(request.fileName.substringAfterLast('.'), header)) return false
        return request.sha256 == null || sha256File(file).equals(request.sha256, ignoreCase = true)
    }

    private data class DownloadState(val id: String, val etag: String?, val lastModified: String?, val total: Long?) {
        val validator: String? get() = etag ?: lastModified
    }

    private fun readState(file: File): DownloadState? = runCatching {
        Properties().apply { file.inputStream().use(::load) }.let {
            DownloadState(it.getProperty("id") ?: return null, it.getProperty("etag"), it.getProperty("lastModified"), it.getProperty("total")?.toLongOrNull())
        }
    }.getOrNull()

    private fun writeState(file: File, state: DownloadState) {
        val p = Properties().apply {
            setProperty("id", state.id)
            state.etag?.let { setProperty("etag", it) }
            state.lastModified?.let { setProperty("lastModified", it) }
            state.total?.let { setProperty("total", it.toString()) }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { stream -> p.store(stream, null); stream.fd.sync() }
        atomicMove(tmp, file)
    }

    private fun clearPartial(part: File, state: File) { part.delete(); state.delete() }
}

private fun atomicMove(source: File, dest: File) {
    try {
        Files.move(source.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun File.isSymbolicLink() = Files.isSymbolicLink(toPath())

private fun sha256Bytes(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(data).joinToString("") { "%02x".format(it) }
private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { stream ->
        val buf = ByteArray(128 * 1024)
        while (true) { val n = stream.read(buf); if (n < 0) break; if (n > 0) digest.update(buf, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
