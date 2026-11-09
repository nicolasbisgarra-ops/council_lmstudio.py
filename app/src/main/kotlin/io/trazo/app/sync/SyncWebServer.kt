package io.trazo.app.sync

import io.trazo.core.share.ReviewWebPage
import io.trazo.core.share.SharedReview
import io.trazo.core.sync.SyncAddresses
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Servidor HTTP mínimo y de solo lectura para ver la revisión de una hoja desde otro dispositivo de la
 * misma red. Solo atiende GET/HEAD bajo `/<código>/`, solo a direcciones de la red de casa o Tailscale,
 * y se apaga solo al pasar [ttlMillis]. Cada pedido arma la página en el momento con [review] y [pagePng].
 */
class ReviewWebServer(
    private val review: () -> SharedReview,
    private val pagePng: () -> ByteArray?,
    val token: String = newToken(),
    private val ttlMillis: Long = 30 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowAddress: (ByteArray) -> Boolean = SyncAddresses::isAllowed,
    private val refreshSeconds: Int = 15,
) {
    sealed interface Route {
        object Page : Route
        object Image : Route
        object NotFound : Route
        object BadMethod : Route
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var startedAt = 0L
    private val slots = Semaphore(MAX_CLIENTS)
    private val pool = Executors.newFixedThreadPool(MAX_CLIENTS) { r -> Thread(r, "trazo-web").apply { isDaemon = true } }
    private val tokenBytes = token.toByteArray(Charsets.UTF_8)

    val isRunning: Boolean get() = server?.isClosed == false && !expired()

    fun expiresAt(): Long = startedAt + ttlMillis

    private fun expired() = clock() >= expiresAt()

    /** Arranca en [port] (0 = cualquiera libre) y devuelve el puerto real. */
    @Synchronized
    fun start(port: Int = 0): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port), 16)
        s.soTimeout = 1000
        startedAt = clock()
        server = s
        Thread({ acceptLoop(s) }, "trazo-web-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            if (expired()) {
                stop()
                return
            }
            val c = try {
                s.accept()
            } catch (_: SocketTimeoutException) {
                null
            } catch (_: Exception) {
                return
            }
            if (c == null) continue
            if (!allowAddress(c.inetAddress.address) || !slots.tryAcquire()) {
                runCatching { c.close() }
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(c)
                    } finally {
                        slots.release()
                    }
                }
            } catch (_: Exception) {
                slots.release()
                runCatching { c.close() }
            }
        }
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            runCatching {
                sock.soTimeout = 5000
                val input = sock.getInputStream()
                val requestLine = readLine(input, MAX_LINE) ?: return
                var total = requestLine.length
                var headers = 0
                while (true) {
                    val h = readLine(input, MAX_LINE) ?: return
                    if (h.isEmpty()) break
                    total += h.length
                    headers++
                    if (total > MAX_HEAD || headers > MAX_HEADERS) return
                }
                val parts = requestLine.split(' ')
                if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return
                val method = parts[0]
                val out = sock.getOutputStream()
                if (expired()) {
                    respond(out, 404, "text/plain; charset=utf-8", NOT_FOUND, method == "HEAD")
                    stop()
                    return
                }
                when (route(method, parts[1])) {
                    Route.BadMethod -> respond(out, 405, "text/plain; charset=utf-8", "Método no permitido".toByteArray(), false, allow = true)
                    Route.NotFound -> respond(out, 404, "text/plain; charset=utf-8", NOT_FOUND, method == "HEAD")
                    Route.Page -> {
                        val body = runCatching { ReviewWebPage.html(review(), token, refreshSeconds).toByteArray(Charsets.UTF_8) }.getOrNull()
                        if (body == null) respond(out, 500, "text/plain; charset=utf-8", ERROR, method == "HEAD")
                        else respond(out, 200, "text/html; charset=utf-8", body, method == "HEAD")
                    }
                    Route.Image -> {
                        val png = runCatching { pagePng() }
                        val body = png.getOrNull()
                        when {
                            png.isFailure -> respond(out, 500, "text/plain; charset=utf-8", ERROR, method == "HEAD")
                            body == null -> respond(out, 404, "text/plain; charset=utf-8", NOT_FOUND, method == "HEAD")
                            else -> respond(out, 200, "image/png", body, method == "HEAD")
                        }
                    }
                }
            }
        }
    }

    /** Qué responder a [method] y [path], sin sockets (para los tests). */
    internal fun route(method: String, path: String): Route {
        if (method != "GET" && method != "HEAD") return Route.BadMethod
        val clean = path.substringBefore('?').substringBefore('#')
        if (!clean.startsWith("/")) return Route.NotFound
        val rest = clean.substring(1)
        val given = rest.substringBefore('/')
        if (!MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), tokenBytes)) return Route.NotFound
        return when (rest.substring(given.length)) {
            "", "/" -> Route.Page
            "/page.png" -> Route.Image
            else -> Route.NotFound
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, head: Boolean, allow: Boolean = false) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val h = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("X-Frame-Options: DENY\r\n")
            .append("Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'unsafe-inline'\r\n")
        if (allow) h.append("Allow: GET, HEAD\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(body)
        out.flush()
    }

    /** Lee una línea terminada en CRLF o LF; null si se corta o pasa de [max] bytes. */
    private fun readLine(input: InputStream, max: Int): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = input.read()
            if (x < 0) return null
            if (x == '\n'.code) break
            if (b.size() >= max) return null
            b.write(x)
        }
        val s = b.toString(Charsets.ISO_8859_1.name())
        return if (s.endsWith("\r")) s.dropLast(1) else s
    }

    /** Espera a que terminen los pedidos en curso (para los tests). */
    internal fun awaitIdle(millis: Long): Boolean {
        val ok = slots.tryAcquire(MAX_CLIENTS, millis, TimeUnit.MILLISECONDS)
        if (ok) slots.release(MAX_CLIENTS)
        return ok
    }

    companion object {
        const val MAX_CLIENTS = 4
        private const val MAX_LINE = 2048
        private const val MAX_HEAD = 8192
        private const val MAX_HEADERS = 50
        private val NOT_FOUND = "No encontrado".toByteArray()
        private val ERROR = "Error".toByteArray()
        private val random = SecureRandom()

        /** La URL para abrir desde otro dispositivo; las IPv6 van entre corchetes y sin zona. */
        fun url(host: String, port: Int, token: String): String {
            val h = host.substringBefore('%')
            val shown = if (':' in h) "[$h]" else h
            return "http://$shown:$port/$token/"
        }

        /** 128 bits al azar en base64url sin relleno (22 caracteres). */
        fun newToken(): String {
            val b = ByteArray(16)
            random.nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}
