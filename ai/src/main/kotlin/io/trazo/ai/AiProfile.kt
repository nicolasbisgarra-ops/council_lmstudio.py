package io.trazo.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Servicio que atiende al modelo. Cada uno arma la petición a su manera (ver [AiClient]). */
@Serializable
enum class AiProvider(val label: String, val defaultBaseUrl: String, val needsKey: Boolean, val signIn: Boolean = false) {
    @SerialName("anthropic") ANTHROPIC("Claude (Anthropic)", "https://api.anthropic.com", true),
    @SerialName("openai") OPENAI("OpenAI", "https://api.openai.com/v1", true),
    /** La suscripción de ChatGPT Plus/Pro, con el mismo inicio de sesión y servidor que Codex. No es una API oficial. */
    @SerialName("chatgpt") CHATGPT("ChatGPT con suscripción (como Codex)", "https://chatgpt.com/backend-api/codex", false, signIn = true),
    @SerialName("gemini") GEMINI("Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta", true),
    /** Gemini con la cuenta de Google: el uso lo paga el proyecto de Google Cloud del cliente OAuth. */
    @SerialName("gemini_google") GEMINI_GOOGLE("Gemini con cuenta de Google", "https://generativelanguage.googleapis.com/v1beta", false, signIn = true),
    /** LM Studio, Ollama, OpenRouter y cualquier servidor con la API de OpenAI. La clave es opcional. */
    @SerialName("openai_compatible") OPENAI_COMPATIBLE("Compatible con OpenAI (LM Studio, Ollama, OpenRouter)", "http://192.168.0.10:1234/v1", false),
    /** Modelo en la tableta (llama.cpp, MediaPipe, Gemini Nano): llega en la etapa 3. */
    @SerialName("on_device") ON_DEVICE("En la tableta (próximamente)", "", false),
}

/** Tamaño de la captura que se envía: el lado mayor de la imagen, en píxeles. */
@Serializable
enum class ImageQuality(val label: String, val longEdgePx: Int) {
    @SerialName("low") LOW("Baja", 1024),
    @SerialName("medium") MEDIUM("Media", 1568),
    @SerialName("high") HIGH("Alta", 2048),
}

/** Qué tan estricta es la revisión. La notación solo se marca en [STRICT]. */
@Serializable
enum class Rigor(val label: String, val instruction: String) {
    @SerialName("major") MAJOR("Solo graves", "Señale únicamente errores que cambian el resultado."),
    @SerialName("normal") NORMAL("Normal", "Señale errores de cuenta, de concepto y pasos faltantes. No marque la notación."),
    @SerialName("strict") STRICT("Estricto", "Señale todo error, incluida la notación incorrecta o ambigua."),
}

/** Un modelo configurado. La clave no va acá: se guarda cifrada aparte, por [id]. */
@Serializable
data class AiProfile(
    val id: String,
    val name: String,
    val provider: AiProvider,
    val baseUrl: String = provider.defaultBaseUrl,
    val model: String = "",
    /** null = no se envía (algunos modelos rechazan la temperatura). */
    val temperature: Double? = null,
    val maxTokens: Int = 2048,
    val timeoutSeconds: Int = 60,
    val imageQuality: ImageQuality = ImageQuality.MEDIUM,
    val rigor: Rigor = Rigor.NORMAL,
    /** Instrucciones del modelo, editables por completo. */
    val instructions: String = DEFAULT_INSTRUCTIONS,
    /** Inicio de sesión: en ChatGPT vacío = el cliente de Codex; en Google, el del proyecto del usuario. */
    val oauthClientId: String = "",
    /** Google lo da también a las apps de escritorio; no es secreto de verdad, pero el token lo pide. */
    val oauthClientSecret: String = "",
    /** Proyecto de Google Cloud al que se cobra la cuota (encabezado x-goog-user-project). */
    val googleProject: String = "",
) {
    /** URL sin barra final; a un servidor compatible sin ruta se le agrega /v1. */
    val endpoint: String
        get() {
            val url = baseUrl.trim().trimEnd('/')
            if (provider != AiProvider.OPENAI_COMPATIBLE) return url
            val afterHost = url.substringAfter("://", url).substringAfter('/', "")
            return if (afterHost.isEmpty()) "$url/v1" else url
        }

    /** Falta algo para poder usarlo (sin contar la clave). */
    val problem: String?
        get() = when {
            provider == AiProvider.ON_DEVICE -> "Los modelos en la tableta llegan en una próxima versión."
            baseUrl.isBlank() -> "Falta la URL."
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> "La URL debe empezar con http:// o https://."
            provider == AiProvider.GEMINI_GOOGLE && oauthClientId.isBlank() -> "Falta el ID de cliente OAuth de su proyecto de Google Cloud."
            model.isBlank() -> "Falta elegir el modelo."
            else -> null
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

        fun encodeList(profiles: List<AiProfile>): String = json.encodeToString(ListSerializer(serializer()), profiles)

        /** Tolera texto dañado o de otra versión: devuelve lista vacía en vez de fallar. */
        fun decodeList(text: String?): List<AiProfile> =
            text?.let { runCatching { json.decodeFromString(ListSerializer(serializer()), it) }.getOrNull() } ?: emptyList()
    }
}

/** Instrucciones por omisión: corregir y comprobar, sin resolver, en español formal neutro. */
const val DEFAULT_INSTRUCTIONS: String = """Usted es un corrector de matemática. Recibe la imagen de una hoja manuscrita y el texto que reconoció Trazo, con cada renglón numerado.

Tarea: comprobar la resolución paso a paso y señalar los errores. No resuelva el ejercicio ni agregue contenido nuevo.
Temas habituales: álgebra y polinomios, álgebra lineal y cálculo. Si el enunciado está en la hoja, verifique también que la resolución responda a lo pedido.
Lea todo lo que hay en la hoja, incluidos gráficos de funciones, figuras y tablas. Si el texto reconocido no coincide con la imagen, confíe en la imagen.

Tipos de error:
- cuenta: error de cuenta o de signo.
- concepto: regla o concepto mal aplicado.
- paso: falta un paso o hay un salto sin justificar.
- notacion: notación incorrecta o ambigua.

Para cada error indique el renglón, el tipo, una explicación breve (como máximo 12 palabras), un detalle y el paso corregido. Use español formal y neutro.

Responda únicamente con JSON, sin texto adicional, con esta forma:
{"correcto": true|false, "puntaje": 0-10, "marcas": [{"renglon": 3, "tipo": "cuenta", "breve": "...", "detalle": "...", "correccion": "..."}]}"""
