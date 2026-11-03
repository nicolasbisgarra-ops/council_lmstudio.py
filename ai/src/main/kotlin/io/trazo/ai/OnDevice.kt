package io.trazo.ai

/** Con qué motor corre un modelo en la tableta. Sale de la extensión del archivo. */
enum class OnDeviceRuntime(val label: String) {
    LLAMA_CPP("llama.cpp"),
    MEDIAPIPE("MediaPipe LLM");

    companion object {
        fun of(file: String): OnDeviceRuntime? = when (file.substringAfterLast('.', "").lowercase()) {
            "gguf" -> LLAMA_CPP
            "task", "litertlm" -> MEDIAPIPE
            else -> null
        }
    }
}

/**
 * Corre un modelo en la tableta, sin red. La app registra su implementación en [OnDevice.engine] al arrancar
 * (llama.cpp por JNI y MediaPipe); `:ai` solo la llama.
 */
interface OnDeviceEngine {
    /** Los modelos instalados: [AiModelInfo.id] es el nombre del archivo, el que va en [AiProfile.model]. */
    fun installed(): List<AiModelInfo>

    /** Genera la respuesta; [onText] recibe cada trozo apenas sale. Devuelve el texto completo. */
    fun generate(profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int, onText: (String) -> Unit): String
}

object OnDevice {
    @Volatile var engine: OnDeviceEngine? = null
}

/**
 * Un modelo con visión recomendado para la tableta. Los nombres exactos de los archivos se leen de Hugging Face
 * al descargar ([HuggingFace.pick]): se elige el de cuantización [quant] y, en llama.cpp, el proyector de imágenes (mmproj).
 */
data class OnDeviceCatalogEntry(
    val id: String,
    val name: String,
    val note: String,
    val repo: String,
    val quant: String,
    val runtime: OnDeviceRuntime,
    /** Tamaño aproximado de la descarga, en MB. */
    val sizeMb: Int,
    /** El repositorio pide aceptar la licencia y un token de Hugging Face. */
    val gated: Boolean = false,
)

/** La lista recomendada: modelos chicos que leen imágenes y entran en la memoria de una tableta. */
object OnDeviceCatalog {
    val entries: List<OnDeviceCatalogEntry> = listOf(
        OnDeviceCatalogEntry(
            "smolvlm2-2b", "SmolVLM2 2.2B", "El más liviano; lee bien cuentas cortas. Unos 3 GB de memoria.",
            "ggml-org/SmolVLM2-2.2B-Instruct-GGUF", "Q4_K_M", OnDeviceRuntime.LLAMA_CPP, 1700,
        ),
        OnDeviceCatalogEntry(
            "qwen2.5-vl-3b", "Qwen2.5-VL 3B", "Buen lector de letra manuscrita y fórmulas. Unos 4 GB de memoria.",
            "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "Q4_K_M", OnDeviceRuntime.LLAMA_CPP, 2800,
        ),
        OnDeviceCatalogEntry(
            "gemma-3-4b", "Gemma 3 4B", "El que mejor razona de la lista; más lento. Unos 5 GB de memoria.",
            "ggml-org/gemma-3-4b-it-GGUF", "Q4_K_M", OnDeviceRuntime.LLAMA_CPP, 3300,
        ),
        OnDeviceCatalogEntry(
            "gemma-3n-e2b", "Gemma 3n E2B (MediaPipe)", "Motor de Google, rápido con GPU. Pide aceptar la licencia en Hugging Face y un token.",
            "google/gemma-3n-E2B-it-litert-preview", "int4", OnDeviceRuntime.MEDIAPIPE, 3100, gated = true,
        ),
    )

    fun byId(id: String): OnDeviceCatalogEntry? = entries.firstOrNull { it.id == id }
}

/** Qué bajar de un repositorio: el modelo y, si hace falta, su proyector de visión. */
data class HubPick(val model: String, val vision: String?)

/** Direcciones de Hugging Face y lectura de la lista de archivos de un repositorio. */
object HuggingFace {
    private val repoPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")

    /** Acepta «dueño/repo» o una URL de huggingface.co; devuelve «dueño/repo» o null. */
    fun repoOf(input: String): String? {
        val s = input.trim().removeSuffix("/")
            .removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/").removePrefix("hf.co/")
        val parts = s.split('/')
        if (parts.size < 2) return null
        val repo = "${parts[0]}/${parts[1]}"
        return repo.takeIf { repoPattern.matches(it) && ".." !in it }
    }

    fun fileUrl(repo: String, name: String): String =
        "https://huggingface.co/$repo/resolve/main/" + name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun treeUrl(repo: String): String = "https://huggingface.co/api/models/$repo"

    /** Los archivos de modelo (.gguf, .task, .litertlm) que lista la API, en el orden en que vienen. */
    fun modelFiles(apiJson: String): List<String> {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(apiJson) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return emptyList()
        val siblings = root["siblings"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return siblings.mapNotNull { s ->
            ((s as? kotlinx.serialization.json.JsonObject)?.get("rfilename") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.filter { OnDeviceRuntime.of(it) != null }
    }

    /** Un archivo mmproj es el proyector de visión, no el modelo. */
    fun isVisionProjector(name: String): Boolean = "mmproj" in name.substringAfterLast('/').lowercase()

    /**
     * Elige de [files] el modelo para [runtime]: el que tiene [quant] en el nombre (o el primero) y, para llama.cpp,
     * el proyector mmproj (mejor en Q8_0, después f16). Null si no hay modelo.
     */
    fun pick(files: List<String>, runtime: OnDeviceRuntime, quant: String = ""): HubPick? {
        val usable = files.filter { OnDeviceRuntime.of(it) == runtime }
        val models = usable.filterNot(::isVisionProjector)
        val q = quant.lowercase()
        val model = models.firstOrNull { q.isNotEmpty() && q in it.lowercase() } ?: models.firstOrNull() ?: return null
        val projectors = usable.filter(::isVisionProjector)
        val vision = if (runtime != OnDeviceRuntime.LLAMA_CPP) null else
            projectors.firstOrNull { "q8_0" in it.lowercase() } ?: projectors.firstOrNull { "f16" in it.lowercase() } ?: projectors.firstOrNull()
        return HubPick(model, vision)
    }

    /** Un nombre de archivo seguro para guardar en la carpeta de modelos. */
    fun localName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "modelo.gguf" }
}
