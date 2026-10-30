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
        val meta = json.encodeToString(NotebookMeta.serializer(), NotebookMeta(n.id, n.name, n.paper, n.createdAt, n.updatedAt, n.lastPage))
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
        return LazyNotebook(meta.id, meta.name, slots, meta.paper, meta.createdAt, meta.updatedAt, meta.lastPage)
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

    data class Archive(val notebook: Notebook, val patterns: List<InkPattern>)

    /** ZIP con el cuaderno y (opcional) los patrones que usa; todo como trazos vectoriales. */
    fun exportArchive(notebook: Notebook, patterns: List<InkPattern>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put(MANIFEST_ENTRY, """{"format":"trazo-archive","schemaVersion":$SCHEMA_VERSION}""")
            put(NOTEBOOK_ENTRY, encodeNotebook(notebook))
            put(PATTERNS_ENTRY, encodePatterns(patterns))
        }
    }

    fun importArchive(input: InputStream): Archive {
        var notebook: Notebook? = null
        var patterns: List<InkPattern> = emptyList()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val bytes = ByteArrayOutputStream().also { zip.copyTo(it) }.toByteArray()
                val text = bytes.toString(Charsets.UTF_8)
                when (e.name) {
                    NOTEBOOK_ENTRY -> notebook = decodeNotebook(text)
                    PATTERNS_ENTRY -> patterns = decodePatterns(text)
                }
            }
        }
        return Archive(notebook ?: throw UnsupportedSchemaException("Archive without $NOTEBOOK_ENTRY"), patterns)
    }
}
