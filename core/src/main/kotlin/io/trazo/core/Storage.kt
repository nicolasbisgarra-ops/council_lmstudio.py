package io.trazo.core

import kotlinx.serialization.Serializable
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
data class Page(val id: String, val strokes: List<InkStroke> = emptyList())

/** Cuaderno: la fuente de verdad son los trazos; nunca se guarda solo como imagen. */
@Serializable
data class Notebook(
    val id: String,
    val name: String,
    val pages: List<Page>,
    val paper: Paper = Paper.LINES,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
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
