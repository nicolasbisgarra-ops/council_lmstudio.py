package io.trazo.app.sync

import io.trazo.app.ink.PaperColors
import io.trazo.core.share.SharedMessage
import io.trazo.core.share.SharedNote
import io.trazo.core.share.SharedReview
import io.trazo.core.share.WebAccess
import io.trazo.core.share.WebAccessConfig
import io.trazo.core.share.WebHistoryItem
import io.trazo.core.share.WebState
import io.trazo.core.share.WebStateBuilder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWebServerTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9)

    private class FakeHost(var review: SharedReview, private val png: ByteArray) : SyncWebHost {
        var renders = 0
        var lastPaper: PaperColors? = null
        var sent: Pair<String, ByteArray?>? = null
        var restarts = ArrayList<Boolean>()
        var problem: String? = null
        var broken = false
        var models: WebAi? = WebAi(
            listOf(WebAiProfile("a", "Claude", "Claude (Anthropic)", "m1", true), WebAiProfile("b", "Local", "LM Studio", "", false)),
            "a", "medium", listOf("low" to "Baja", "medium" to "Media", "high" to "Alta"),
        )
        var picked: Pair<String?, String?>? = null
        var historyList: List<WebHistoryItem>? = listOf(
            WebHistoryItem("1731273240000", 1731273240000L, 6, "Tu idea de descomponer…")
        )
        var archives = mutableMapOf<String, Pair<Long, List<SharedMessage>>>(
            "1731273240000" to (1731273240000L to listOf(
                SharedMessage(fromStudent = true, text = "hola"),
                SharedMessage(fromStudent = false, text = "chau"),
            ))
        )
        var restored: String? = null
        var deleted: String? = null

        override fun ai(): WebAi? = models
        override fun chooseAi(profileId: String?, quality: String?): String? { if (problem == null) picked = profileId to quality; return problem }
        override fun review(): SharedReview = if (broken) error("ocupado") else review
        override fun pagePng(paper: PaperColors): ByteArray? { renders++; lastPaper = paper; return png }
        override fun chatImage(name: String): ByteArray? = if (name == "foto.jpg" || name == "vieja.jpg") byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1) else null
        override fun send(text: String, image: ByteArray?): String? { if (problem == null) sent = text to image; return problem }
        override fun restart(fresh: Boolean): String? { if (problem == null) restarts += fresh; return problem }
        override fun history(): List<WebHistoryItem>? = if (broken) error("ocupado") else historyList
        override fun historyArchive(id: String): Pair<Long, List<SharedMessage>>? = if (broken) error("ocupado") else archives[id]
        override fun restoreHistory(id: String): String? {
            if (problem != null) return problem
            restored = id
            return if (archives.containsKey(id)) null else "No se encontró esa charla guardada."
        }
        override fun deleteHistory(id: String): String? {
            if (problem != null) return problem
            deleted = id
            archives.remove(id)
            return null
        }
    }

    private val review = SharedReview(
        open = true,
        title = "Análisis · hoja 2",
        pageKey = "p:1",
        messages = listOf(
            SharedMessage(fromStudent = true, text = "¿Está bien?\n<script>alert('x')</script>", image = "foto.jpg"),
            SharedMessage(fromStudent = false, text = "Revisá **el signo** de \$-x\$", line = 2, correct = false, verdict = "Falta un signo"),
        ),
        notes = listOf(SharedNote(3, "signo", "<b>mal</b>", "detalle", "poné +")),
    )
    private var saved = WebAccessConfig()
    private val access = WebAccess({ saved }, { saved = it })
    private var host: FakeHost? = FakeHost(review, png)
    private val assets = mapOf(
        "index.html" to "<!doctype html><title>Trazo</title>",
        "app.js" to "console.log(1)",
        "app.css" to "body{}",
        "sw.js" to "self.addEventListener('fetch', function(){})",
        "manifest.webmanifest" to "{}",
        "icon-192.png" to "PNG",
        "vendor/math/fonts/a.woff2" to "F",
        "vendor/math/LICENSE.txt" to "MIT",
        "secreto.kt" to "no",
    )
    private var allow: (ByteArray) -> Boolean = { true }
    private val server = SyncWebServer({ host }, access, { assets[it]?.toByteArray() }, { allow(it) })

    @After fun tearDown() = server.stop()

    private val post = mapOf("x-trazo" to "1", "content-type" to "application/json", "host" to "192.168.1.60:8787")

    private fun get(path: String, cookie: String? = null, query: Map<String, String> = emptyMap(), extra: Map<String, String> = emptyMap()) =
        server.respond(SyncWebServer.Request("GET", path, query, extra + listOfNotNull(cookie?.let { "cookie" to "otra=1; ${WebAccess.COOKIE}=$it" })))

    private fun post(path: String, body: String, cookie: String? = null, headers: Map<String, String> = post) =
        server.respond(SyncWebServer.Request("POST", path, emptyMap(), headers + listOfNotNull(cookie?.let { "cookie" to "${WebAccess.COOKIE}=$it" }), body.toByteArray()))

    private fun header(r: SyncWebServer.Response, name: String) = r.headers.firstOrNull { it.first == name }?.second

    /** Vincula un navegador y devuelve el valor de su cookie. */
    private fun link(): String {
        val r = post("/api/pair", "{\"code\":\"${access.newCode().code}\"}", headers = post + ("user-agent" to "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0"))
        assertEquals(r.text, 200, r.code)
        assertTrue(r.text.contains("\"name\":\"Chrome · Windows\""))
        val set = header(r, "Set-Cookie")!!
        assertTrue(set, set.endsWith("; Path=/; Max-Age=34560000; HttpOnly; SameSite=Lax"))
        return set.substringAfter('=').substringBefore(';')
    }

    @Test fun servesTheAppToAnyoneButOnlyKnownFiles() {
        val index = get("/")
        assertEquals(200, index.code)
        assertEquals("text/html; charset=utf-8", index.type)
        assertTrue(index.text.contains("<title>Trazo</title>"))
        assertEquals("no-cache", header(index, "Cache-Control"))
        assertNull(header(index, "Set-Cookie"))
        assertEquals(index.text, get("/index.html").text)
        assertEquals("text/javascript; charset=utf-8", get("/app.js").type)
        assertEquals("text/css; charset=utf-8", get("/app.css").type)
        assertEquals("application/manifest+json", get("/manifest.webmanifest").type)
        assertEquals("font/woff2", get("/vendor/math/fonts/a.woff2").type)
        assertEquals("image/png", get("/favicon.ico").type)
        assertEquals("MIT", get("/vendor/math/LICENSE.txt").text)
        for (bad in listOf("/secreto.kt", "/nada.js", "/../app.js", "/vendor/../app.js", "/vendor//math", "/.oculto.js", "/vendor/.x/a.js", "/app.js%00", "/a\\b.js", "app.js", "/" + "a".repeat(300) + ".js", "/api/otra")) {
            assertEquals(bad, 404, get(bad).code)
        }
        // Con la misma versión en el navegador no se manda de nuevo.
        val etag = header(index, "ETag")!!
        val again = get("/", extra = mapOf("if-none-match" to etag))
        assertEquals(304, again.code)
        assertEquals(0, again.body.size)
        assertEquals(200, get("/", extra = mapOf("if-none-match" to "\"otra\"")).code)
    }

    @Test fun apiNeedsALinkedBrowser() {
        for (path in listOf("/api/state", "/api/page.png", "/api/image/foto.jpg")) {
            val r = get(path)
            assertEquals(path, 401, r.code)
            assertTrue(r.text.contains("\"unpaired\":true"))
            assertFalse(r.text.contains("Análisis"))
            assertEquals(401, get(path, cookie = "x.y").code)
        }
        for (path in listOf("/api/chat", "/api/review", "/api/unpair")) assertEquals(path, 401, post(path, "{}").code)
        assertNull(host!!.sent)
    }

    @Test fun pairingThenState() {
        assertEquals(403, post("/api/pair", "{\"code\":\"12345678\"}").code)
        assertTrue(post("/api/pair", "{\"code\":\"12345678\"}").text.contains("\"closed\":true"))
        val code = access.newCode().code
        val wrong = post("/api/pair", "{\"code\":\"${if (code == "11111111") "22222222" else "11111111"}\"}")
        assertEquals(403, wrong.code)
        assertTrue(wrong.text, wrong.text.contains("\"left\":4"))
        assertNull(header(wrong, "Set-Cookie"))
        assertEquals(403, post("/api/pair", "no es json").code)
        val cookie = link()
        val r = get("/api/state", cookie)
        assertEquals(200, r.code)
        assertEquals("application/json; charset=utf-8", r.type)
        assertFalse(r.text.contains("<script"))
        val state = WebStateBuilder.JSON.decodeFromString(WebState.serializer(), r.text)
        assertTrue(state.open)
        assertEquals("Análisis · hoja 2", state.title)
        assertEquals(2, state.messages.size)
        assertEquals("<p>Revisá <strong>el signo</strong> de <span class=\"tex\">-x</span></p>", state.messages[1].html)
        assertEquals("foto.jpg", state.messages[0].image)
        assertNotNull(state.page)
        // Si el navegador ya tiene esa versión, la respuesta es mínima.
        assertEquals("{\"v\":\"${state.v}\",\"same\":true}", get("/api/state", cookie, mapOf("have" to state.v)).text)
        host!!.review = review.copy(busy = true, streaming = "Vamos")
        val busy = WebStateBuilder.JSON.decodeFromString(WebState.serializer(), get("/api/state", cookie, mapOf("have" to state.v)).text)
        assertTrue(busy.busy)
        assertEquals("<p>Vamos</p>", busy.streaming)
        // Abrir la app renueva la cookie del navegador vinculado.
        assertTrue(header(get("/", cookie), "Set-Cookie")!!.startsWith("${WebAccess.COOKIE}=$cookie; "))
        assertNull(header(get("/", "x.y"), "Set-Cookie"))
    }

    @Test fun withoutASheetOrWhenBusy() {
        val cookie = link()
        host!!.broken = true
        assertEquals(503, get("/api/state", cookie).code)
        assertEquals(404, get("/api/page.png", cookie).code)
        host = null
        val closed = WebStateBuilder.JSON.decodeFromString(WebState.serializer(), get("/api/state", cookie).text)
        assertFalse(closed.open)
        assertEquals(SyncWebServer.NO_SHEET, closed.hint)
        assertEquals(404, get("/api/page.png", cookie).code)
        assertEquals(404, get("/api/image/foto.jpg", cookie).code)
        assertEquals(409, post("/api/chat", "{\"text\":\"hola\"}", cookie).code)
        assertEquals(409, post("/api/review", "{}", cookie).code)
    }

    @Test fun pageImageIsDrawnOncePerVersion() {
        val cookie = link()
        val h = host!!
        val first = get("/api/page.png", cookie)
        assertEquals(200, first.code)
        assertEquals("image/png", first.type)
        assertTrue(first.body.contentEquals(png))
        assertEquals(PaperColors.LIGHT, h.lastPaper)
        get("/api/page.png", cookie)
        assertEquals(1, h.renders)
        // Pedido con color: se dibuja con ese color y se guarda por separado.
        val crema = get("/api/page.png", cookie, query = mapOf("paper" to "crema"))
        assertEquals(200, crema.code)
        assertEquals(2, h.renders)
        assertEquals(SyncWebServer.webPaper("crema"), h.lastPaper)
        get("/api/page.png", cookie, query = mapOf("paper" to "crema"))
        assertEquals(2, h.renders)
        val oscuro = get("/api/page.png", cookie, query = mapOf("paper" to "oscuro"))
        assertEquals(200, oscuro.code)
        assertEquals(PaperColors.DARK, h.lastPaper)
        assertEquals(3, h.renders)
        val negro = get("/api/page.png", cookie, query = mapOf("paper" to "negro"))
        assertEquals(200, negro.code)
        assertEquals(PaperColors.BLACK, h.lastPaper)
        assertEquals(4, h.renders)
        assertEquals(400, get("/api/page.png", cookie, query = mapOf("paper" to "desconocido")).code)
        h.review = review.copy(pageKey = "p:2")
        get("/api/page.png", cookie)
        assertEquals(5, h.renders)
        h.review = review.copy(pageKey = null)
        assertEquals(404, get("/api/page.png", cookie).code)
        h.review = SharedReview(open = false, pageKey = "p:2")
        assertEquals(404, get("/api/page.png", cookie).code)
    }

    @Test fun chatImagesOnlyFromThisChat() {
        val cookie = link()
        val ok = get("/api/image/foto.jpg", cookie)
        assertEquals(200, ok.code)
        assertEquals("image/jpeg", ok.type)
        val old = get("/api/image/vieja.jpg", cookie)
        assertEquals(200, old.code)
        for (bad in listOf("otra.jpg", "", ".foto.jpg", "a/b.jpg", "a b.jpg", "x".repeat(200))) assertEquals(bad, 404, get("/api/image/$bad", cookie).code)
    }

    @Test fun sendsMessagesAndPhotos() {
        val cookie = link()
        val h = host!!
        assertEquals(200, post("/api/chat", "{\"text\":\"  ¿y el paso 2?  \"}", cookie).code)
        assertEquals("¿y el paso 2?", h.sent!!.first)
        assertNull(h.sent!!.second)
        val photo = Base64.getEncoder().encodeToString(jpeg)
        assertEquals(200, post("/api/chat", "{\"text\":\"\",\"image\":\"data:image/jpeg;base64,$photo\"}", cookie).code)
        assertEquals("", h.sent!!.first)
        assertTrue(h.sent!!.second!!.contentEquals(jpeg))
        assertEquals(200, post("/api/chat", "{\"text\":\"mirá\",\"image\":\"$photo\"}", cookie).code)
        assertTrue(h.sent!!.second!!.contentEquals(jpeg))
        assertEquals(200, post("/api/chat", "{\"text\":\"sin foto\",\"image\":null}", cookie).code)
        assertNull(h.sent!!.second)
        h.sent = null
        for (bad in listOf("{}", "{\"text\":\"   \"}", "[]", "x", "{\"text\":\"a\",\"image\":\"no es base64 !!\"}", "{\"text\":\"a\",\"image\":\"${Base64.getEncoder().encodeToString("texto".toByteArray())}\"}")) {
            assertEquals(bad, 400, post("/api/chat", bad, cookie).code)
        }
        assertEquals(413, post("/api/chat", "{\"text\":\"${"a".repeat(9000)}\"}", cookie).code)
        assertNull(h.sent)
        h.problem = "El tutor todavía está contestando."
        val busy = post("/api/chat", "{\"text\":\"hola\"}", cookie)
        assertEquals(409, busy.code)
        assertTrue(busy.text.contains("El tutor todavía está contestando."))
    }

    @Test fun asksForAReview() {
        val cookie = link()
        assertEquals(200, post("/api/review", "{\"fresh\":true}", cookie).code)
        assertEquals(200, post("/api/review", "{}", cookie).code)
        assertEquals(listOf(true, false), host!!.restarts)
    }

    @Test fun choosesTheModelFromTheWeb() {
        val cookie = link()
        assertEquals(401, get("/api/ai").code)
        assertEquals(401, post("/api/ai", "{\"profile\":\"b\"}").code)
        val state = get("/api/ai", cookie)
        assertEquals(200, state.code)
        assertTrue(state.text, state.text.contains("\"current\":\"a\"") && state.text.contains("\"ready\":false") && state.text.contains("\"key\":\"high\""))
        assertEquals(200, post("/api/ai", "{\"profile\":\"b\",\"quality\":\"high\"}", cookie).code)
        assertEquals("b" to "high", host!!.picked)
        assertEquals(200, post("/api/ai", "{\"quality\":\"low\"}", cookie).code)
        assertEquals(null to "low", host!!.picked)
        assertEquals(400, post("/api/ai", "{}", cookie).code)
        assertEquals(400, post("/api/ai", "no es json", cookie).code)
        // Un campo de otro tipo no se ignora: no cambia nada a medias.
        assertEquals(400, post("/api/ai", "{\"profile\":\"b\",\"quality\":999}", cookie).code)
        assertEquals(400, post("/api/ai", "{\"profile\":null,\"quality\":\"high\"}", cookie).code)
        assertEquals(400, post("/api/ai", "{\"quality\":\"ultra\"}", cookie).code)
        assertEquals(null to "low", host!!.picked)
        host!!.problem = "Ese modelo ya no existe."
        assertEquals(409, post("/api/ai", "{\"profile\":\"zz\"}", cookie).code)
        host!!.problem = null
        host!!.models = null
        assertEquals(409, get("/api/ai", cookie).code)
    }

    @Test fun onlyOurOwnPageCanPost() {
        val cookie = link()
        val body = "{\"text\":\"hola\"}"
        val cases = listOf(
            post - "x-trazo",
            post + ("x-trazo" to "0"),
            post + ("content-type" to "text/plain"),
            post - "content-type",
            post + ("origin" to "http://malo.example"),
            post + ("origin" to "http://192.168.1.60:9999"),
            post + ("origin" to "http://192.168.1.60:8787") - "host",
            post + ("sec-fetch-site" to "cross-site"),
            post + ("sec-fetch-site" to "same-site"),
        )
        for (h in cases) {
            assertEquals(h.toString(), 403, post("/api/chat", body, cookie, h).code)
            assertEquals(403, post("/api/pair", "{\"code\":\"${access.newCode().code}\"}", headers = h).code)
        }
        assertNull(host!!.sent)
        assertEquals(1, saved.devices.size)
        assertEquals(200, post("/api/chat", body, cookie, post + ("origin" to "http://192.168.1.60:8787") + ("sec-fetch-site" to "same-origin")).code)
        assertEquals("hola", host!!.sent!!.first)
        assertEquals(404, post("/api/state", "{}", cookie).code)
        assertEquals(404, post("/", "{}", cookie).code)
    }

    @Test fun unpairForgetsTheBrowser() {
        val cookie = link()
        val other = link()
        val r = post("/api/unpair", "{}", cookie)
        assertEquals(200, r.code)
        assertTrue(header(r, "Set-Cookie")!!.contains("Max-Age=0"))
        assertEquals(401, get("/api/state", cookie).code)
        assertEquals(200, get("/api/state", other).code)
        access.forgetAll()
        assertEquals(401, get("/api/state", other).code)
    }

    // ------------------------------------------------------------------ por la red

    private fun raw(port: Int, request: String, body: ByteArray = ByteArray(0)): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 4000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().write(body)
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

    private fun http(port: Int, method: String, path: String, headers: String = "", body: String = ""): String {
        val bytes = body.toByteArray()
        val length = if (method == "POST") "Content-Length: ${bytes.size}\r\n" else ""
        return raw(port, "$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$headers$length\r\n", bytes)
    }

    @Test fun worksOverSockets() {
        val port = server.start()
        assertTrue(server.isRunning)
        assertEquals(port, server.port)
        assertEquals(port, server.start())
        val index = http(port, "GET", "/")
        assertTrue(index, index.startsWith("HTTP/1.1 200 OK"))
        for (h in listOf(
            "Content-Type: text/html; charset=utf-8", "X-Content-Type-Options: nosniff", "X-Frame-Options: DENY", "Referrer-Policy: no-referrer",
            "Cross-Origin-Resource-Policy: same-origin", "Content-Security-Policy: default-src 'none'; script-src 'self';", "Cache-Control: no-cache", "Connection: close",
        )) assertTrue(h, index.contains(h))
        assertTrue(index.endsWith("<title>Trazo</title>"))
        val head = http(port, "HEAD", "/")
        assertTrue(head.startsWith("HTTP/1.1 200"))
        assertFalse(head.contains("<title>"))
        val etag = Regex("ETag: (\\S+)").find(index)!!.groupValues[1]
        val cached = http(port, "GET", "/", "If-None-Match: $etag\r\n")
        assertTrue(cached, cached.startsWith("HTTP/1.1 304"))
        assertFalse(cached.contains("Content-Length"))
        assertTrue(http(port, "GET", "/api/state").startsWith("HTTP/1.1 401"))
        assertTrue(http(port, "GET", "/no-esta.js").startsWith("HTTP/1.1 404"))
        val put = http(port, "PUT", "/")
        assertTrue(put.startsWith("HTTP/1.1 405"))
        assertTrue(put.contains("Allow: GET, HEAD, POST"))

        // Vincular y charlar, de punta a punta.
        val json = "X-Trazo: 1\r\nContent-Type: application/json\r\nOrigin: http://127.0.0.1:$port\r\n"
        val paired = http(port, "POST", "/api/pair", json + "User-Agent: Mozilla/5.0 (Linux; Android 14) Chrome/130.0\r\n", "{\"code\":\"${access.newCode().code}\"}")
        assertTrue(paired, paired.startsWith("HTTP/1.1 200"))
        val cookie = Regex("Set-Cookie: (${WebAccess.COOKIE}=[^;]+);").find(paired)!!.groupValues[1]
        assertEquals("Chrome · Android", saved.devices.single().name)
        val state = http(port, "GET", "/api/state?have=nada&x=1", "Cookie: $cookie\r\n")
        assertTrue(state, state.startsWith("HTTP/1.1 200"))
        assertTrue(state.contains("Cache-Control: no-store"))
        assertTrue(state.contains("\"title\":\"Análisis · hoja 2\""))
        val image = http(port, "GET", "/api/page.png?v=1", "Cookie: $cookie\r\n")
        assertTrue(image.contains("Content-Type: image/png"))
        assertTrue(image.contains("Content-Length: ${png.size}"))
        val sent = http(port, "POST", "/api/chat", json + "Cookie: $cookie\r\n", "{\"text\":\"¿por qué diverge?\"}")
        assertTrue(sent, sent.startsWith("HTTP/1.1 200"))
        assertEquals("¿por qué diverge?", host!!.sent!!.first)
        assertTrue(http(port, "POST", "/api/chat", "X-Trazo: 1\r\nContent-Type: application/json\r\nOrigin: http://otro.example\r\nCookie: $cookie\r\n", "{\"text\":\"x\"}").startsWith("HTTP/1.1 403"))

        // Pedidos mal formados.
        assertTrue(raw(port, "POST /api/chat HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 411"))
        assertTrue(raw(port, "POST /api/chat HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 411"))
        assertTrue(raw(port, "POST /api/pair HTTP/1.1\r\nHost: x\r\nContent-Length: 99999\r\n\r\n").startsWith("HTTP/1.1 413"))
        assertTrue(raw(port, "POST /api/chat HTTP/1.1\r\nHost: x\r\nContent-Length: 99999999\r\n\r\n").startsWith("HTTP/1.1 413"))
        assertEquals("", raw(port, "GET /\r\n\r\n"))
        assertEquals("", raw(port, "GET / HTTP/1.1\r\n" + "X: ${"a".repeat(5000)}\r\n\r\n"))
        assertTrue(server.awaitIdle(3000))
        server.stop()
        assertFalse(server.isRunning)
        assertEquals(0, server.port)
    }

    @Test fun refusesOtherAddresses() {
        allow = { false }
        val port = server.start()
        val r = try {
            http(port, "GET", "/")
        } catch (_: SocketTimeoutException) {
            "timeout"
        } catch (_: java.io.IOException) {
            ""
        }
        assertFalse(r, r.contains("HTTP/1.1"))
    }

    @Test fun urls() {
        assertEquals("http://192.168.1.5:8787/", SyncWebServer.url("192.168.1.5", 8787))
        assertEquals("http://[fd7a::1]:80/", SyncWebServer.url("fd7a::1%wlan0", 80))
        assertEquals("http://100.80.1.2:8787/#c=12345678", SyncWebServer.pairUrl("100.80.1.2", 8787, "12345678"))
        assertEquals("image/png", SyncWebServer.imageType(png))
        assertEquals("image/jpeg", SyncWebServer.imageType(jpeg))
        assertEquals("image/webp", SyncWebServer.imageType("RIFF0000WEBPVP8 ".toByteArray()))
        assertEquals("image/gif", SyncWebServer.imageType("GIF89a".toByteArray()))
        assertNull(SyncWebServer.imageType("<svg onload=alert(1)>".toByteArray()))
        assertNull(SyncWebServer.imageType(ByteArray(0)))
    }

    @Test fun historyEndpoints() {
        val cookie = link()
        val h = host!!
        // GET /api/history
        val list = get("/api/history", cookie)
        assertEquals(200, list.code)
        assertTrue(list.text.contains("\"items\""))
        assertTrue(list.text.contains("\"1731273240000\""))
        assertTrue(list.text.contains("Tu idea de descomponer…"))
        // GET /api/history/<id>
        val one = get("/api/history/1731273240000", cookie)
        assertEquals(200, one.code)
        assertTrue(one.text.contains("\"messages\""))
        assertTrue(one.text.contains("hola"))
        assertEquals(404, get("/api/history/99999999", cookie).code)
        // ID inválido: 400
        // Una ruta con «..» ni llega a mirarse.
        assertEquals(404, get("/api/history/../1", cookie).code)
        for (badId in listOf("abc", "12a", "12345678901234567", "../1", "-1")) {
            if (".." !in badId) assertEquals(badId, 400, get("/api/history/$badId", cookie).code)
            assertEquals(badId, 400, post("/api/history/restore", "{\"id\":\"$badId\"}", cookie).code)
            assertEquals(badId, 400, post("/api/history/delete", "{\"id\":\"$badId\"}", cookie).code)
        }
        // POST /api/history/restore
        assertEquals(200, post("/api/history/restore", "{\"id\":\"1731273240000\"}", cookie).code)
        assertEquals("1731273240000", h.restored)
        // POST /api/history/delete
        assertEquals(200, post("/api/history/delete", "{\"id\":\"1731273240000\"}", cookie).code)
        assertEquals("1731273240000", h.deleted)
        assertFalse(h.archives.containsKey("1731273240000"))
        assertEquals(404, get("/api/history/1731273240000", cookie).code)
        // Sin hoja
        host = null
        assertEquals(409, get("/api/history", cookie).code)
        assertEquals(404, get("/api/history/1731273240000", cookie).code)
        assertEquals(409, post("/api/history/restore", "{\"id\":\"1731273240000\"}", cookie).code)
        assertEquals(409, post("/api/history/delete", "{\"id\":\"1731273240000\"}", cookie).code)
    }

    @Test fun servesSwWithAllowedHeaderAndWorkerCsp() {
        val sw = get("/sw.js")
        assertEquals(200, sw.code)
        assertEquals("text/javascript; charset=utf-8", sw.type)
        assertEquals("/", header(sw, "Service-Worker-Allowed"))
        // La política va en todas las respuestas, al escribirlas en el socket.
        val port = server.start()
        try {
            val raw = http(port, "GET", "/sw.js")
            assertTrue(raw, raw.contains("worker-src 'self'"))
            assertTrue(raw, raw.contains("Service-Worker-Allowed: /"))
        } finally { server.stop() }
        val manifest = get("/manifest.webmanifest")
        assertEquals(200, manifest.code)
        assertEquals("application/manifest+json", manifest.type)
    }
}
