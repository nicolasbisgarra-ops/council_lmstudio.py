package io.trazo.app.sync

import io.trazo.core.share.ReviewWebPage
import io.trazo.core.share.SharedMessage
import io.trazo.core.share.SharedNote
import io.trazo.core.share.SharedReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class ReviewWebServerTest {
    private val review = SharedReview(
        title = "Hoja <1>",
        messages = listOf(
            SharedMessage(fromStudent = true, text = "¿Está bien?\n<script>alert('x')</script>", line = 2),
            SharedMessage(fromStudent = false, text = "Revisá el signo & \"el\" paso", correct = false),
            SharedMessage(fromStudent = false, text = "falló", error = true),
        ),
        notes = listOf(SharedNote(3, "signo", "<b>mal</b>", "detalle 'x'", "poné +")),
        updatedAt = 42L,
    )

    private fun server(
        allow: (ByteArray) -> Boolean = { true },
        clock: () -> Long = System::currentTimeMillis,
        png: () -> ByteArray? = { byteArrayOf(1, 2, 3) },
    ) = ReviewWebServer({ review }, png, token = "tok", clock = clock, allowAddress = allow)

    @Test fun routes() {
        val s = server()
        assertEquals(ReviewWebServer.Route.Page, s.route("GET", "/tok/"))
        assertEquals(ReviewWebServer.Route.Page, s.route("GET", "/tok"))
        assertEquals(ReviewWebServer.Route.Page, s.route("HEAD", "/tok/?x=1"))
        assertEquals(ReviewWebServer.Route.Image, s.route("GET", "/tok/page.png"))
        assertEquals(ReviewWebServer.Route.Image, s.route("GET", "/tok/page.png?t=123"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "/bad/"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "/to/"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "/tokk/"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "/"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "/tok/otra"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "/tok/../etc"))
        assertEquals(ReviewWebServer.Route.NotFound, s.route("GET", "http://x/tok/"))
        assertEquals(ReviewWebServer.Route.BadMethod, s.route("POST", "/tok/"))
        assertEquals(ReviewWebServer.Route.BadMethod, s.route("PUT", "/tok/page.png"))
    }

    @Test fun urlsAndTokens() {
        assertEquals("http://192.168.1.5:8080/abc/", ReviewWebServer.url("192.168.1.5", 8080, "abc"))
        assertEquals("http://[fd7a::1]:80/abc/", ReviewWebServer.url("fd7a::1%wlan0", 80, "abc"))
        val a = ReviewWebServer.newToken()
        val b = ReviewWebServer.newToken()
        assertNotEquals(a, b)
        assertTrue(a.length >= 22)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    private fun get(port: Int, path: String, method: String = "GET"): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 3000
            s.getOutputStream().write("$method $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArrayOutputStream()
            val input = s.getInputStream()
            val b = ByteArray(4096)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                buf.write(b, 0, n)
            }
            return buf.toString("UTF-8")
        }
    }

    @Test fun servesOverSocket() {
        val s = server()
        val port = s.start()
        try {
            assertTrue(s.isRunning)
            val ok = get(port, "/tok/")
            assertTrue(ok, ok.startsWith("HTTP/1.1 200"))
            assertTrue(ok.contains("Cache-Control: no-store"))
            assertTrue(ok.contains("X-Content-Type-Options: nosniff"))
            assertTrue(ok.contains("Content-Type: text/html; charset=utf-8"))
            assertTrue(ok.contains("Hoja &lt;1&gt;"))
            val png = get(port, "/tok/page.png?t=1")
            assertTrue(png.startsWith("HTTP/1.1 200"))
            assertTrue(png.contains("Content-Type: image/png"))
            assertTrue(png.contains("Content-Length: 3"))
            val bad = get(port, "/bad/")
            assertTrue(bad.startsWith("HTTP/1.1 404"))
            assertTrue(bad.contains("Cache-Control: no-store"))
            assertFalse(bad.contains("Hoja"))
            val post = get(port, "/tok/", "POST")
            assertTrue(post.startsWith("HTTP/1.1 405"))
            assertTrue(post.contains("Allow: GET, HEAD"))
            val head = get(port, "/tok/", "HEAD")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertFalse(head.contains("<html"))
        } finally {
            s.stop()
        }
        assertFalse(s.isRunning)
    }

    @Test fun errorsAreGeneric() {
        val s = server(png = { error("secreto") })
        val port = s.start()
        try {
            val r = get(port, "/tok/page.png")
            assertTrue(r.startsWith("HTTP/1.1 500"))
            assertFalse(r.contains("secreto"))
            val none = ReviewWebServer({ review }, { null }, token = "tok", allowAddress = { true })
            val p2 = none.start()
            try {
                assertTrue(get(p2, "/tok/page.png").startsWith("HTTP/1.1 404"))
            } finally {
                none.stop()
            }
        } finally {
            s.stop()
        }
    }

    @Test fun refusesOtherAddresses() {
        val s = server(allow = { false })
        val port = s.start()
        try {
            val r = try {
                get(port, "/tok/")
            } catch (_: SocketTimeoutException) {
                "timeout"
            } catch (_: java.io.IOException) {
                ""
            }
            assertFalse(r, r.contains("HTTP/1.1"))
        } finally {
            s.stop()
        }
    }

    @Test fun expires() {
        var now = 1000L
        val s = server(clock = { now })
        val port = s.start()
        try {
            assertEquals(1000L + 30 * 60_000L, s.expiresAt())
            assertTrue(get(port, "/tok/").startsWith("HTTP/1.1 200"))
            now += 30 * 60_000L
            assertFalse(s.isRunning)
            val r = try {
                get(port, "/tok/")
            } catch (_: java.io.IOException) {
                ""
            }
            assertFalse(r.startsWith("HTTP/1.1 200"))
        } finally {
            s.stop()
        }
    }
}
