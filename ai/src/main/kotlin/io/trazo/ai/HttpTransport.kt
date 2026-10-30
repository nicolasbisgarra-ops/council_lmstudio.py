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
}

/** Error que se le muestra al usuario tal cual: siempre en español y sin la clave. */
class AiException(message: String, val status: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** Sin dependencias: HttpURLConnection del sistema. */
object UrlConnectionTransport : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val conn = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("La URL no es válida.", cause = e)
        }
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = (request.timeoutSeconds * 1000).coerceAtMost(15_000)
            conn.readTimeout = request.timeoutSeconds * 1000
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
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
