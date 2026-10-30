package io.trazo.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Un modelo que ofrece el servidor ("Obtener modelos"). */
data class AiModelInfo(val id: String, val displayName: String = id)

/** Resultado de "Probar conexión": cuánto tardó y si leyó la imagen de prueba. */
data class ConnectionReport(val latencyMs: Long, val reply: String, val readsImages: Boolean)

/**
 * Habla con cada proveedor usando su propia API. Todas las llamadas bloquean: la app las corre fuera del
 * hilo de la interfaz. La clave solo viaja en los encabezados, nunca en la URL ni en los errores.
 */
class AiClient(
    private val transport: HttpTransport = UrlConnectionTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun listModels(profile: AiProfile, key: String?): List<AiModelInfo> {
        val base = profile.endpoint
        val response = when (profile.provider) {
            AiProvider.ANTHROPIC -> get("$base/v1/models?limit=1000", anthropicHeaders(key), profile)
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE -> get("$base/models", bearer(key), profile)
            AiProvider.GEMINI -> get("$base/models?pageSize=1000", geminiHeaders(key), profile)
            AiProvider.ON_DEVICE -> throw AiException(profile.problem!!)
        }
        val root = parseObject(response)
        val models = when (profile.provider) {
            AiProvider.GEMINI -> root["models"].array().mapNotNull { m ->
                val o = m.jsonObject
                val methods = o["supportedGenerationMethods"].array().mapNotNull { it.text() }
                if ("generateContent" !in methods) return@mapNotNull null
                val id = o["name"].text()?.removePrefix("models/") ?: return@mapNotNull null
                AiModelInfo(id, o["displayName"].text() ?: id)
            }
            else -> root["data"].array().mapNotNull { m ->
                val o = m.jsonObject
                val id = o["id"].text() ?: return@mapNotNull null
                AiModelInfo(id, o["display_name"].text() ?: id)
            }
        }
        if (models.isEmpty()) throw AiException("El servidor no informó ningún modelo.")
        return models.sortedBy { it.displayName.lowercase() }
    }

    /** Pide una respuesta con una imagen PNG y un texto. Devuelve el texto que contestó el modelo. */
    fun complete(profile: AiProfile, key: String?, system: String, text: String, png: ByteArray?, maxTokens: Int = profile.maxTokens): String {
        profile.problem?.let { throw AiException(it) }
        val image = png?.let { Base64.getEncoder().encodeToString(it) }
        val base = profile.endpoint
        val response = when (profile.provider) {
            AiProvider.ANTHROPIC -> post("$base/v1/messages", anthropicHeaders(key), anthropicBody(profile, system, text, image, maxTokens), profile)
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE ->
                post("$base/chat/completions", bearer(key), openAiBody(profile, system, text, image, maxTokens), profile)
            AiProvider.GEMINI -> post(
                "$base/models/${profile.model.removePrefix("models/")}:generateContent", geminiHeaders(key),
                geminiBody(profile, system, text, image, maxTokens), profile,
            )
            AiProvider.ON_DEVICE -> throw AiException(profile.problem!!)
        }
        return replyText(profile.provider, parseObject(response))
    }

    /** Manda una imagen chica con «1+2» escrito: si el modelo contesta 3, lee imágenes. */
    fun testConnection(profile: AiProfile, key: String?): ConnectionReport {
        val start = clock()
        val reply = complete(
            profile, key,
            system = "Responda solo con un número.",
            text = "¿Cuál es el resultado de la cuenta escrita en la imagen?",
            png = VISION_PROBE_PNG, maxTokens = maxOf(256, minOf(profile.maxTokens, 1024)),
        )
        return ConnectionReport(clock() - start, reply.trim(), Regex("(?<![0-9])3(?![0-9])").containsMatchIn(reply))
    }

    internal fun anthropicBody(p: AiProfile, system: String, text: String, image: String?, maxTokens: Int): JsonObject = buildJsonObject {
        put("model", p.model)
        put("max_tokens", maxTokens)
        if (system.isNotBlank()) put("system", system)
        p.temperature?.let { put("temperature", it) }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    if (image != null) addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64"); put("media_type", "image/png"); put("data", image)
                        }
                    }
                    addJsonObject { put("type", "text"); put("text", text) }
                }
            }
        }
    }

    internal fun openAiBody(p: AiProfile, system: String, text: String, image: String?, maxTokens: Int): JsonObject = buildJsonObject {
        put("model", p.model)
        // OpenAI cambió el nombre del límite; los servidores compatibles siguen con el viejo.
        put(if (p.provider == AiProvider.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
        p.temperature?.let { put("temperature", it) }
        putJsonArray("messages") {
            if (system.isNotBlank()) addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject { put("type", "text"); put("text", text) }
                    if (image != null) addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:image/png;base64,$image") }
                    }
                }
            }
        }
    }

    internal fun geminiBody(p: AiProfile, system: String, text: String, image: String?, maxTokens: Int): JsonObject = buildJsonObject {
        if (system.isNotBlank()) putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", system) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", text) }
                    if (image != null) addJsonObject {
                        putJsonObject("inline_data") { put("mime_type", "image/png"); put("data", image) }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", maxTokens)
            p.temperature?.let { put("temperature", it) }
        }
    }

    private fun replyText(provider: AiProvider, root: JsonObject): String {
        val text = when (provider) {
            AiProvider.ANTHROPIC -> root["content"].array()
                .filter { it.jsonObject["type"].text() == "text" }.mapNotNull { it.jsonObject["text"].text() }.joinToString("")
            AiProvider.GEMINI -> root["candidates"].array().firstOrNull()?.jsonObject?.get("content")
                ?.jsonObject?.get("parts").array().mapNotNull { it.jsonObject["text"].text() }.joinToString("")
            else -> root["choices"].array().firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content").let { c ->
                // Algunos servidores devuelven el contenido como lista de partes.
                if (c is JsonArray) c.mapNotNull { it.jsonObject["text"].text() }.joinToString("") else c.text().orEmpty()
            }
        }
        if (text.isBlank()) throw AiException("El modelo respondió vacío (puede que se haya quedado sin tokens).")
        return text
    }

    private fun anthropicHeaders(key: String?) = buildMap {
        put("anthropic-version", "2023-06-01")
        put("x-api-key", requireKey(key))
    }

    private fun geminiHeaders(key: String?) = mapOf("x-goog-api-key" to requireKey(key))

    /** La clave es opcional en los servidores compatibles (LM Studio, Ollama). */
    private fun bearer(key: String?) = if (key.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${key.trim()}")

    private fun requireKey(key: String?): String =
        key?.trim()?.takeIf { it.isNotEmpty() } ?: throw AiException("Falta la clave de API de este perfil.")

    private fun get(url: String, headers: Map<String, String>, p: AiProfile) =
        check(transport.send(HttpRequest("GET", url, headers, timeoutSeconds = p.timeoutSeconds)))

    private fun post(url: String, headers: Map<String, String>, body: JsonObject, p: AiProfile) =
        check(transport.send(HttpRequest("POST", url, headers, body.toString(), p.timeoutSeconds)))

    private fun check(r: HttpResponse): String {
        if (r.status in 200..299) return r.body
        val detail = runCatching {
            val e = json.parseToJsonElement(r.body).jsonObject["error"]
            (e as? JsonObject)?.get("message").text() ?: e.text()
        }.getOrNull()?.take(300)
        val reason = when (r.status) {
            401, 403 -> "La clave no es válida o no tiene permiso"
            404 -> "No se encontró: revise la URL y el modelo"
            429 -> "Se alcanzó el límite de uso del proveedor"
            in 500..599 -> "El servidor del proveedor tuvo un error"
            else -> "El servidor rechazó la petición"
        }
        throw AiException("$reason (HTTP ${r.status})" + (detail?.let { ": $it" } ?: "."), r.status)
    }

    private fun parseObject(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw AiException("La respuesta del servidor no es JSON: ¿la URL apunta a la API?", cause = it)
        }

    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 168×88, «1+2» en negro sobre blanco. */
        val VISION_PROBE_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAKgAAABYCAAAAABEsuNxAAAAhUlEQVR42u3WMQ7AIAgFUO5/6XZxbYLaNAXfXzXwXJC4iiRAQUFBQUFBQUGPhMbI640fAgoKCtoFmi0w+7DVc1BQUNBq0NWBv/tBgIKCgp4GjWS2+4CCgoI2hX51HxQUFBR0sy4oKChoMejqApwFzi7aoKCgoNWgfwsoKCgoKCgoKGhL6A022FFg64anXAAAAABJRU5ErkJggg==",
        )
    }
}
