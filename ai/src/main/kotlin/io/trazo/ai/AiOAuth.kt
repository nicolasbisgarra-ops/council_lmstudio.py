package io.trazo.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Lo que queda guardado (cifrado) tras iniciar sesión, en lugar de una clave de API.
 * [accountId] es la cuenta de ChatGPT, que su servidor pide en cada llamada.
 */
@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0,
    val accountId: String? = null,
    val email: String? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null si el texto guardado no es una sesión (por ejemplo, una clave de API vieja). */
        fun decode(text: String?): OAuthTokens? =
            text?.takeIf { it.trimStart().startsWith("{") }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** PKCE (RFC 7636): el verificador se queda en el equipo y al navegador solo va su hash. */
class Pkce(val verifier: String) {
    val challenge: String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    companion object {
        fun create(random: SecureRandom = SecureRandom()): Pkce = Pkce(randomToken(random, 64))

        fun randomToken(random: SecureRandom = SecureRandom(), bytes: Int = 32): String =
            base64Url(ByteArray(bytes).also(random::nextBytes))

        private fun base64Url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

/** Cómo inicia sesión cada proveedor con cuenta. */
data class OAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String?,
    val scope: String,
    /** Puerto fijo del regreso: el de Codex está registrado así; 0 = cualquiera libre (Google). */
    val port: Int,
    val redirectPath: String,
    val redirectHost: String,
    val extraParams: Map<String, String> = emptyMap(),
) {
    fun redirectUri(boundPort: Int) = "http://$redirectHost:$boundPort$redirectPath"
}

/**
 * Inicio de sesión con la cuenta del usuario: se abre el navegador, el proveedor vuelve a un servidor
 * mínimo en 127.0.0.1 de la tableta con un código, y el código se cambia por tokens.
 */
class AiOAuth(private val transport: HttpTransport = UrlConnectionTransport, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    fun config(p: AiProfile): OAuthConfig = when (p.provider) {
        AiProvider.CHATGPT -> OAuthConfig(
            authorizeUrl = "https://auth.openai.com/oauth/authorize",
            tokenUrl = "https://auth.openai.com/oauth/token",
            clientId = p.oauthClientId.ifBlank { CODEX_CLIENT_ID },
            clientSecret = null,
            scope = "openid profile email offline_access",
            port = 1455, redirectPath = "/auth/callback", redirectHost = "localhost",
            extraParams = mapOf(
                "id_token_add_organizations" to "true",
                "codex_cli_simplified_flow" to "true",
                "originator" to CODEX_ORIGINATOR,
            ),
        )
        AiProvider.GEMINI_GOOGLE -> OAuthConfig(
            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            clientId = p.oauthClientId.trim(),
            clientSecret = p.oauthClientSecret.trim().ifEmpty { null },
            scope = GOOGLE_SCOPES,
            port = 0, redirectPath = "/", redirectHost = "127.0.0.1",
            // Sin esto Google no entrega el token de renovación y habría que volver a entrar cada hora.
            extraParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        )
        else -> throw AiException("${p.provider.label} usa clave de API, no inicio de sesión.")
    }

    fun authorizeUrl(c: OAuthConfig, redirectUri: String, pkce: Pkce, state: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to c.clientId,
            "redirect_uri" to redirectUri,
            "scope" to c.scope,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        ) + c.extraParams
        return c.authorizeUrl + "?" + form(params)
    }

    fun exchange(c: OAuthConfig, code: String, redirectUri: String, pkce: Pkce, timeoutSeconds: Int = 30): OAuthTokens {
        val params = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to c.clientId,
            "code_verifier" to pkce.verifier,
        )
        c.clientSecret?.let { params["client_secret"] = it }
        return tokens(postForm(c.tokenUrl, params, timeoutSeconds), previous = null)
    }

    fun refresh(c: OAuthConfig, t: OAuthTokens, timeoutSeconds: Int = 30): OAuthTokens {
        val refresh = t.refreshToken ?: throw AiException("La sesión venció: vuelva a iniciar sesión.")
        val params = linkedMapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to c.clientId)
        c.clientSecret?.let { params["client_secret"] = it }
        return tokens(postForm(c.tokenUrl, params, timeoutSeconds), previous = t)
    }

    /** Renueva si vence en menos de un minuto. Devuelve los mismos tokens si todavía sirven. */
    fun fresh(p: AiProfile, t: OAuthTokens): OAuthTokens =
        if (t.expiresAtMs > clock() + 60_000) t else refresh(config(p), t, p.timeoutSeconds)

    private fun postForm(url: String, params: Map<String, String>, timeoutSeconds: Int): JsonObject {
        val r = transport.send(
            HttpRequest("POST", url, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), form(params), timeoutSeconds),
        )
        val body = runCatching { json.parseToJsonElement(r.body).jsonObject }.getOrNull()
        if (r.status !in 200..299 || body == null) {
            val detail = body?.let { it["error_description"].text() ?: (it["error"] as? JsonObject)?.get("message").text() ?: it["error"].text() }
            val reason = if (r.status == 400 || r.status == 401) "El proveedor rechazó el inicio de sesión" else "No se pudo iniciar sesión"
            throw AiException("$reason (HTTP ${r.status})" + (detail?.let { ": ${it.take(300)}" } ?: "."), r.status)
        }
        return body
    }

    private fun tokens(o: JsonObject, previous: OAuthTokens?): OAuthTokens {
        val access = o["access_token"].text() ?: throw AiException("El proveedor no devolvió el token de acceso.")
        val claims = o["id_token"].text()?.let(::jwtClaims)
        val auth = claims?.get("https://api.openai.com/auth") as? JsonObject
        val expiresIn = (o["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 3600
        return OAuthTokens(
            accessToken = access,
            // Google no repite el token de renovación al renovar: se conserva el anterior.
            refreshToken = o["refresh_token"].text() ?: previous?.refreshToken,
            expiresAtMs = clock() + expiresIn * 1000,
            accountId = auth?.get("chatgpt_account_id").text() ?: previous?.accountId,
            email = claims?.get("email").text() ?: previous?.email,
        )
    }

    companion object {
        /** El cliente público de Codex CLI: con él, ChatGPT Plus/Pro paga el uso. No es una API oficial para terceros. */
        const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val CODEX_ORIGINATOR = "codex_cli_rs"
        const val GOOGLE_SCOPES =
            "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/generative-language.retriever"

        fun form(params: Map<String, String>): String =
            params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** La parte del medio de un JWT, sin verificar la firma: solo se usa para leer la cuenta y el correo. */
        fun jwtClaims(jwt: String): JsonObject? = runCatching {
            val payload = jwt.split('.')[1]
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)).jsonObject
        }.getOrNull()

        private fun kotlinx.serialization.json.JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * Servidor de una sola petición en 127.0.0.1 que recibe el regreso del navegador (`?code=…&state=…`).
 * Solo escucha en la interfaz local: nada de la red puede llegarle.
 */
class LoopbackReceiver(port: Int) : AutoCloseable {
    private val server = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    } catch (e: Exception) {
        throw AiException("No se pudo abrir el puerto $port para el regreso del inicio de sesión (¿hay otro inicio abierto?).", cause = e)
    }
    val port: Int get() = server.localPort

    /** Espera el regreso; ignora pedidos que no traen `code` ni `error` (por ejemplo, el favicon). */
    fun await(path: String, timeoutMs: Int): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw AiException("No llegó la respuesta del inicio de sesión a tiempo.")
            server.soTimeout = left.toInt()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) {
                throw AiException("No llegó la respuesta del inicio de sesión a tiempo.", cause = e)
            }
            socket.use { s ->
                s.soTimeout = 5000
                val line = s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val query = parseQuery(target.substringAfter('?', ""))
                val ours = target.substringBefore('?') == path && ("code" in query || "error" in query)
                val page = if (!ours) "" else if ("code" in query) DONE_PAGE else ERROR_PAGE
                val status = if (ours) "200 OK" else "404 Not Found"
                val bytes = page.toByteArray(Charsets.UTF_8)
                s.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
                if (ours) return query
            }
        }
    }

    override fun close() = server.close()

    companion object {
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        private const val DONE_PAGE =
            "<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:2em'>" +
                "<h2>Listo</h2><p>Ya podés volver a Trazo.</p>"
        private const val ERROR_PAGE =
            "<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:2em'>" +
                "<h2>No se pudo iniciar sesión</h2><p>Volvé a Trazo para ver el motivo.</p>"
    }
}

/** Todo el inicio de sesión de un perfil, de punta a punta. [open] abre la URL en el navegador. */
fun AiOAuth.signIn(p: AiProfile, open: (String) -> Unit, timeoutMs: Int = 5 * 60_000): OAuthTokens {
    val c = config(p)
    if (c.clientId.isBlank()) throw AiException("Falta el ID de cliente OAuth.")
    LoopbackReceiver(c.port).use { receiver ->
        val redirect = c.redirectUri(receiver.port)
        val pkce = Pkce.create()
        val state = Pkce.randomToken()
        open(authorizeUrl(c, redirect, pkce, state))
        val back = receiver.await(c.redirectPath, timeoutMs)
        back["error"]?.let { throw AiException("Se canceló o rechazó el inicio de sesión ($it${back["error_description"]?.let { d -> ": $d" } ?: ""}).") }
        if (back["state"] != state) throw AiException("La respuesta del inicio de sesión no coincide; inténtelo de nuevo.")
        return exchange(c, back["code"]!!, redirect, pkce, p.timeoutSeconds)
    }
}
