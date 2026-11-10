package io.trazo.app.sync

import io.trazo.core.share.SharedReview
import io.trazo.core.share.WebAccess
import io.trazo.core.share.WebStateBuilder
import io.trazo.core.sync.SyncAddresses
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Un modelo de IA visto desde la web: sin claves ni direcciones. [ready] = ya se puede usar tal cual. */
data class WebAiProfile(val id: String, val name: String, val provider: String, val model: String, val ready: Boolean)

/** Lo que la web muestra para elegir: los modelos, cuál se usa y la calidad de la foto que se le manda. */
data class WebAi(val profiles: List<WebAiProfile>, val current: String?, val quality: String, val qualities: List<Pair<String, String>>)

/** Lo que la hoja abierta le ofrece a la web. Las llamadas llegan desde los hilos del servidor. */
interface SyncWebHost {
    fun review(): SharedReview

    /** Los modelos que se pueden elegir desde la web; null si no están disponibles ahora. */
    fun ai(): WebAi? = null

    /** Elige el modelo por [profileId] y/o la calidad de la foto ([quality]: low, medium o high). Devuelve null si salió, o por qué no. */
    fun chooseAi(profileId: String?, quality: String?): String? = "Los modelos no están disponibles."

    /** La hoja con las marcas de la IA, como PNG; null si no hay hoja. */
    fun pagePng(): ByteArray?

    /** Una imagen adjunta del chat de esta hoja; null si [name] no es de este chat. */
    fun chatImage(name: String): ByteArray?

    /** Manda un mensaje del estudiante (con una foto opcional). Devuelve null si salió, o por qué no. */
    fun send(text: String, image: ByteArray?): String?

    /** Pide la revisión de la hoja; con [fresh] borra antes la conversación. Devuelve null si salió. */
    fun restart(fresh: Boolean): String?
}

/**
 * La web de revisión: un servidor HTTP mínimo que atiende solo a la red de casa o Tailscale. Sirve la app
 * web (archivos fijos de `assets/web`, sin datos) y, solo a los navegadores vinculados ([WebAccess]), el
 * estado de la hoja abierta, su imagen y el chat con el tutor. Lo que cambia algo va por POST, con
 * controles de origen para que otra página abierta en el mismo navegador no pueda pedirlo.
 */
class SyncWebServer(
    private val host: () -> SyncWebHost?,
    private val access: WebAccess,
    private val asset: (String) -> ByteArray?,
    private val allowAddress: (ByteArray) -> Boolean = SyncAddresses::isAllowed,
) {
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    class Response(val code: Int, val type: String, val body: ByteArray, val headers: List<Pair<String, String>> = emptyList()) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private class Static(val body: ByteArray, val etag: String)

    @Volatile private var server: ServerSocket? = null
    /** Conexiones aceptadas (atendidas o en espera): un navegador abre varias a la vez al cargar la app. */
    private val slots = Semaphore(MAX_PENDING)
    private val pool = Executors.newFixedThreadPool(MAX_CLIENTS) { r -> Thread(r, "trazo-web").apply { isDaemon = true } }
    private val builder = WebStateBuilder()
    private val statics = ConcurrentHashMap<String, Static>()
    private val pngLock = Any()
    private var pngKey: String? = null
    private var png: ByteArray? = null
    @Volatile private var lastState: Pair<String, ByteArray>? = null

    val isRunning: Boolean get() = server?.isClosed == false

    /** El puerto en que atiende, o 0 si está apagado. */
    val port: Int get() = server?.takeIf { !it.isClosed }?.localPort ?: 0

    /** Arranca en [port] (0 = cualquiera libre) y devuelve el puerto real. Lanza si el puerto está ocupado. */
    @Synchronized
    fun start(port: Int = 0): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port), 32)
            s.soTimeout = 1000
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        server = s
        Thread({ acceptLoop(s) }, "trazo-web-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    /** Deja de atender. El servidor no se vuelve a usar: para prenderlo de nuevo se crea otro. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
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
                sock.soTimeout = HEAD_TIMEOUT
                val input = sock.getInputStream()
                val out = sock.getOutputStream()
                val requestLine = readLine(input, MAX_LINE) ?: return
                val headers = HashMap<String, String>()
                var total = requestLine.length
                while (true) {
                    val h = readLine(input, MAX_LINE) ?: return
                    if (h.isEmpty()) break
                    total += h.length
                    if (total > MAX_HEAD || headers.size >= MAX_HEADERS) return
                    val colon = h.indexOf(':')
                    if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return
                val method = parts[0]
                val head = method == "HEAD"
                if (method != "GET" && method != "HEAD" && method != "POST") {
                    write(out, plain(405, "Método no permitido", listOf("Allow" to "GET, HEAD, POST")), false)
                    return
                }
                val target = parts[1]
                val path = target.substringBefore('?').substringBefore('#')
                var body = ByteArray(0)
                if (method == "POST") {
                    if (headers.containsKey("transfer-encoding")) { write(out, plain(411, "Falta el tamaño"), false); return }
                    val length = headers["content-length"]?.toLongOrNull()
                    if (length == null || length < 0) { write(out, plain(411, "Falta el tamaño"), false); return }
                    if (length > maxBody(path)) { write(out, json(413, fail("Es demasiado grande.")), false); return }
                    sock.soTimeout = BODY_TIMEOUT
                    body = readFully(input, length.toInt())
                    if (body.size.toLong() != length) return
                }
                val response = try {
                    respond(Request(method, path, query(target), headers, body))
                } catch (_: Throwable) {
                    plain(500, "Error")
                }
                write(out, response, head)
            }
        }
    }

    /** `InputStream.readNBytes(int)` recién existe en Android 13; esto lee hasta [n] bytes o el fin del flujo. */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun maxBody(path: String): Long = if (path == "/api/chat") MAX_CHAT_BODY else MAX_SMALL_BODY

    /** Qué contestar a [req], sin sockets (también para las pruebas). */
    internal fun respond(req: Request): Response {
        val path = req.path
        if (!path.startsWith("/") || ".." in path || "//" in path || '\\' in path || '%' in path || path.length > MAX_PATH) return notFound()
        if (req.method == "POST") {
            if (!sameOrigin(req)) return json(403, fail("Pedido de otro origen."))
            return when (path) {
                "/api/pair" -> pair(req)
                "/api/chat" -> authorized(req) { chat(req) }
                "/api/review" -> authorized(req) { review(req) }
                "/api/ai" -> authorized(req) { aiChoose(req) }
                "/api/unpair" -> authorized(req) { device ->
                    access.forget(device.id)
                    json(200, OK, listOf("Set-Cookie" to "${WebAccess.COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"))
                }
                else -> notFound()
            }
        }
        return when {
            path == "/api/state" -> authorized(req) { state(req) }
            path == "/api/page.png" -> authorized(req) { pagePng() }
            path == "/api/ai" -> authorized(req) { aiState() }
            path.startsWith("/api/image/") -> authorized(req) { image(path.removePrefix("/api/image/")) }
            path.startsWith("/api/") -> notFound()
            else -> static(req)
        }
    }

    // ------------------------------------------------------------------ quién entra

    private fun cookie(req: Request): String? =
        req.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith(WebAccess.COOKIE + "=") }?.substringAfter('=')

    private fun authorized(req: Request, then: (io.trazo.core.share.WebDevice) -> Response): Response {
        val device = access.authenticate(cookie(req)) ?: return json(401, buildJsonObject { put("ok", false); put("unpaired", true) }.toString())
        return then(device)
    }

    private fun sessionCookie(value: String) =
        "Set-Cookie" to "${WebAccess.COOKIE}=$value; Path=/; Max-Age=${WebAccess.COOKIE_SECONDS}; HttpOnly; SameSite=Lax"

    /**
     * Un POST solo vale si lo hizo nuestra propia página: lleva el encabezado `X-Trazo` (que otra página no
     * puede poner sin permiso) y, cuando el navegador los manda, `Origin` y `Sec-Fetch-Site` coinciden.
     */
    private fun sameOrigin(req: Request): Boolean {
        if (req.headers["x-trazo"] != "1") return false
        if (req.headers["content-type"]?.startsWith("application/json") != true) return false
        req.headers["sec-fetch-site"]?.let { if (it != "same-origin") return false }
        val origin = req.headers["origin"] ?: return true
        val host = req.headers["host"] ?: return false
        return origin.equals("http://$host", ignoreCase = true)
    }

    private fun pair(req: Request): Response {
        val code = (body(req)?.get("code") as? JsonPrimitive)?.content.orEmpty()
        return when (val r = access.pair(code, req.headers["user-agent"])) {
            is WebAccess.Pairing.Linked -> json(200, buildJsonObject { put("ok", true); put("name", r.device.name) }.toString(), listOf(sessionCookie(r.cookie)))
            is WebAccess.Pairing.Wrong -> json(
                403,
                buildJsonObject {
                    put("ok", false)
                    put("left", r.triesLeft)
                    put("error", if (r.triesLeft > 0) "Ese código no es. Fijate en la pantalla de Trazo." else "Demasiados intentos: pedí un código nuevo en Trazo.")
                }.toString(),
            )
            WebAccess.Pairing.Closed -> json(
                403,
                buildJsonObject { put("ok", false); put("closed", true); put("error", "No hay un código vigente. Tocá «Ver en otro dispositivo» en Trazo.") }.toString(),
            )
        }
    }

    // ------------------------------------------------------------------ la hoja y el chat

    /** Lo que hay para mostrar; null si la app no llegó a contestar (está ocupada): el navegador reintenta. */
    private fun currentReview(): SharedReview? {
        val h = host() ?: return SharedReview(open = false, hint = NO_SHEET)
        return runCatching { h.review() }.getOrNull()
    }

    private fun state(req: Request): Response {
        val review = currentReview() ?: return json(503, fail("Trazo está ocupado."))
        val version = builder.version(review)
        if (req.query["have"] == version) return json(200, WebStateBuilder.same(version))
        val cached = lastState
        val bytes = if (cached != null && cached.first == version) cached.second
        else builder.json(builder.state(review, version)).toByteArray(Charsets.UTF_8).also { lastState = version to it }
        return Response(200, JSON_TYPE, bytes)
    }

    private fun pagePng(): Response {
        val h = host() ?: return notFound()
        val key = runCatching { h.review() }.getOrNull()?.takeIf { it.open }?.pageKey ?: return notFound()
        // Un dibujo a la vez: dos navegadores mirando la misma hoja comparten el mismo PNG.
        val bytes = synchronized(pngLock) {
            if (pngKey == key && png != null) png
            else runCatching { h.pagePng() }.getOrNull().also { pngKey = key; png = it }
        } ?: return notFound()
        return Response(200, "image/png", bytes)
    }

    private fun image(name: String): Response {
        if (name.isEmpty() || name.length > 120 || !name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } || name.startsWith(".")) return notFound()
        val bytes = host()?.let { h -> runCatching { h.chatImage(name) }.getOrNull() } ?: return notFound()
        val type = imageType(bytes) ?: return notFound()
        return Response(200, type, bytes, listOf("Cache-Control" to "private, max-age=3600"))
    }

    private fun chat(req: Request): Response {
        val o = body(req) ?: return json(400, fail("No se entendió el mensaje."))
        val text = (o["text"] as? JsonPrimitive)?.content.orEmpty().trim()
        if (text.length > MAX_TEXT) return json(413, fail("El mensaje es demasiado largo."))
        val image = (o["image"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { data ->
            runCatching { Base64.getMimeDecoder().decode(data.substringAfter("base64,", data)) }.getOrNull()?.takeIf { it.isNotEmpty() && imageType(it) != null }
                ?: return json(400, fail("No se pudo leer la imagen."))
        }
        if (text.isEmpty() && image == null) return json(400, fail("Escribí un mensaje."))
        val h = host() ?: return json(409, fail(NO_SHEET))
        val problem = runCatching { h.send(text, image) }.getOrElse { "No se pudo mandar el mensaje." }
        return if (problem == null) json(200, OK) else json(409, fail(problem))
    }

    private fun review(req: Request): Response {
        val fresh = (body(req)?.get("fresh") as? JsonPrimitive)?.booleanOrNull ?: false
        val h = host() ?: return json(409, fail(NO_SHEET))
        val problem = runCatching { h.restart(fresh) }.getOrElse { "No se pudo pedir la revisión." }
        return if (problem == null) json(200, OK) else json(409, fail(problem))
    }

    // ------------------------------------------------------------------ el modelo de IA

    private fun aiState(): Response {
        val h = host() ?: return json(409, fail(NO_SHEET))
        val ai = try {
            h.ai()
        } catch (_: Throwable) {
            return json(503, fail("Trazo está ocupado."))
        } ?: return json(409, fail(AI_UNAVAILABLE))
        return json(
            200,
            buildJsonObject {
                put("ok", true)
                put("current", ai.current)
                put("quality", ai.quality)
                putJsonArray("profiles") {
                    ai.profiles.forEach { p ->
                        addJsonObject { put("id", p.id); put("name", p.name); put("provider", p.provider); put("model", p.model); put("ready", p.ready) }
                    }
                }
                putJsonArray("qualities") { ai.qualities.forEach { (key, label) -> addJsonObject { put("key", key); put("label", label) } } }
            }.toString(),
        )
    }

    private fun aiChoose(req: Request): Response {
        val o = body(req) ?: return json(400, fail("No se entendió el pedido."))
        // Un campo presente con otro tipo (número, objeto, null) es un pedido mal hecho: no se ignora en silencio.
        fun field(name: String, max: Int): String? {
            val v = o[name] ?: return null
            val p = v as? JsonPrimitive
            if (p == null || !p.isString) throw IllegalArgumentException(name)
            return p.content.take(max)
        }
        val profile = try { field("profile", 80) } catch (_: IllegalArgumentException) { return json(400, fail("El modelo tiene que ser un texto.")) }
        val quality = try { field("quality", 20) } catch (_: IllegalArgumentException) { return json(400, fail("La calidad tiene que ser un texto.")) }
        if (profile == null && quality == null) return json(400, fail("Elegí un modelo o una calidad."))
        val h = host() ?: return json(409, fail(NO_SHEET))
        // La calidad tiene que ser una de las que se ofrecen: un valor inventado es un pedido mal hecho (400).
        if (quality != null && h.ai()?.qualities?.any { it.first == quality } == false) return json(400, fail("Esa calidad no existe."))
        val problem = runCatching { h.chooseAi(profile, quality) }.getOrElse { "No se pudo cambiar el modelo." }
        return if (problem == null) json(200, OK) else json(409, fail(problem))
    }

    private fun body(req: Request): JsonObject? =
        runCatching { Json.parseToJsonElement(req.body.toString(Charsets.UTF_8)).jsonObject }.getOrNull()

    // ------------------------------------------------------------------ los archivos de la app web

    private fun static(req: Request): Response {
        val name = when (val p = req.path.removePrefix("/")) {
            "", "index.html" -> "index.html"
            "favicon.ico" -> "icon-192.png"
            else -> p
        }
        if (!name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == '/' } || name.split('/').any { it.isEmpty() || it.startsWith(".") }) return notFound()
        val type = TYPES[name.substringAfterLast('.', "")] ?: return notFound()
        val file = statics[name] ?: run {
            val bytes = runCatching { asset(name) }.getOrNull() ?: return notFound()
            Static(bytes, "\"" + sha256(bytes).take(20) + "\"").also { statics[name] = it }
        }
        val headers = ArrayList<Pair<String, String>>()
        headers += "Cache-Control" to "no-cache"
        headers += "ETag" to file.etag
        // Cada vez que un navegador vinculado abre la app, su cookie vuelve a durar lo máximo.
        if (name == "index.html") cookie(req)?.let { value -> if (access.authenticate(value) != null) headers += sessionCookie(value) }
        if (req.headers["if-none-match"] == file.etag) return Response(304, type, ByteArray(0), headers)
        return Response(200, type, file.body, headers)
    }

    // ------------------------------------------------------------------ HTTP

    private fun json(code: Int, body: String, headers: List<Pair<String, String>> = emptyList()) =
        Response(code, JSON_TYPE, body.toByteArray(Charsets.UTF_8), headers)

    private fun plain(code: Int, text: String, headers: List<Pair<String, String>> = emptyList()) =
        Response(code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), headers)

    private fun notFound() = plain(404, "No encontrado")

    private fun fail(message: String): String = buildJsonObject { put("ok", false); put("error", message) }.toString()

    private fun write(out: OutputStream, r: Response, head: Boolean) {
        val reason = when (r.code) {
            200 -> "OK"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            503 -> "Service Unavailable"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        val h = StringBuilder()
            .append("HTTP/1.1 ").append(r.code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(r.type).append("\r\n")
        // Un 304 no lleva cuerpo ni dice cuánto mide: el navegador usa la copia que ya tiene.
        if (r.code != 304) h.append("Content-Length: ").append(r.body.size).append("\r\n")
        h.append("X-Content-Type-Options: nosniff\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("X-Frame-Options: DENY\r\n")
            .append("Cross-Origin-Resource-Policy: same-origin\r\n")
            .append("Content-Security-Policy: ").append(CSP).append("\r\n")
        if (r.headers.none { it.first == "Cache-Control" }) h.append("Cache-Control: no-store\r\n")
        for ((name, value) in r.headers) h.append(name).append(": ").append(value).append("\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head && r.code != 304) out.write(r.body)
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

    private fun query(target: String): Map<String, String> {
        val q = target.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in q.split('&').take(16)) {
            val name = pair.substringBefore('=')
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            if (name.isNotEmpty()) out[name] = value
        }
        return out
    }

    /** Espera a que terminen los pedidos en curso (para las pruebas). */
    internal fun awaitIdle(millis: Long): Boolean {
        val ok = slots.tryAcquire(MAX_PENDING, millis, TimeUnit.MILLISECONDS)
        if (ok) slots.release(MAX_PENDING)
        return ok
    }

    companion object {
        const val MAX_CLIENTS = 8
        private const val MAX_PENDING = 64
        /** El puerto de siempre, para que la dirección no cambie entre un día y otro. */
        const val DEFAULT_PORT = 8787
        const val NO_SHEET = "Abrí una hoja en Trazo para verla acá."
        const val AI_UNAVAILABLE = "Los modelos no están disponibles ahora."
        private const val MAX_LINE = 4096
        private const val MAX_HEAD = 16384
        private const val MAX_HEADERS = 60
        private const val MAX_PATH = 200
        private const val MAX_TEXT = 8000
        private const val MAX_SMALL_BODY = 4096L
        /** Un mensaje con una foto ya achicada por el navegador (en base64). */
        private const val MAX_CHAT_BODY = 16L * 1024 * 1024
        private const val HEAD_TIMEOUT = 8000
        private const val BODY_TIMEOUT = 20000
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val OK = "{\"ok\":true}"
        private const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; " +
            "font-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        private val TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "webmanifest" to "application/manifest+json",
            "png" to "image/png",
            "svg" to "image/svg+xml",
            "woff2" to "font/woff2",
            "txt" to "text/plain; charset=utf-8",
        )

        /** La URL para abrir desde otro dispositivo; las IPv6 van entre corchetes y sin zona. */
        fun url(host: String, port: Int): String {
            val h = host.substringBefore('%')
            val shown = if (':' in h) "[$h]" else h
            return "http://$shown:$port/"
        }

        /** La URL del QR: abre la app web y la vincula sola con [code] (va después del «#», no viaja en el pedido). */
        fun pairUrl(host: String, port: Int, code: String): String = url(host, port) + "#c=" + code

        /** El tipo de una imagen por sus primeros bytes; null si no es PNG, JPEG, WebP ni GIF. */
        fun imageType(b: ByteArray): String? = when {
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            b.size >= 6 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
            else -> null
        }

        private fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
