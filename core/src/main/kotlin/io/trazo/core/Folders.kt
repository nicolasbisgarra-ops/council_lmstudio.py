package io.trazo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Carpeta de cuadernos. Los cuadernos la nombran por [id] ([Notebook.folderId]). */
@Serializable
data class Folder(
    val id: String,
    val name: String,
    val colorArgb: Long = DEFAULT_COLOR,
    val orderKey: Int = 0,
) {
    companion object {
        const val DEFAULT_COLOR = 0xFF607D8BL
    }
}

/** La carpeta todavía tiene cuadernos: hay que vaciarla antes de eliminarla. */
class FolderNotEmptyException(val count: Int) : Exception("La carpeta tiene $count cuadernos")

/** Las carpetas del usuario, con ids únicos y en orden estable. */
data class FolderCatalog(val folders: List<Folder> = emptyList()) {
    init {
        require(folders.map { it.id }.toSet().size == folders.size) { "Duplicate folder id" }
    }

    /** Por [Folder.orderKey] y, a igual clave, por id. */
    fun ordered(): List<Folder> = folders.sortedWith(compareBy<Folder>({ it.orderKey }, { it.id }))

    operator fun get(id: String?): Folder? = folders.firstOrNull { it.id == id }

    fun add(name: String, colorArgb: Long = Folder.DEFAULT_COLOR, ids: IdGenerator = IdGenerator.RANDOM): Pair<FolderCatalog, Folder> {
        val folder = Folder(ids.next(), cleanName(name), colorArgb, (folders.maxOfOrNull { it.orderKey } ?: -1) + 1)
        return FolderCatalog(folders + folder) to folder
    }

    fun renamed(id: String, name: String): FolderCatalog = edit(id) { it.copy(name = cleanName(name)) }

    fun recolored(id: String, colorArgb: Long): FolderCatalog = edit(id) { it.copy(colorArgb = colorArgb) }

    /** Elimina la carpeta solo si ningún cuaderno de [notebookFolders] la usa. */
    fun removed(id: String, notebookFolders: Collection<String?>): FolderCatalog {
        val count = notebookFolders.count { it == id }
        if (count > 0) throw FolderNotEmptyException(count)
        return FolderCatalog(folders.filter { it.id != id })
    }

    private fun edit(id: String, f: (Folder) -> Folder): FolderCatalog {
        require(folders.any { it.id == id }) { "No folder $id" }
        return FolderCatalog(folders.map { if (it.id == id) f(it) else it })
    }

    companion object {
        const val MAX_NAME = 60

        fun cleanName(name: String): String = name.trim().take(MAX_NAME).ifEmpty { "Carpeta" }

        fun encode(c: FolderCatalog): String =
            TrazoFormat.json.encodeToString(FoldersFile.serializer(), FoldersFile(schemaVersion = TrazoFormat.SCHEMA_VERSION, folders = c.folders))

        /** Un archivo dañado o de otra versión no impide abrir la lista: queda sin carpetas. */
        fun decode(text: String?): FolderCatalog {
            if (text.isNullOrBlank()) return FolderCatalog()
            val file = runCatching { TrazoFormat.json.decodeFromString(FoldersFile.serializer(), text) }.getOrNull()
            if (file == null || file.format != FOLDERS_FORMAT || file.schemaVersion > TrazoFormat.SCHEMA_VERSION) return FolderCatalog()
            // Ids repetidos (archivo editado a mano): queda la primera.
            return FolderCatalog(file.folders.distinctBy { it.id }.map { it.copy(name = cleanName(it.name)) })
        }
    }
}

private const val FOLDERS_FORMAT = "trazo-folders"

@Serializable
private data class FoldersFile(val format: String = FOLDERS_FORMAT, val schemaVersion: Int, val folders: List<Folder>)

/**
 * Orden manual de los cuadernos: la lista de ids tal como el usuario los acomodó. Los cuadernos que no
 * figuran (nuevos o importados) van primero, del más reciente al más viejo.
 */
object NotebookOrder {
    fun encode(ids: List<String>): String = TrazoFormat.json.encodeToString(ListSerializer(String.serializer()), ids.distinct())

    fun decode(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList()
        else runCatching { TrazoFormat.json.decodeFromString(ListSerializer(String.serializer()), text).distinct() }.getOrDefault(emptyList())

    /** [items] en orden manual; [id] da el id de cada uno y [recency] desempata los que no están en [order]. */
    fun <T> sorted(items: List<T>, order: List<String>, id: (T) -> String, recency: (T) -> Long): List<T> {
        val pos = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, s -> putIfAbsent(s, i) } }
        val (known, unknown) = items.partition { id(it) in pos }
        return unknown.sortedByDescending(recency) + known.sortedBy { pos.getValue(id(it)) }
    }

    /** Mueve [moving] justo antes de [target] (o al final si [target] es null) dentro de [current]. */
    fun moved(current: List<String>, moving: String, target: String?): List<String> {
        if (moving == target || moving !in current) return current
        val rest = current.filter { it != moving }
        val at = if (target == null) rest.size else rest.indexOf(target).takeIf { it >= 0 } ?: return current
        return rest.toMutableList().apply { add(at, moving) }
    }

    /**
     * El orden completo [full] con los ids visibles reacomodados como [visible] (una carpeta o la lista
     * entera): ocupan los mismos lugares que antes y el resto no se mueve. Ids de [visible] que no están
     * en [full] van al final.
     */
    fun withSubset(full: List<String>, visible: List<String>): List<String> {
        val set = visible.toHashSet()
        val next = visible.filter { it in full }.iterator()
        val merged = full.map { if (it in set && next.hasNext()) next.next() else it }
        return (merged + visible.filter { it !in full }).distinct()
    }
}

/** Portadas: imágenes guardadas como `covers/<sha256>.webp` (o `.png`), referidas por nombre. */
object Covers {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_SIDE = 1600

    private val NAME = Regex("[0-9a-f]{64}\\.(webp|png)")

    fun isValidName(name: String?): Boolean = name != null && NAME.matches(name)

    /** Una portada de imagen sin un nombre válido vuelve a la primera hoja. */
    fun normalized(kind: CoverKind, image: String?): Pair<CoverKind, String?> =
        if (kind == CoverKind.IMAGE && isValidName(image)) kind to image else CoverKind.FIRST_PAGE to null

    fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 && b.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && b.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())

    fun isPng(b: ByteArray): Boolean =
        b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    /** Extensión de la imagen o null si no es una portada aceptable (tamaño o formato). */
    fun extensionOf(b: ByteArray): String? = when {
        b.isEmpty() || b.size > MAX_BYTES -> null
        isWebp(b) -> "webp"
        isPng(b) -> "png"
        else -> null
    }
}

fun Notebook.normalizedCover(): Notebook {
    val (k, img) = Covers.normalized(coverKind, coverImage)
    return if (k == coverKind && img == coverImage) this else copy(coverKind = k, coverImage = img)
}
