package io.trazo.core.sync

import io.trazo.core.CoverKind
import io.trazo.core.Paper
import io.trazo.core.TrazoFormat
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Huella de una hoja: su id y el hash de su JSON. */
@Serializable
data class PagePrint(val id: String, val hash: String)

/**
 * Huella de un cuaderno para comparar versiones sin mandar los trazos. No incluye `lastPage` ni
 * `updatedAt`: la hoja abierta es de cada dispositivo y la fecha cambia al guardar sin cambios.
 */
@Serializable
data class NotebookPrint(
    val id: String,
    val name: String,
    val paper: Paper = Paper.LINES,
    val coverKind: CoverKind = CoverKind.FIRST_PAGE,
    val coverImage: String? = null,
    val folderId: String? = null,
    val pages: List<PagePrint> = emptyList(),
)

/**
 * Lo que los dos dispositivos tenían en común al terminar la última sincronización (el ancestro de la
 * fusión de tres vías). Cada uno guarda la misma base para el otro.
 */
@Serializable
data class SyncBase(
    val notebooks: Map<String, NotebookPrint> = emptyMap(),
    val patterns: Map<String, String> = emptyMap(),
    val folders: Map<String, String> = emptyMap(),
    val order: String? = null,
)

/** Estado de un dispositivo, sin contenido: alcanza para decidir qué pedir. */
@Serializable
data class SyncManifest(
    val protocol: Int = SyncProtocol.VERSION,
    val deviceId: String,
    val notebooks: Map<String, NotebookPrint> = emptyMap(),
    /** Cuadernos abiertos en el editor: no se tocan en esta vuelta. */
    val busy: Set<String> = emptySet(),
    val patterns: Map<String, String> = emptyMap(),
    val folders: Map<String, String> = emptyMap(),
    val order: String? = null,
    /** Imágenes y portadas presentes: `images/<sha>.webp`, `covers/<sha>.webp`… */
    val blobs: Set<String> = emptySet(),
)

/** Otro dispositivo vinculado, con la clave que comparten. */
@Serializable
data class SyncPeer(
    val deviceId: String,
    val name: String,
    /** Clave de 32 bytes en Base64. */
    val key: String,
    /** Direcciones conocidas (LAN, Tailscale 100.x o nombre MagicDNS). */
    val addresses: List<String> = emptyList(),
    val lastSync: Long = 0L,
)

/** Configuración de la sincronización de este dispositivo. */
@Serializable
data class SyncConfig(
    val deviceId: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = false,
)

/** Qué archivo representa una clave: un cuaderno o los archivos compartidos. */
sealed interface SyncKey {
    data class NotebookKey(val id: String) : SyncKey
    data object Patterns : SyncKey
    data object Folders : SyncKey
    data object Order : SyncKey

    val wire: String
        get() = when (this) {
            is NotebookKey -> "nb:$id"
            Patterns -> "patterns"
            Folders -> "folders"
            Order -> "order"
        }

    companion object {
        fun parse(s: String): SyncKey? = when {
            s.startsWith("nb:") && s.length > 3 -> NotebookKey(s.substring(3))
            s == "patterns" -> Patterns
            s == "folders" -> Folders
            s == "order" -> Order
            else -> null
        }
    }
}

/** Mensajes del protocolo; viajan como JSON dentro del canal cifrado. */
@Serializable
sealed class SyncMessage {
    @Serializable @SerialName("getManifest") data object GetManifest : SyncMessage()
    @Serializable @SerialName("manifest") data class Manifest(val manifest: SyncManifest) : SyncMessage()
    @Serializable @SerialName("get") data class Get(val key: String) : SyncMessage()
    /** [text] null: no existe. [fingerprint] identifica la versión para una escritura condicional. */
    @Serializable @SerialName("text") data class Text(val key: String, val text: String? = null, val fingerprint: String) : SyncMessage()
    @Serializable @SerialName("put") data class Put(val key: String, val text: String? = null, val expect: String) : SyncMessage()
    @Serializable @SerialName("putResult") data class PutResult(val key: String, val ok: Boolean) : SyncMessage()
    @Serializable @SerialName("getBlob") data class GetBlob(val name: String) : SyncMessage()
    @Serializable @SerialName("blob") data class Blob(val name: String, val data: String? = null) : SyncMessage()
    @Serializable @SerialName("putBlob") data class PutBlob(val name: String, val data: String) : SyncMessage()
    @Serializable @SerialName("ok") data class Ok(val ok: Boolean = true) : SyncMessage()
    @Serializable @SerialName("commit") data class Commit(val base: SyncBase) : SyncMessage()
    @Serializable @SerialName("busy") data object Busy : SyncMessage()
    @Serializable @SerialName("bye") data object Bye : SyncMessage()
    @Serializable @SerialName("pairHello") data class PairHello(val deviceId: String, val name: String, val addresses: List<String> = emptyList()) : SyncMessage()
    @Serializable @SerialName("pairWelcome") data class PairWelcome(val deviceId: String, val name: String, val addresses: List<String> = emptyList(), val key: String) : SyncMessage()
}

object SyncProtocol {
    const val VERSION = 1
    /** Puerto TCP fijo: así se puede escribir una dirección de Tailscale sin puerto. */
    const val PORT = 47821
    const val SERVICE_TYPE = "_trazo._tcp"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "t"
    }

    fun encode(m: SyncMessage): ByteArray = json.encodeToString(SyncMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    fun decode(b: ByteArray): SyncMessage = json.decodeFromString(SyncMessage.serializer(), b.toString(Charsets.UTF_8))

    fun encodeBase(b: SyncBase): String = json.encodeToString(SyncBase.serializer(), b)
    fun decodeBase(text: String?): SyncBase =
        text?.let { runCatching { json.decodeFromString(SyncBase.serializer(), it) }.getOrNull() } ?: SyncBase()

    /** Nombre válido de imagen o portada (direccionadas por contenido). */
    fun isValidBlob(name: String): Boolean = BLOB.matches(name)

    private val BLOB = Regex("(images|covers)/[0-9a-f]{64}\\.(webp|png)")
}

object SyncHash {
    fun of(text: String): String = of(text.toByteArray(Charsets.UTF_8))

    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Huella corta para comparar (128 bits). */
    fun short(text: String): String = of(text).substring(0, 32)

    /** Para escrituras condicionales: un archivo que no existe tiene huella fija. */
    fun fingerprint(text: String?): String = text?.let { short(it) } ?: "-"

    fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        short(TrazoFormat.json.encodeToString(serializer, value))
}
