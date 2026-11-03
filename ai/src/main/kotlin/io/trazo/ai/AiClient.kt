package io.trazo.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Un modelo que ofrece el servidor ("Obtener modelos"). */
data class AiModelInfo(
    val id: String,
    val displayName: String = id,
    val reasoningEfforts: List<AiReasoningEffortOption> = emptyList(),
    val defaultReasoningEffort: String? = null,
    /** true si el catálogo declaró el parámetro, aunque no haya enumerado valores. */
    val reasoningEffortManualSupported: Boolean = false,
    /** Gemini puede informar thinking=true sin especificar niveles seleccionables. */
    val reasoningThinkingSupported: Boolean = false,
    /** true cuando el modelo vino de una consulta de catálogo. */
    val reasoningEffortCatalogKnown: Boolean = false,
)

/** Resultado de "Probar conexión": cuánto tardó y si leyó la imagen de prueba. */
data class ConnectionReport(val latencyMs: Long, val reply: String, val readsImages: Boolean)

/**
 * Habla con cada proveedor usando su propia API. Todas las llamadas bloquean: la app las corre fuera del
 * hilo de la interfaz. La clave solo viaja en los encabezados, nunca en la URL ni en los errores.
 */
class AiClient(
    private val transport: HttpTransport = UrlConnectionTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val local: OnDeviceCompletion? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun localEngine(): OnDeviceCompletion = local ?: throw AiException("El motor local no está disponible en este entorno.")

    private fun localInstructions(profile: AiProfile, system: String): String = if (!profile.localTextOnly) system else
        "$system\n\nNo recibe ninguna imagen. Compruebe solo el texto reconocido; no infiera símbolos '?', gráficos ni figuras ausentes. Indique la incertidumbre y no declare correctos pasos que no pudo leer."

    fun listModels(profile: AiProfile, key: String?): List<AiModelInfo> {
        val base = profile.endpoint
        val response = when (profile.provider) {
            AiProvider.ANTHROPIC -> get("$base/v1/models?limit=1000", anthropicHeaders(key), profile)
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE -> get("$base/models", bearer(key), profile)
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> get("$base/models?pageSize=1000", geminiHeaders(profile, key), profile)
            AiProvider.CHATGPT -> get("$base/models?client_version=$CODEX_CLIENT_VERSION", chatGptHeaders(key) + ("Accept" to "application/json"), profile)
            AiProvider.ON_DEVICE -> return listOf(AiModelInfo(profile.model, profile.model)).filter { it.id.isNotBlank() }
        }
        val root = parseObject(response)
        val models = when (profile.provider) {
            AiProvider.CHATGPT -> (root["models"] ?: root["data"]).array().mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                if (o["visibility"].text() == "hide") return@mapNotNull null
                val id = (o["slug"].text() ?: o["id"].text())?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                modelInfo(id, o["display_name"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: id, o, AiProvider.CHATGPT)
            }.distinctBy { it.id }
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> root["models"].array().mapNotNull { m ->
                val o = m.jsonObject
                val methods = o["supportedGenerationMethods"].array().mapNotNull { it.text() }
                if ("generateContent" !in methods) return@mapNotNull null
                val id = o["name"].text()?.removePrefix("models/") ?: return@mapNotNull null
                modelInfo(id, o["displayName"].text() ?: id, o, profile.provider)
            }
            else -> root["data"].array().mapNotNull { m ->
                val o = m.jsonObject
                val id = o["id"].text() ?: return@mapNotNull null
                modelInfo(id, o["display_name"].text() ?: id, o, profile.provider)
            }
        }
        if (models.isEmpty()) throw AiException(if (profile.provider == AiProvider.CHATGPT)
            "La cuenta inició sesión, pero ChatGPT no devolvió modelos disponibles. Reintentá la lista o volvé a iniciar sesión con la cuenta que tenga acceso a Codex. También podés escribir el identificador del modelo y probarlo."
            else "El servidor no informó ningún modelo.")
        // ChatGPT orders its catalog by server preference; access is checked by an inference request.
        return if (profile.provider == AiProvider.CHATGPT) models else models.sortedBy { it.displayName.lowercase() }
    }

    /** Lee sólo opciones efectivamente anunciadas por el catálogo; nunca fabrica una lista por nombre. */
    private fun modelInfo(id: String, displayName: String, model: JsonObject, provider: AiProvider): AiModelInfo {
        val reasoning = model["reasoning"] as? JsonObject
        val effortArray = sequenceOf(
            model["supported_reasoning_efforts"], model["supportedReasoningEfforts"],
            model["supported_reasoning_levels"], model["supportedReasoningLevels"],
            model["supported_thinking_levels"], model["supportedThinkingLevels"],
            reasoning?.get("supported_efforts"), reasoning?.get("supportedEfforts"),
        ).firstOrNull { it is JsonArray } as? JsonArray
        val parsedEfforts = effortArray.orEmpty().mapNotNull { entry ->
            val (value, description) = when (entry) {
                is JsonPrimitive -> entry.contentOrNull?.trim().orEmpty() to ""
                is JsonObject -> {
                    val value = sequenceOf("effort", "reasoning_effort", "reasoningEffort", "level", "value")
                        .mapNotNull { entry[it].text()?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
                    value.orEmpty() to (entry["description"].text()?.trim().orEmpty())
                }
                else -> "" to ""
            }
            value.takeIf(String::isNotEmpty)?.let { AiReasoningEffortOption(it, description) }
        }.distinctBy { it.value }
        // Only expose values for providers whose request adapter has a matching field.
        val efforts = if (provider == AiProvider.ON_DEVICE) emptyList() else parsedEfforts
        val defaultEffort = sequenceOf(
            model["default_reasoning_effort"], model["defaultReasoningEffort"],
            model["default_reasoning_level"], model["defaultReasoningLevel"],
            model["default_thinking_level"], model["defaultThinkingLevel"],
            reasoning?.get("default_effort"), reasoning?.get("defaultEffort"),
        ).mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
            .takeIf { provider != AiProvider.ON_DEVICE }
        val supportedParameters = model["supported_parameters"].array().mapNotNull { it.text()?.lowercase() }.toSet()
        val explicitSupport = sequenceOf("supports_reasoning_effort", "supportsReasoningEffort", "reasoning_effort_supported")
            .mapNotNull { (model[it] as? JsonPrimitive)?.booleanOrNull }.firstOrNull() == true
        val supportsGeminiLevel = provider == AiProvider.GEMINI || provider == AiProvider.GEMINI_GOOGLE
        val supportsManual = when (provider) {
            AiProvider.CHATGPT, AiProvider.OPENAI_COMPATIBLE -> efforts.isNotEmpty() || explicitSupport ||
                supportedParameters.any { it in setOf("reasoning", "reasoning_effort") }
            AiProvider.OPENAI -> efforts.isNotEmpty() || explicitSupport ||
                supportedParameters.any { it in setOf("reasoning", "reasoning_effort") }
            AiProvider.ANTHROPIC -> efforts.isNotEmpty() || explicitSupport ||
                supportedParameters.any { it in setOf("effort", "output_config.effort") }
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> efforts.isNotEmpty()
            AiProvider.ON_DEVICE -> false
        }
        val thinking = if (supportsGeminiLevel) {
            (model["thinking"] as? JsonPrimitive)?.booleanOrNull
                ?: (model["supports_thinking"] as? JsonPrimitive)?.booleanOrNull
                ?: false
        } else false
        return AiModelInfo(
            id = id,
            displayName = displayName,
            reasoningEfforts = efforts,
            defaultReasoningEffort = defaultEffort,
            reasoningEffortManualSupported = supportsManual,
            reasoningThinkingSupported = thinking,
            reasoningEffortCatalogKnown = true,
        )
    }

    /** Pide una respuesta con una imagen PNG y un texto. Devuelve el texto que contestó el modelo. */
    fun complete(
        profile: AiProfile, key: String?, system: String, text: String, png: ByteArray?,
        maxTokens: Int = profile.maxTokens, onUsage: ((AiTokenUsage) -> Unit)? = null,
    ): String {
        profile.problem?.let { throw AiException(it) }
        if (profile.provider == AiProvider.ON_DEVICE) return localEngine().complete(profile, localInstructions(profile, system), text, png.takeUnless { profile.localTextOnly }, maxTokens)
        val image = png?.let { Base64.getEncoder().encodeToString(it) }
        val base = profile.endpoint
        val response = when (profile.provider) {
            AiProvider.ANTHROPIC -> post("$base/v1/messages", anthropicHeaders(key), anthropicBody(profile, system, text, image, maxTokens), profile)
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE ->
                post("$base/chat/completions", bearer(key), openAiBody(profile, system, text, image, maxTokens), profile)
            AiProvider.CHATGPT -> {
                // El servidor de Codex solo contesta en streaming (SSE): se lee entero y se juntan los trozos.
                val body = check(send(profile, HttpRequest("POST", "$base/responses", chatGptHeaders(key), chatGptBody(profile, system, text, image).toString(), profile.timeoutSeconds)))
                reportStreamedUsage(AiProvider.CHATGPT, body, onUsage)
                return streamedText(body)
            }
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> post(
                "$base/models/${profile.model.removePrefix("models/")}:generateContent", geminiHeaders(profile, key),
                geminiBody(profile, system, text, image, maxTokens), profile,
            )
            AiProvider.ON_DEVICE -> error("Local inference is handled before HTTP")
        }
        val root = parseObject(response)
        usageFrom(profile.provider, root)?.let { onUsage?.invoke(it) }
        return replyText(profile.provider, root)
    }

    /**
     * Como [complete], pero en streaming: [onText] recibe cada trozo de texto apenas llega (las marcas se
     * van mostrando). Si el servidor no contesta en streaming, se lee la respuesta entera. Devuelve el texto completo.
     */
    fun stream(
        profile: AiProfile, key: String?, system: String, text: String, png: ByteArray?,
        maxTokens: Int = profile.maxTokens, onUsage: ((AiTokenUsage) -> Unit)? = null, onText: (String) -> Unit,
    ): String {
        profile.problem?.let { throw AiException(it) }
        if (profile.provider == AiProvider.ON_DEVICE) return localEngine().stream(profile, localInstructions(profile, system), text, png.takeUnless { profile.localTextOnly }, maxTokens, onText)
        val image = png?.let { Base64.getEncoder().encodeToString(it) }
        val base = profile.endpoint
        val p = profile.provider
        val (url, headers, body) = when (p) {
            AiProvider.ANTHROPIC -> Triple("$base/v1/messages", anthropicHeaders(key), anthropicBody(profile, system, text, image, maxTokens).streaming())
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE ->
                Triple("$base/chat/completions", bearer(key), openAiStreamingBody(profile, system, text, image, maxTokens))
            AiProvider.CHATGPT -> Triple("$base/responses", chatGptHeaders(key), chatGptBody(profile, system, text, image))
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> Triple(
                "$base/models/${profile.model.removePrefix("models/")}:streamGenerateContent?alt=sse",
                geminiHeaders(profile, key), geminiBody(profile, system, text, image, maxTokens),
            )
            AiProvider.ON_DEVICE -> error("Local inference is handled before HTTP")
        }
        val out = StringBuilder()
        var events = 0
        val usageAccumulator = UsageAccumulator()
        guard(profile, url)
        val response = transport.stream(HttpRequest("POST", url, headers + ("Accept" to "text/event-stream"), body.toString(), profile.timeoutSeconds)) { line ->
            if (!line.startsWith("data:")) return@stream
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@stream
            val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@stream
            events++
            usageFrom(p, event)?.let { usageAccumulator.accept(it)?.let { snapshot -> onUsage?.invoke(snapshot) } }
            val delta = deltaOf(p, event)
            if (!delta.isNullOrEmpty()) { out.append(delta); onText(delta) }
        }
        val raw = check(response)
        if (out.isNotEmpty()) return out.toString()
        // Sin trozos: un servidor que ignoró el streaming, o Codex con el texto solo al final.
        val whole = when {
            p == AiProvider.CHATGPT -> streamedText(raw)
            events == 0 -> parseObject(raw).let { root ->
                usageFrom(p, root)?.let { onUsage?.invoke(it) }
                replyText(p, root)
            }
            else -> throw AiException("El modelo respondió vacío (puede que se haya quedado sin tokens).")
        }
        onText(whole)
        return whole
    }

    /** El texto de un evento SSE de cada proveedor; lanza [AiException] si el evento es un error. */
    private fun deltaOf(p: AiProvider, e: JsonObject): String? = when (p) {
        AiProvider.ANTHROPIC -> when (e["type"].text()) {
            "content_block_delta" -> e["delta"]?.jsonObject?.takeIf { it["type"].text() == "text_delta" }?.get("text").text()
            "error" -> throw AiException("El proveedor cortó la respuesta: ${(e["error"] as? JsonObject)?.get("message").text() ?: "sin detalle"}")
            else -> null
        }
        AiProvider.CHATGPT -> when (e["type"].text()) {
            "response.output_text.delta" -> e["delta"].text()
            "response.failed", "error" -> {
                val err = e["response"]?.jsonObject?.get("error") ?: e["error"] ?: e
                throw AiException("ChatGPT rechazó el pedido: ${((err as? JsonObject)?.get("message")).text() ?: "sin detalle"}")
            }
            else -> null
        }
        AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> {
            (e["error"] as? JsonObject)?.let { throw AiException("Gemini cortó la respuesta: ${it["message"].text() ?: "sin detalle"}") }
            e["candidates"].array().firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts").array()
                .mapNotNull { it.jsonObject["text"].text() }.joinToString("")
        }
        else -> {
            (e["error"] as? JsonObject)?.let { throw AiException("El servidor cortó la respuesta: ${it["message"].text() ?: "sin detalle"}") }
            e["choices"].array().firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content").let { c ->
                if (c is JsonArray) c.mapNotNull { it.jsonObject["text"].text() }.joinToString("") else c.text()
            }
        }
    }

    private fun JsonObject.streaming() = JsonObject(this + ("stream" to JsonPrimitive(true)))

    private fun openAiStreamingBody(p: AiProfile, system: String, text: String, image: String?, maxTokens: Int): JsonObject {
        val streaming = openAiBody(p, system, text, image, maxTokens).streaming()
        // OpenAI's Chat Completions API emits final usage only when requested. Compatible endpoints
        // may reject stream_options, so send it only to the first-party provider.
        return if (p.provider == AiProvider.OPENAI) JsonObject(streaming + ("stream_options" to buildJsonObject {
            put("include_usage", true)
        })) else streaming
    }

    private class UsageAccumulator {
        private var current: AiTokenUsage? = null

        fun accept(next: AiTokenUsage): AiTokenUsage? {
            val merged = current?.mergeSnapshot(next) ?: next
            if (merged == current) return null
            current = merged
            return merged
        }
    }

    /** Reads the usage object belonging to one provider response/event. */
    private fun usageFrom(provider: AiProvider, event: JsonObject): AiTokenUsage? {
        val usage = when (provider) {
            AiProvider.ANTHROPIC -> ((event["message"] as? JsonObject)?.get("usage") as? JsonObject)
                ?: (event["usage"] as? JsonObject)
            AiProvider.CHATGPT -> ((event["response"] as? JsonObject)?.get("usage") as? JsonObject)
                ?: (event["usage"] as? JsonObject)
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> event["usageMetadata"] as? JsonObject
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE -> event["usage"] as? JsonObject
            AiProvider.ON_DEVICE -> null
        } ?: return null

        val input: Int?
        val output: Int?
        val reasoning: Int?
        val cachedInput: Int?
        val imageInput: Int?
        when (provider) {
            AiProvider.ANTHROPIC -> {
                val uncachedInput = usage.nonNegativeInt("input_tokens")
                val cacheCreation = usage.nonNegativeInt("cache_creation_input_tokens")
                val cacheRead = usage.nonNegativeInt("cache_read_input_tokens")
                cachedInput = sumWhenBothReported(cacheCreation, cacheRead)
                // Anthropic's input_tokens excludes cache reads and writes. A total input count is
                // complete when both cache fields are present; older/no-cache responses omit both.
                input = when {
                    uncachedInput == null -> null
                    cacheCreation == null && cacheRead == null -> uncachedInput
                    cacheCreation != null && cacheRead != null -> sumCounts(uncachedInput, cacheCreation, cacheRead)
                    else -> null
                }
                output = usage.nonNegativeInt("output_tokens")
                reasoning = ((usage["output_tokens_details"] as? JsonObject)
                    ?: (usage["output_token_details"] as? JsonObject))?.nonNegativeInt("thinking_tokens")
                imageInput = null
            }
            AiProvider.CHATGPT -> {
                val inputDetails = usage["input_tokens_details"] as? JsonObject
                val outputDetails = usage["output_tokens_details"] as? JsonObject
                input = usage.nonNegativeInt("input_tokens")
                output = usage.nonNegativeInt("output_tokens")
                reasoning = outputDetails?.nonNegativeInt("reasoning_tokens")
                cachedInput = inputDetails?.nonNegativeInt("cached_tokens")
                imageInput = inputDetails?.nonNegativeInt("image_tokens")
            }
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> {
                val candidates = usage.nonNegativeInt("candidatesTokenCount")
                val thoughts = usage.nonNegativeInt("thoughtsTokenCount")
                input = usage.nonNegativeInt("promptTokenCount")
                output = candidates?.let { sumCounts(it, thoughts ?: 0) }
                reasoning = thoughts
                cachedInput = usage.nonNegativeInt("cachedContentTokenCount")
                imageInput = usage["promptTokensDetails"].array().mapNotNull { (it as? JsonObject)
                    ?.takeIf { detail -> detail["modality"].text().equals("IMAGE", ignoreCase = true) }
                    ?.nonNegativeInt("tokenCount")
                }.takeIf { it.isNotEmpty() }?.let { sumCounts(*it.toIntArray()) }
            }
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE -> {
                val promptDetails = usage["prompt_tokens_details"] as? JsonObject
                val inputDetails = usage["input_tokens_details"] as? JsonObject
                val outputDetails = usage["completion_tokens_details"] as? JsonObject
                    ?: usage["output_tokens_details"] as? JsonObject
                input = usage.nonNegativeInt("prompt_tokens") ?: usage.nonNegativeInt("input_tokens")
                output = usage.nonNegativeInt("completion_tokens") ?: usage.nonNegativeInt("output_tokens")
                reasoning = outputDetails?.nonNegativeInt("reasoning_tokens")
                cachedInput = promptDetails?.nonNegativeInt("cached_tokens") ?: inputDetails?.nonNegativeInt("cached_tokens")
                imageInput = promptDetails?.nonNegativeInt("image_tokens") ?: inputDetails?.nonNegativeInt("image_tokens")
            }
            AiProvider.ON_DEVICE -> return null
        }
        return AiTokenUsage(input, output, reasoning, cachedInput, imageInput).takeIf { it.hasAnyCount }
    }

    private fun JsonObject.nonNegativeInt(field: String): Int? =
        (this[field] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

    private fun sumWhenBothReported(a: Int?, b: Int?): Int? = if (a == null || b == null) null else sumCounts(a, b)

    private fun sumCounts(vararg counts: Int): Int? =
        counts.fold(0L) { sum, count -> sum + count }.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun reportStreamedUsage(provider: AiProvider, sse: String, onUsage: ((AiTokenUsage) -> Unit)?) {
        if (onUsage == null) return
        val accumulator = UsageAccumulator()
        for (line in sse.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            usageFrom(provider, event)?.let { accumulator.accept(it)?.let(onUsage) }
        }
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
        if (p.reasoningEffortMode == AiReasoningEffortMode.MANUAL) {
            p.reasoningEffort?.let { effort -> putJsonObject("output_config") { put("effort", effort) } }
        }
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
        if (p.reasoningEffortMode == AiReasoningEffortMode.MANUAL) {
            p.reasoningEffort?.let { put("reasoning_effort", it) }
        }
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

    /** API «Responses». Sin límite de tokens ni temperatura: el servidor de Codex los rechaza. */
    internal fun chatGptBody(p: AiProfile, system: String, text: String, image: String?): JsonObject = buildJsonObject {
        put("model", p.model)
        if (p.reasoningEffortMode == AiReasoningEffortMode.MANUAL) {
            p.reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
        }
        put("instructions", system)
        putJsonArray("input") {
            addJsonObject {
                put("type", "message")
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject { put("type", "input_text"); put("text", text) }
                    if (image != null) addJsonObject { put("type", "input_image"); put("image_url", "data:image/png;base64,$image") }
                }
            }
        }
        put("tools", JsonArray(emptyList()))
        put("stream", true)
        put("store", false)
    }

    /** Junta el texto de los eventos `response.output_text.delta`; si no hay, toma el de `response.completed`. */
    internal fun streamedText(sse: String): String {
        val deltas = StringBuilder()
        var completed: String? = null
        for (line in sse.lineSequence()) {
            val data = line.removePrefix("data:").trim()
            if (!line.startsWith("data:") || data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (event["type"].text()) {
                "response.output_text.delta" -> deltas.append(event["delta"].text().orEmpty())
                "response.completed" -> completed = event["response"]?.jsonObject?.get("output").array()
                    .flatMap { it.jsonObject["content"].array() }
                    .filter { it.jsonObject["type"].text() == "output_text" }
                    .mapNotNull { it.jsonObject["text"].text() }.joinToString("")
                "response.failed", "error" -> {
                    val e = event["response"]?.jsonObject?.get("error") ?: event["error"] ?: event
                    throw AiException("ChatGPT rechazó el pedido: ${((e as? JsonObject)?.get("message")).text() ?: "sin detalle"}")
                }
            }
        }
        val text = deltas.toString().ifBlank { completed.orEmpty() }
        if (text.isBlank()) throw AiException("El modelo respondió vacío (puede que se haya quedado sin tokens).")
        return text
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
            if (p.reasoningEffortMode == AiReasoningEffortMode.MANUAL) {
                p.reasoningEffort?.let { effort -> putJsonObject("thinkingConfig") { put("thinkingLevel", effort) } }
            }
        }
    }

    private fun replyText(provider: AiProvider, root: JsonObject): String {
        val text = when (provider) {
            AiProvider.ANTHROPIC -> root["content"].array()
                .filter { it.jsonObject["type"].text() == "text" }.mapNotNull { it.jsonObject["text"].text() }.joinToString("")
            AiProvider.GEMINI, AiProvider.GEMINI_GOOGLE -> root["candidates"].array().firstOrNull()?.jsonObject?.get("content")
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

    /** Con cuenta de Google, la «clave» es la sesión guardada y la cuota se cobra a [AiProfile.googleProject]. */
    private fun geminiHeaders(p: AiProfile, key: String?): Map<String, String> {
        if (p.provider != AiProvider.GEMINI_GOOGLE) return mapOf("x-goog-api-key" to requireKey(key))
        return buildMap {
            put("Authorization", "Bearer ${session(key).accessToken}")
            p.googleProject.trim().takeIf { it.isNotEmpty() }?.let { put("x-goog-user-project", it) }
        }
    }

    /** Los mismos encabezados que manda Codex CLI. */
    private fun chatGptHeaders(key: String?): Map<String, String> {
        val t = session(key)
        return buildMap {
            put("Authorization", "Bearer ${t.accessToken}")
            t.accountId?.let { put("chatgpt-account-id", it) }
            put("OpenAI-Beta", "responses=experimental")
            put("originator", AiOAuth.CODEX_ORIGINATOR)
            put("Accept", "text/event-stream")
        }
    }

    private fun session(key: String?): OAuthTokens =
        OAuthTokens.decode(key) ?: throw AiException("Falta iniciar sesión en este perfil.")

    /** La clave es opcional en los servidores compatibles (LM Studio, Ollama). */
    private fun bearer(key: String?) = if (key.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${key.trim()}")

    private fun requireKey(key: String?): String =
        key?.trim()?.takeIf { it.isNotEmpty() } ?: throw AiException("Falta la clave de API de este perfil.")

    private fun get(url: String, headers: Map<String, String>, p: AiProfile) =
        check(send(p, HttpRequest("GET", url, headers, timeoutSeconds = p.timeoutSeconds)))

    private fun post(url: String, headers: Map<String, String>, body: JsonObject, p: AiProfile) =
        check(send(p, HttpRequest("POST", url, headers, body.toString(), p.timeoutSeconds)))

    private fun send(p: AiProfile, request: HttpRequest): HttpResponse {
        guard(p, request.url)
        return transport.send(request)
    }

    /** Última barrera antes de la red: la sesión de un perfil con inicio de sesión solo va al servidor oficial. */
    private fun guard(p: AiProfile, url: String) {
        // La URL del perfil, sin query; la del pedido puede llevar la que agrega este cliente.
        if (!AiEndpoints.isOfficial(p.provider, p.endpoint) || !AiEndpoints.isOfficial(p.provider, url.substringBefore('?'))) {
            throw AiException("Por seguridad, la sesión solo se envía a ${p.provider.defaultBaseUrl}. Corrija la URL del perfil.")
        }
    }

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
        /** Versión que se informa al pedir la lista de modelos de Codex. */
        const val CODEX_CLIENT_VERSION = "0.166.0"

        /** 168×88, «1+2» en negro sobre blanco. */
        val VISION_PROBE_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAKgAAABYCAAAAABEsuNxAAAAhUlEQVR42u3WMQ7AIAgFUO5/6XZxbYLaNAXfXzXwXJC4iiRAQUFBQUFBQUGPhMbI640fAgoKCtoFmi0w+7DVc1BQUNBq0NWBv/tBgIKCgp4GjWS2+4CCgoI2hX51HxQUFBR0sy4oKChoMejqApwFzi7aoKCgoNWgfwsoKCgoKCgoKGhL6A022FFg64anXAAAAABJRU5ErkJggg==",
        )
    }
}
