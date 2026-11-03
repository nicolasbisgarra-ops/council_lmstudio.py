package io.trazo.app.data

import io.trazo.app.ai.validateModelSourceUrl
import io.trazo.app.ai.isPinnedHuggingFaceUrl

import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque

/** JVM tests run the REAL downloader with a fake transport, never external network or Android. */
class LocalModelDownloadsTest {
    private val full = "GGUF".toByteArray() + ByteArray(129) { (it % 255).toByte() }
    private val hf = "https://huggingface.co/acme/demo/resolve/${"a".repeat(40)}/model.gguf"

    private fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun request(url: String = hf, bytes: ByteArray = full, length: Long? = bytes.size.toLong()) = ModelDownloadRequest(url, "sample.gguf", hash(bytes), length)
    private fun directory(): File = Files.createTempDirectory("trazo-a2-test-").toFile().apply { deleteOnExit() }
    private fun expectFailure(expected: String, body: () -> Unit) {
        val e = try { body(); throw AssertionError("expected exception containing $expected") } catch (e: Exception) { e }
        check(e.message?.contains(expected, ignoreCase = true) == true) { "Unexpected: ${e.message} (wanted $expected)" }
    }
    private fun ok(value: Boolean, message: String) = check(value) { message }

    private class Reply(
        override val status: Int,
        val bytes: ByteArray,
        val headers: Map<String, String> = emptyMap(),
        private val stopAfter: Int? = null,
    ) : ModelHttpResponse {
        override fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override val body: InputStream = object : InputStream() {
            private var at = 0
            override fun read(): Int { val b = ByteArray(1); val n = read(b, 0, 1); return if (n < 0) -1 else b[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (stopAfter != null && at >= stopAfter) throw IOException("network interrupted")
                if (at >= bytes.size) return -1
                val avail = bytes.size - at
                val limit = if (stopAfter == null) avail else minOf(avail, stopAfter - at)
                val n = minOf(len, limit, 17)
                System.arraycopy(bytes, at, b, off, n)
                at += n
                return n
            }
        }
        override fun close() = body.close()
    }

    private class FakeHttp(vararg responses: Reply) : ModelHttpTransfer {
        val calls = mutableListOf<Pair<Long, String?>>()
        val queue = ArrayDeque(responses.asList())
        override fun open(url: String, offset: Long, ifRange: String?): ModelHttpResponse {
            calls += offset to ifRange
            return queue.pollFirst() ?: throw IOException("Unexpected network call")
        }
    }

    private fun success(data: ByteArray = full, etag: String = "\"v1\"") =
        Reply(200, data, mapOf("Content-Length" to data.size.toString(), "ETag" to etag))

    private fun partial(data: ByteArray = full, prefix: Int = 37, etag: String = "\"v1\"") =
        Reply(200, data, mapOf("Content-Length" to data.size.toString(), "ETag" to etag), stopAfter = prefix)

    private fun continued(data: ByteArray = full, offset: Int = 37, etag: String = "\"v1\"") =
        Reply(206, data.copyOfRange(offset, data.size), mapOf(
            "Content-Range" to "bytes $offset-${data.size - 1}/${data.size}", "ETag" to etag))

    @Test fun pinnedHfRevisionIsAccepted() { ok(isPinnedHuggingFaceUrl(hf), "pinned HF commit") }
    @Test fun movingHfRevisionIsRejected() { expectFailure("commit") { ModelDownloadRequest(hf.replace("a".repeat(40), "main"), "m.gguf") } }
    @Test fun plaintextHttpIsRejected() { expectFailure("HTTPS") { ModelDownloadRequest(hf.replace("https", "http"), "m.gguf") } }
    @Test fun localHostsAndIpAreRejected() {
        for (url in listOf("https://localhost/m.gguf", "https://192.168.1.1/m.gguf", "https://box.local/m.gguf")) {
            expectFailure(if (url.contains("192.")) "DNS" else "local") { ModelDownloadRequest(url, "m.gguf", hash(full)) }
        }
    }
    @Test fun credentialsFragmentAndCustomPortRejected() {
        for (url in listOf("https://a@models.example/m.gguf", "https://models.example:8443/m.gguf", "https://models.example/m.gguf#fragment"))
            expectFailure("URL") { ModelDownloadRequest(url, "m.gguf", hash(full)) }
    }
    @Test fun arbitraryHttpsNeedsDigest() { expectFailure("SHA256") { ModelDownloadRequest("https://models.example/model.gguf", "m.gguf") } }
    @Test fun arbitraryHttpsWithDigestAllowed() { request("https://models.example/model.gguf") }
    @Test fun unsafeFilenameRejected() { expectFailure("filename") { ModelDownloadRequest(hf, "../model.gguf") } }
    @Test fun happyPathPublishesOnlyValidFile() {
        val dir = directory(); val http = FakeHttp(success()); val progress = mutableListOf<ModelDownloadProgress>()
        val result = LocalModelDownloader(dir, http).download(request(), onProgress = { progress += it })
        ok(result.isFile && result.readBytes().contentEquals(full), "bytes persisted")
        ok(dir.listFiles().orEmpty().size == 1, "no partial or metadata")
        ok(progress.last().downloadedBytes == full.size.toLong(), "progress reported")
    }
    @Test fun interruptionPreservesPartialAndIfRangeResumes() {
        val dir = directory(); val http = FakeHttp(partial(), continued())
        val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        ok(dir.listFiles().orEmpty().any { it.name.endsWith(".part") }, "partial left")
        val f = worker.download(request())
        ok(f.readBytes().contentEquals(full), "resumed correct full bytes")
        ok(http.calls == listOf(0L to null, 37L to "\"v1\""), "offset and validator")
        ok(dir.listFiles().orEmpty().size == 1, "complete and no temp")
    }
    @Test fun serverIgnoringRangeRestartsFromZero() {
        val dir = directory(); val http = FakeHttp(partial(), success()); val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        ok(worker.download(request()).readBytes().contentEquals(full), "restart without duplicate prefix")
        ok(http.calls[1].first == 37L, "resume attempted")
    }
    @Test fun rangeNotSatisfiableRetriesFreshOnce() {
        val dir = directory(); val http = FakeHttp(partial(), Reply(416, ByteArray(0)), success()); val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        ok(worker.download(request()).readBytes().contentEquals(full), "retry fresh")
        ok(http.calls.map { it.first } == listOf(0L, 37L, 0L), "offsets")
    }
    @Test fun changedEtagMustRestartWithoutMixing() {
        val dir = directory(); val http = FakeHttp(partial(), continued(etag = "\"v2\""), success(etag = "\"v2\"")); val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        ok(worker.download(request()).readBytes().contentEquals(full), "restarted correctly")
        ok(http.calls.map { it.first } == listOf(0L, 37L, 0L), "restarted on mismatch")
    }
    @Test fun wrongRangeStartRejectsWithoutPublishing() {
        val dir = directory(); val http = FakeHttp(partial(), Reply(206, full.copyOfRange(38, full.size), mapOf("Content-Range" to "bytes 38-${full.size - 1}/${full.size}")))
        val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        expectFailure("Range") { worker.download(request()) }
        ok(dir.listFiles().orEmpty().none { it.extension == "gguf" }, "no published model")
    }
    @Test fun badHashRejectsAndDeletesPartial() {
        val dir = directory(); val http = FakeHttp(success())
        val req = ModelDownloadRequest(hf, "m.gguf", hash("different".toByteArray()), full.size.toLong())
        expectFailure("SHA256") { LocalModelDownloader(dir, http).download(req) }
        ok(dir.listFiles().orEmpty().none { it.name.endsWith(".part") || it.extension == "gguf" }, "corrupt removed")
    }
    @Test fun badHeaderRejects() {
        val bad = "BLAH".toByteArray() + ByteArray(20)
        val dir = directory()
        expectFailure("header") { LocalModelDownloader(dir, FakeHttp(success(bad))).download(request(bytes = bad)) }
    }
    @Test fun excessiveDeclaredSizeFailsBeforeHttp() {
        val dir = directory(); val http = FakeHttp(success())
        expectFailure("size") { LocalModelDownloader(dir, http, maxBytes = 40).download(request()) }
        ok(http.calls.isEmpty(), "no network after preflight rejection")
    }
    @Test fun streamingEnforcesLimitForUnknownLength() {
        val dir = directory(); val http = FakeHttp(Reply(200, full, mapOf("ETag" to "\"v1\"")))
        expectFailure("maximum") { LocalModelDownloader(dir, http, maxBytes = 40, reserveBytes = 0).download(request(length = null)) }
        ok(dir.listFiles().orEmpty().none { it.extension == "gguf" }, "not published")
    }
    @Test fun repeatFetchUsesVerifiedCacheWithoutNetwork() {
        val dir = directory(); val http = FakeHttp(success()); val worker = LocalModelDownloader(dir, http)
        val first = worker.download(request()); val again = worker.download(request())
        ok(first == again && http.calls.size == 1, "cache reused")
    }
    @Test fun cancellationPreservesPartialForLaterResume() {
        val dir = directory(); val http = FakeHttp(success(), continued(offset = 17)); val worker = LocalModelDownloader(dir, http)
        var reported = 0L
        expectFailure("cancel") { worker.download(request(), checkActive = { if (reported > 0L) throw CancellationException("cancelled") }, onProgress = { reported = it.downloadedBytes }) }
        ok(reported in 1 until full.size.toLong(), "cancelled mid-response")
        ok(dir.listFiles().orEmpty().any { it.name.endsWith(".part") && it.length() == reported }, "partial retained")
        ok(worker.download(request()).readBytes().contentEquals(full), "cancelled download resumed")
    }
    @Test fun differentProfilesNeverAccidentallySharePartial() {
        val dir = directory(); val http = FakeHttp(partial(), success()); val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        val second = ModelDownloadRequest(hf.replace("/model.gguf", "/other.gguf"), "sample.gguf", hash(full), full.size.toLong())
        val b = worker.download(second)
        ok(b.readBytes().contentEquals(full), "second independent")
        ok(http.calls.last().first == 0L, "did not resume first")
    }

    @Test fun weakEtagUsesLastModifiedAsIfRange() {
        val dir=directory()
        val first=Reply(200, full, mapOf("Content-Length" to full.size.toString(),"ETag" to "W/\"weak\"","Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT"),stopAfter=37)
        val http=FakeHttp(first,continued())
        val worker=LocalModelDownloader(dir,http)
        expectFailure("interrupted") { worker.download(request()) }
        ok(worker.download(request()).readBytes().contentEquals(full),"last modified resume")
        ok(http.calls[1].second=="Wed, 21 Oct 2015 07:28:00 GMT","weak ETag ignored")
    }

    @Test fun corruptedResumeCheckpointStartsFromZero() {
        val dir=directory();val http=FakeHttp(partial(),success());val worker=LocalModelDownloader(dir,http)
        expectFailure("interrupted") { worker.download(request()) }
        val state=dir.listFiles().orEmpty().first{it.extension=="properties"}
        state.writeText("id=NOT_THE_EXPECTED_ID\netag=\"v1\"\n")
        ok(worker.download(request()).readBytes().contentEquals(full),"recovered from malformed metadata")
        ok(http.calls.last().first==0L,"did not append to unrelated bytes")
    }

    @Test fun noValidatorForcesRestartAfterInterruptedDownload() {
        val dir = directory()
        val http = FakeHttp(Reply(200, full, mapOf("Content-Length" to full.size.toString()), stopAfter = 37), success())
        val worker = LocalModelDownloader(dir, http)
        expectFailure("interrupted") { worker.download(request()) }
        check(worker.download(request()).readBytes().contentEquals(full))
        check(http.calls.map { it.first } == listOf(0L,0L))
    }

    @Test fun lastModifiedChangeMustRestart() {
        val dir=directory()
        val first=Reply(200, full, mapOf("Content-Length" to full.size.toString(),"Last-Modified" to "Mon, 01 Jan 2024 00:00:00 GMT"),stopAfter=37)
        val bad=Reply(206,full.copyOfRange(37,full.size),mapOf("Content-Range" to "bytes 37-${full.size-1}/${full.size}","Last-Modified" to "Tue, 02 Jan 2024 00:00:00 GMT"))
        val http=FakeHttp(first,bad,success())
        val worker=LocalModelDownloader(dir,http)
        expectFailure("interrupted") { worker.download(request()) }
        check(worker.download(request()).readBytes().contentEquals(full))
        check(http.calls.map { it.first } == listOf(0L,37L,0L))
    }

    @Test fun encodedServerBodyIsRejected() {
        val dir=directory()
        val http=FakeHttp(Reply(200,full,mapOf("Content-Encoding" to "gzip","Content-Length" to full.size.toString())))
        expectFailure("encoded") { LocalModelDownloader(dir,http).download(request()) }
        check(dir.listFiles().orEmpty().none { it.extension=="gguf" })
    }

    @Test fun serverFailurePreservesPartialForLaterRetry() {
        val dir=directory()
        val http=FakeHttp(partial(),Reply(503,ByteArray(0)),continued())
        val worker=LocalModelDownloader(dir,http)
        expectFailure("interrupted") { worker.download(request()) }
        expectFailure("503") { worker.download(request()) }
        check(dir.listFiles().orEmpty().any{it.name.endsWith(".part")})
        check(worker.download(request()).readBytes().contentEquals(full))
    }

    @Test fun symLinkPartialIsRejectedWithoutFollowingIt() {
        val dir=directory();val http=FakeHttp(partial());val worker=LocalModelDownloader(dir,http)
        expectFailure("interrupted") { worker.download(request()) }
        val part=dir.listFiles().orEmpty().single { it.name.endsWith(".part") }
        part.delete()
        val victim=File(dir.parentFile, "outside-model-store-symlink-test-${System.nanoTime()}.txt").apply { writeText("DO NOT TOUCH") }
        try {
            Files.createSymbolicLink(part.toPath(),victim.toPath())
            expectFailure("unsafe") { worker.download(request()) }
            check(victim.readText()=="DO NOT TOUCH")
        } finally { part.delete();victim.delete() }
    }

    @Test fun changedRemoteSizeRestartsEvenWithSameValidator() {
        val dir=directory();val http=FakeHttp(partial(),
            Reply(206, full.copyOfRange(37,full.size), mapOf("Content-Range" to "bytes 37-${full.size-1}/${full.size+3}","ETag" to "\"v1\"")),
            success())
        val worker=LocalModelDownloader(dir,http)
        expectFailure("interrupted") { worker.download(request()) }
        check(worker.download(request()).readBytes().contentEquals(full))
        check(http.calls.map {it.first}==listOf(0L,37L,0L))
    }
}
