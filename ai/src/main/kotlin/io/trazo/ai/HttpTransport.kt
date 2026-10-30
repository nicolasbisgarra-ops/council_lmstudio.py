package io.trazo.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutSeconds: Int = 60,
)

class HttpResponse(val status: Int, val body: String)

/** La red, aparte para que las pruebas la reemplacen por respuestas fijas. */
fun interface HttpTransport {
    /** Lanza [AiException] si no hay respuesta; un código de error HTTP sí es una respuesta. */
    fun send(request: HttpRequest): HttpResponse

    /**
     * Como [send], pero entrega el cuerpo de una respuesta buena renglón a renglón, a medida que llega
     * (eventos SSE). Devuelve la respuesta con el cuerpo completo.
     */
    fun stream(request: HttpRequest, onLine: (String) -> Unit): HttpResponse =
        send(request).also { r -> if (r.status in 200..299) r.body.lineSequence().forEach(onLine) }
}

/** Error que se le muestra al usuario tal cual: siempre en español y sin la clave. */
class AiException(message: String, val status: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** Sin dependencias: HttpURLConnection del sistema. */
object UrlConnectionTransport : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse = exchange(request, null)

    override fun stream(request: HttpRequest, onLine: (String) -> Unit): HttpResponse = exchange(request, onLine)

    private fun exchange(request: HttpRequest, onLine: ((String) -> Unit)?): HttpResponse {
        val conn = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("La URL no es válida.", cause = e)
        }
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = (request.timeoutSeconds * 1000).coerceAtMost(15_000)
            conn.readTimeout = request.timeoutSeconds * 1000
            if (request.body != null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Van después: el pedido puede cambiar el tipo (el token OAuth va como formulario).
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val reader = stream?.bufferedReader(Charsets.UTF_8) ?: return HttpResponse(status, "")
            val body = reader.use { r ->
                if (onLine == null || status >= 400) r.readText()
                else buildString { r.lineSequence().forEach { line -> appendLine(line); onLine(line) } }
            }
            return HttpResponse(status, body)
        } catch (e: SocketTimeoutException) {
            throw AiException("El modelo tardó más de ${request.timeoutSeconds} s en responder.", cause = e)
        } catch (e: UnknownHostException) {
            throw AiException("No se encontró el servidor: revise la URL o la conexión a internet.", cause = e)
        } catch (e: IOException) {
            throw AiException("No se pudo conectar con el servidor (${e.message ?: e.javaClass.simpleName}).", cause = e)
        } finally {
            conn.disconnect()
        }
    }
}
