package io.trazo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
enum class Paper { PLAIN, LINES, GRID, DOTS }

/** Qué se ve en la tapa del cuaderno: la primera hoja o una imagen elegida ([Notebook.coverImage]). */
@Serializable
enum class CoverKind { FIRST_PAGE, IMAGE }

@Serializable
data class Page(
    val id: String,
    val strokes: List<InkStroke> = emptyList(),
    /** Formas paramétricas editables; su tinta se deriva al dibujar ([allInk]). */
    val parametricShapes: List<ParametricShape> = emptyList(),
    /** Marcas e historial del Modo IA; null en las hojas que nunca se revisaron. */
    val ai: PageAi? = null,
) {
    /** Trazos más la tinta de las formas, en orden de dibujo. */
    val allInk: List<InkStroke>
        get() = if (parametricShapes.isEmpty()) strokes else strokes + parametricShapes.flatMap { it.render() }
}

/** Cuaderno: la fuente de verdad son los trazos; nunca se guarda solo como imagen. */
@Serializable
data class Notebook(
    val id: String,
    val name: String,
    val pages: List<Page>,
    val paper: Paper = Paper.LINES,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Última página abierta: el cuaderno vuelve a abrirse ahí. */
    val lastPage: Int = 0,
    val coverKind: CoverKind = CoverKind.FIRST_PAGE,
    /** Nombre del archivo de la portada (ver [Covers]); solo vale con [CoverKind.IMAGE]. */
    val coverImage: String? = null,
    /** Carpeta del cuaderno ([Folder.id]); null es «Sin carpeta». */
    val folderId: String? = null,
) {
    init { require(pages.isNotEmpty()) { "A notebook needs at least one page" } }

    companion object {
        /** Tamaño lógico de página (unidades de documento), proporción A4. */
        const val PAGE_WIDTH = 1000f
        const val PAGE_HEIGHT = 1414f

        fun new(name: String, ids: IdGenerator = IdGenerator.RANDOM, now: Long = System.currentTimeMillis()) =
            Notebook(ids.next(), name, listOf(Page(ids.next())), createdAt = now, updatedAt = now)
    }
}

fun Notebook.withPageRemoved(index: Int): Notebook {
    require(pages.size > 1 && index in pages.indices) { "Cannot remove page $index" }
    return copy(pages = pages.filterIndexed { i, _ -> i != index })
}

/** Inserta una copia profunda de la página justo después: id de página y de trazos nuevos, grupos reasignados. */
fun Notebook.withPageDuplicated(index: Int, ids: IdGenerator = IdGenerator.RANDOM): Notebook {
    require(index in pages.indices) { "No page $index" }
    val src = pages[index]
    val copy = Page(ids.next(), src.strokes.copiedWithNewIds(ids), src.parametricShapes.map { it.copy(id = ids.next()) })
    return copy(pages = pages.toMutableList().apply { add(index + 1, copy) })
}

fun Notebook.withPageMoved(from: Int, to: Int): Notebook {
    require(from in pages.indices && to in pages.indices) { "Cannot move page $from to $to" }
    if (from == to) return this
    return copy(pages = pages.toMutableList().apply { add(to, removeAt(from)) })
}

object PatternLibrary {
    /** Fusiona patrones importados con la biblioteca: los idénticos (ignorando updatedAt y enabled) se omiten; mismo id con contenido distinto → id nuevo y nombre "<nombre> (importado)"; el resto se agrega. Conserva el orden: existentes y luego nuevos. */
    fun merge(existing: List<InkPattern>, incoming: List<InkPattern>, ids: IdGenerator = IdGenerator.RANDOM): List<InkPattern> {
        val out = existing.toMutableList()
        for (p in incoming) {
            val key = p.contentKey()
            if (out.any { it.contentKey() == key }) continue
            if (out.none { it.id == p.id }) { out += p; continue }
            val renamed = p.copy(name = "${p.name} (importado)", builtIn = false)
            // Reimportar el mismo archivo no debe duplicar la copia renombrada.
            if (out.any { it.contentKey().copy(id = p.id) == renamed.contentKey() }) continue
            out += renamed.copy(id = ids.next())
        }
        return out
    }

    private fun InkPattern.contentKey() = copy(updatedAt = 0L, enabled = true)
}

@Serializable
private data class NotebookFile(val format: String = FORMAT, val schemaVersion: Int, val notebook: Notebook)

@Serializable
private data class PatternLibraryFile(val format: String = PATTERNS_FORMAT, val schemaVersion: Int, val patterns: List<InkPattern>)

private const val FORMAT = "trazo-notebook"
private const val PATTERNS_FORMAT = "trazo-patterns"

class UnsupportedSchemaException(message: String) : Exception(message)

/** Formatos de archivo versionados de Trazo. */
object TrazoFormat {
    const val SCHEMA_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        // Un valor desconocido (p. ej. un tipo de portada de una versión futura) toma el predeterminado
        // en vez de dejar el cuaderno sin abrir.
        coerceInputValues = true
    }

    fun encodeNotebook(n: Notebook): String =
        json.encodeToString(NotebookFile.serializer(), NotebookFile(schemaVersion = SCHEMA_VERSION, notebook = n))

    fun decodeNotebook(text: String): Notebook {
        val root = migrate(json.parseToJsonElement(text).jsonObject, FORMAT)
        return json.decodeFromJsonElement(NotebookFile.serializer(), root).notebook
    }

    fun encodePage(p: Page): String = json.encodeToString(Page.serializer(), p)

    fun decodePage(text: String): Page = json.decodeFromString(Page.serializer(), text)

    /** Mismo archivo que [encodeNotebook]; las páginas fijas se copian tal cual, sin decodificarlas. */
    fun encodeLazy(n: LazyNotebook): String {
        val meta = json.encodeToString(NotebookMeta.serializer(), NotebookMeta(n.id, n.name, n.paper, n.createdAt, n.updatedAt, n.lastPage, n.coverKind, n.coverImage, n.folderId))
        val out = StringBuilder(n.slots.sumOf { it.json().length } + meta.length + 96)
        out.append("{\"format\":\"").append(FORMAT).append("\",\"schemaVersion\":").append(SCHEMA_VERSION).append(",\"notebook\":")
        out.append(meta, 0, meta.length - 1).append(",\"pages\":[")
        n.slots.forEachIndexed { i, slot -> if (i > 0) out.append(','); out.append(slot.json()) }
        return out.append("]}}").toString()
    }

    /**
     * Lee un cuaderno dejando todas las páginas fijas como texto: abrir un cuaderno largo no decodifica
     * sus trazos. Si el archivo no tiene la forma esperada, lo lee entero como siempre.
     */
    fun decodeLazy(text: String): LazyNotebook = runCatching { splitLazy(text) }.getOrElse { LazyNotebook.of(decodeNotebook(text)) }

    private fun splitLazy(text: String): LazyNotebook {
        val scan = JsonSpans(text)
        val root = scan.members(0)
        root["\"format\""]?.let { r ->
            val format = json.decodeFromString(String.serializer(), text.substring(r.first, r.last + 1))
            if (format != FORMAT) throw UnsupportedSchemaException("Expected $FORMAT, got $format")
        }
        root["\"schemaVersion\""]?.let { r ->
            val version = text.substring(r.first, r.last + 1).trim().toInt()
            if (version > SCHEMA_VERSION) throw UnsupportedSchemaException("Schema $version is newer than $SCHEMA_VERSION")
        }
        val nb = root.getValue("\"notebook\"")
        val members = scan.members(nb.first)
        val pages = members.getValue("\"pages\"")
        val slots = scan.elements(pages.first).map { r ->
            val pageText = text.substring(r.first, r.last + 1)
            val idRange = scan.members(r.first).getValue("\"id\"")
            val id = json.decodeFromString(String.serializer(), text.substring(idRange.first, idRange.last + 1))
            PageSlot.frozen(id, pageText)
        }
        val metaText = text.substring(nb.first, pages.first) + "[]" + text.substring(pages.last + 1, nb.last + 1)
        val meta = json.decodeFromString(NotebookMeta.serializer(), metaText)
        return LazyNotebook(
            meta.id, meta.name, slots, meta.paper, meta.createdAt, meta.updatedAt, meta.lastPage,
            meta.coverKind, meta.coverImage, meta.folderId,
        )
    }

    fun encodePatterns(p: List<InkPattern>): String =
        json.encodeToString(PatternLibraryFile.serializer(), PatternLibraryFile(schemaVersion = SCHEMA_VERSION, patterns = p))

    fun decodePatterns(text: String): List<InkPattern> {
        val root = migrate(json.parseToJsonElement(text).jsonObject, PATTERNS_FORMAT)
        return json.decodeFromJsonElement(PatternLibraryFile.serializer(), root).patterns
    }

    /** Punto único para migraciones futuras de esquema. */
    private fun migrate(root: JsonObject, expectedFormat: String): JsonObject {
        val format = root["format"]?.jsonPrimitive?.content
        if (format != null && format != expectedFormat) throw UnsupportedSchemaException("Expected $expectedFormat, got $format")
        val version = root["schemaVersion"]?.jsonPrimitive?.int ?: 1
        if (version > SCHEMA_VERSION) throw UnsupportedSchemaException("Schema $version is newer than $SCHEMA_VERSION")
        return root
    }

    // ------------------------------------------------------------ exportación editable (.trazo = ZIP)

    private const val NOTEBOOK_ENTRY = "notebook.json"
    private const val PATTERNS_ENTRY = "patterns.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val COVER_ENTRY = "cover"

    /** [cover]: bytes de la portada (WEBP o PNG) cuando el cuaderno tiene una imagen elegida. */
    class Archive(val notebook: Notebook, val patterns: List<InkPattern>, val cover: ByteArray? = null) {
        operator fun component1() = notebook
        operator fun component2() = patterns
    }

    /** ZIP con el cuaderno, (opcional) los patrones que usa y la portada; todo como trazos vectoriales. */
    fun exportArchive(notebook: Notebook, patterns: List<InkPattern>, out: OutputStream, cover: ByteArray? = null) {
        val ext = cover?.let { Covers.extensionOf(it) }.takeIf { notebook.coverKind == CoverKind.IMAGE }
        val nb = if (ext != null) notebook else notebook.copy(coverKind = CoverKind.FIRST_PAGE, coverImage = null)
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put(MANIFEST_ENTRY, """{"format":"trazo-archive","schemaVersion":$SCHEMA_VERSION}""".toByteArray(Charsets.UTF_8))
            put(NOTEBOOK_ENTRY, encodeNotebook(nb).toByteArray(Charsets.UTF_8))
            put(PATTERNS_ENTRY, encodePatterns(patterns).toByteArray(Charsets.UTF_8))
            if (ext != null) put("$COVER_ENTRY.$ext", cover!!)
        }
    }

    /**
     * Lee un `.trazo`. El cuaderno importado queda «Sin carpeta» (las carpetas son de cada dispositivo);
     * una portada que falta, es muy grande o no es WEBP/PNG vuelve a la primera hoja.
     */
    fun importArchive(input: InputStream): Archive {
        var notebook: Notebook? = null
        var patterns: List<InkPattern> = emptyList()
        var cover: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    NOTEBOOK_ENTRY -> notebook = decodeNotebook(zip.readAll().toString(Charsets.UTF_8))
                    PATTERNS_ENTRY -> patterns = decodePatterns(zip.readAll().toString(Charsets.UTF_8))
                    "$COVER_ENTRY.webp", "$COVER_ENTRY.png" ->
                        cover = zip.readAll(Covers.MAX_BYTES + 1)?.takeIf { Covers.extensionOf(it) == e.name.substringAfter('.') }
                }
            }
        }
        val nb = (notebook ?: throw UnsupportedSchemaException("Archive without $NOTEBOOK_ENTRY")).copy(folderId = null)
        val withCover = if (cover != null && nb.coverKind == CoverKind.IMAGE) nb
        else nb.copy(coverKind = CoverKind.FIRST_PAGE, coverImage = null)
        return Archive(withCover, patterns, cover.takeIf { withCover.coverKind == CoverKind.IMAGE })
    }

    private fun InputStream.readAll(): ByteArray = ByteArrayOutputStream().also { copyTo(it) }.toByteArray()

    /** Hasta [limit] bytes; null si la entrada es más larga. */
    private fun InputStream.readAll(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() >= limit) return null
        }
    }
}
