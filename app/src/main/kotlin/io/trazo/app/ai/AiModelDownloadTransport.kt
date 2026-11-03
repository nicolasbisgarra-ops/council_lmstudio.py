package io.trazo.app.ai

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import io.trazo.app.data.ModelHttpTransfer
import io.trazo.app.data.ModelHttpResponse

private val HF_COMMIT = Regex("[0-9a-fA-F]{40}")

/** Network-independent address validation; redirects are validated independently by HttpUrlTransfer. */
internal fun validateModelSourceUrl(raw: String) {
    val uri = runCatching { URI(raw) }.getOrNull() ?: throw IllegalArgumentException("Invalid URL")
    require(uri.scheme?.lowercase() == "https" && uri.host != null && uri.host!!.isNotBlank()) { "Only HTTPS downloads are allowed" }
    require(uri.userInfo == null && uri.fragment == null && uri.port == -1) { "URL must not contain userinfo, fragment or custom port" }
    val host = uri.host!!.lowercase()
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal")) { "Local hosts are not download sources" }
    require(!host.matches(Regex("[0-9.]+")) && !host.contains(':')) { "Use a DNS HTTPS hostname" }
    require(uri.path != null && uri.path.startsWith("/") && !uri.path.contains("..")) { "Invalid path" }
    if (host == "huggingface.co") {
        require(isPinnedHuggingFaceUrl(raw)) { "Hugging Face URL must use /owner/repo/resolve/<full-commit>/file" }
    }
}

internal fun isPinnedHuggingFaceUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.host.equals("huggingface.co", ignoreCase = true)) return false
    if (uri.query != null || uri.userInfo != null || uri.fragment != null || uri.port != -1) return false
    val segments = uri.path.trim('/').split('/')
    return segments.size >= 5 && segments[2] == "resolve" && HF_COMMIT.matches(segments[3]) &&
        segments[0].isNotBlank() && segments[1].isNotBlank() && segments.drop(4).all { it.isNotBlank() && it != "." && it != ".." }
}

/** No automatic redirects, no Authorization propagation, no plaintext downgrade. */
internal class HttpUrlModelTransfer : ModelHttpTransfer {
    override fun open(url: String, offset: Long, ifRange: String?): ModelHttpResponse {
        var target = url
        repeat(6) { hop ->
            validateRedirectUrl(target)
            val conn = (URI(target).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "Trazo-Android/1 ModelDownloader")
                if (offset > 0L) {
                    setRequestProperty("Range", "bytes=$offset-")
                    if (ifRange != null) setRequestProperty("If-Range", ifRange)
                }
            }
            try {
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                    if (hop == 5) throw IOException("Too many redirects")
                    val next = URI(target).resolve(location).toString()
                    validateRedirectUrl(next)
                    target = next
                    conn.disconnect()
                } else {
                    return object : ModelHttpResponse {
                        override val status: Int = status
                        override fun header(name: String): String? = conn.getHeaderField(name)
                        override val body: InputStream = if (status in 200..299) conn.inputStream else conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
                        override fun close() { body.close(); conn.disconnect() }
                    }
                }
            } catch (e: Throwable) { conn.disconnect(); throw e }
        }
        throw IOException("Too many redirects")
    }

    private fun validateRedirectUrl(raw: String) {
        val uri = try { URI(raw) } catch (e: Exception) { throw IOException("Invalid download redirect", e) }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.port != -1) {
            throw IOException("Unsafe download redirect")
        }
        val host = uri.host!!.lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") ||
            host.endsWith(".internal") || host.matches(Regex("[0-9.]+")) || host.contains(':')) {
            throw IOException("Redirect to local network not permitted")
        }
        // Redirects from the Hub commonly go to signed Xet/CDN URLs. The original URL
        // is pinned to the immutable Git revision, and the resulting bytes are checked.
    }
}
