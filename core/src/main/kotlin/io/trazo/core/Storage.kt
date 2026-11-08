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
import java.io.IOException
import java.util.zip.ZipEntry
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
    /** Imágenes de la hoja, debajo de la tinta (ver [PageImage]). */
    val images: List<PageImage> = emptyList(),
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
    val copy = Page(ids.next(), src.strokes.copiedWithNewIds(ids), src.parametricShapes.map { it.copy(id = ids.next()) },
        images = src.images.map { it.copy(id = ids.next()) })
    return copy(pages = pages.toMutableList().apply { add(index + 1, copy) })
}

fun Notebook.withPageMoved(from: Int, to: Int): Notebook {
    require(from in pages.indices && to in pages.indices) { "Cannot move page $from to $to" }
    if (from == to) return this
    return copy(pages = pages.toMutableList().apply { add(to, removeAt(from)) })
}

object PatternLibrary {
    data class MergeResult(
        val patterns: List<InkPattern>,
        /** Identificador del archivo importado → identificador que debe usar la nota en esta biblioteca. */
        val idRemap: Map<String, String>,
    )

    /**
     * Fusiona patrones por contenido funcional, no por sus identificadores ni metadatos locales.
     * Conserva nombre/activación del patrón local cuando encuentra uno equivalente. Si el ID importado
     * ya pertenece a otro patrón, agrega una copia con ID nuevo y nombre "(importado)".
     */
    fun mergeWithRemapping(
        existing: List<InkPattern>,
        incoming: List<InkPattern>,
        ids: IdGenerator = IdGenerator.RANDOM,
    ): MergeResult {
        val out = existing.toMutableList()
        val usedIds = existing.mapTo(HashSet()) { it.id }
        val byContent = LinkedHashMap<PatternContentKey, InkPattern>()
        existing.forEach { pattern ->
            val key = pattern.contentKey()
            if (key !in byContent) byContent[key] = pattern
        }
        val remap = LinkedHashMap<String, String>()

        for (p in incoming) {
            val key = p.contentKey()
            val equivalent = byContent[key]
            if (equivalent != null) {
                remapImportId(remap, p.id, equivalent.id)
                continue
            }

            if (p.id !in usedIds) {
                out += p
                usedIds += p.id
                byContent[key] = p
                remapImportId(remap, p.id, p.id)
                continue
            }

            val renamed = p.copy(name = "${p.name} (importado)", builtIn = false)
            val generatedId = ids.next()
            // Preset IDs select name-based math labels when unknown is automatic.
            val newId = if (p.id.startsWith(PresetPatterns.PREFIX)) "${PresetPatterns.PREFIX}imported-$generatedId" else generatedId
            check(newId !in usedIds) { "Pattern ID generator returned a duplicate ID: $newId" }
            var imported = renamed.copy(id = newId)
            val sourceMeaning = patternMeaning(p)
            if (p.unknown == null && sourceMeaning != patternMeaning(imported)) {
                // A custom automatic symbol depends on its pattern ID. Pin it before remapping.
                imported = imported.copy(unknown = sourceMeaning)
            }
            out += imported
            usedIds += newId
            byContent[key] = imported
            remapImportId(remap, p.id, newId)
        }
        return MergeResult(out, remap)
    }

    /** Compatibilidad con quienes solo necesitan la biblioteca, sin remapear notas. */
    fun merge(existing: List<InkPattern>, incoming: List<InkPattern>, ids: IdGenerator = IdGenerator.RANDOM): List<InkPattern> =
        mergeWithRemapping(existing, incoming, ids).patterns

    private fun remapImportId(remap: MutableMap<String, String>, importedId: String, localId: String) {
        val previous = remap[importedId]
        require(previous == null || previous == localId) { "Imported pattern ID is ambiguous: $importedId" }
        remap[importedId] = localId
    }

    private fun InkPattern.contentKey() = PatternContentKey(
        gestureExamples = gestureExamples.map { inkKey(it) },
        outputInk = inkKey(outputInk),
        sizing = sizing,
        outputScale = outputScale,
        fixedWidth = fixedWidth,
        fixedHeight = fixedHeight,
        minScore = minScore,
        holdMillis = holdMillis,
        stretch = stretch,
        floor = floor,
        core = core,
        exceedsRow = exceedsRow,
        mathMeaning = patternMeaning(this),
    )

    /**
     * La identidad de un patrón son sus gestos, salida, opciones funcionales y significado matemático
     * actual. Nombre visible, activación, categoría, builtIn y fechas son preferencias/metadatos salvo
     * cuando el nombre aporta ese significado. Los tiempos y IDs se omiten; los datos funcionales de
     * calc sí se conservan, sin comparar su ID.
     */
    private fun inkKey(strokes: List<InkStroke>): PatternInkKey {
        val calcIds = LinkedHashMap<String, Int>()
        return PatternInkKey(strokes.map { stroke ->
            PatternStrokeKey(
                points = stroke.points.map { PatternPointKey(it.x, it.y, it.pressure) },
                color = stroke.color,
                width = stroke.width,
                brush = stroke.brush,
                calc = stroke.calc?.let { tag ->
                    PatternCalcKey(
                        calcIds.getOrPut(tag.id) { calcIds.size },
                        tag.result,
                        tag.inputs,
                        tag.steps,
                        tag.failed,
                        tag.complex,
                    )
                },
            )
        })
    }

    private fun patternMeaning(pattern: InkPattern): String? =
        MathNotes.patternSymbol(pattern)?.let { symbol ->
            io.trazo.core.math.layout.MathLayoutConverter.spelling(symbol) ?: symbol
        }

    private data class PatternContentKey(
        val gestureExamples: List<PatternInkKey>,
        val outputInk: PatternInkKey,
        val sizing: OutputSizing,
        val outputScale: Float,
        val fixedWidth: Float,
        val fixedHeight: Float,
        val minScore: Float,
        val holdMillis: Long?,
        val stretch: Stretch?,
        val floor: Float?,
        val core: List<Int>?,
        val exceedsRow: Boolean?,
        val mathMeaning: String?,
    )

    private data class PatternInkKey(val strokes: List<PatternStrokeKey>)
    private data class PatternStrokeKey(
        val points: List<PatternPointKey>,
        val color: Int,
        val width: Float,
        val brush: Brush,
        val calc: PatternCalcKey?,
    )
    private data class PatternPointKey(val x: Float, val y: Float, val pressure: Float)
    private data class PatternCalcKey(
        val id: Int,
        val result: Boolean,
        val inputs: Int,
        val steps: List<String>,
        val failed: Boolean,
        val complex: Boolean,
    )
}

/** Cambia solo las referencias a patrones; conserva los IDs y la agrupación de tinta/cálculo de la nota. */
fun Notebook.remapPatternIds(idRemap: Map<String, String>): Notebook {
    if (idRemap.isEmpty()) return this
    var changed = false
    val remappedPages = pages.map { page ->
        var pageChanged = false
        val strokes = page.strokes.map { stroke ->
            val localId = stroke.patternId?.let(idRemap::get)
            if (localId == null || localId == stroke.patternId) stroke
            else stroke.copy(patternId = localId).also { pageChanged = true }
        }
        val shapes = page.parametricShapes.map { shape ->
            val localId = shape.sourcePatternId?.let(idRemap::get)
            if (localId == null || localId == shape.sourcePatternId) shape
            else shape.copy(sourcePatternId = localId).also { pageChanged = true }
        }
        if (pageChanged) page.copy(strokes = strokes, parametricShapes = shapes).also { changed = true } else page
    }
    return if (changed) copy(pages = remappedPages) else this
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
    fun encodeLazy(n: LazyNotebook): String =
        StringBuilder(n.slots.sumOf { it.json().length } + 512).also { writeLazy(n, it) }.toString()

    /**
     * Lo mismo que [encodeLazy], escrito de a partes en [out]: guardar un cuaderno largo directo a un
     * archivo no arma el texto entero en memoria.
     */
    fun writeLazy(n: LazyNotebook, out: Appendable) {
        val meta = json.encodeToString(NotebookMeta.serializer(), NotebookMeta(n.id, n.name, n.paper, n.createdAt, n.updatedAt, n.lastPage, n.coverKind, n.coverImage, n.folderId))
        out.append("{\"format\":\"").append(FORMAT).append("\",\"schemaVersion\":").append(SCHEMA_VERSION.toString()).append(",\"notebook\":")
        out.append(meta, 0, meta.length - 1).append(",\"pages\":[")
        n.slots.forEachIndexed { i, slot -> if (i > 0) out.append(','); out.append(slot.json()) }
        out.append("]}}")
    }

    /** La última hoja abierta, sin copiar las páginas; 0 si el texto no tiene la forma esperada. */
    fun lastPage(text: String): Int = runCatching {
        val scan = JsonSpans(text)
        val nb = scan.members(0).getValue("\"notebook\"")
        val r = scan.members(nb.first)["\"lastPage\""] ?: return 0
        text.substring(r.first, r.last + 1).trim().toInt()
    }.getOrDefault(0)

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
        return json.decodeFromJsonElement(PatternLibraryFile.serializer(), root).patterns.also {
            if (it.size > MAX_IMPORTED_PATTERNS) throw UnsupportedSchemaException("Too many imported patterns")
        }
    }

    /** Decodifica una biblioteca externa con límite aplicado durante la lectura del stream. */
    fun decodePatterns(input: InputStream): List<InkPattern> =
        decodePatterns(input.readBounded(MAX_PATTERNS_ENTRY_BYTES).toString(Charsets.UTF_8))

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
    internal const val IMAGE_PREFIX = "image-"
    /** Límites sobre bytes descomprimidos: el tamaño comprimido no protege de ZIP bombs. */
    internal const val MAX_NOTEBOOK_ENTRY_BYTES = 32 * 1024 * 1024
    internal const val MAX_PATTERNS_ENTRY_BYTES = 8 * 1024 * 1024
    internal const val MAX_ARCHIVE_BYTES = 48 * 1024 * 1024
    private const val MAX_UNKNOWN_ENTRY_BYTES = 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 128
    internal const val MAX_IMPORTED_PATTERNS = 20_000

    private class ArchiveLimitException(message: String) : IOException(message)

    /**
     * [cover]: bytes de la portada (WEBP o PNG) cuando el cuaderno tiene una imagen elegida.
     * [images]: archivos de las imágenes de las hojas, por nombre ([PageImage.asset] / [PageImage.source]).
     */
    class Archive(
        val notebook: Notebook,
        val patterns: List<InkPattern>,
        val cover: ByteArray? = null,
        val images: Map<String, ByteArray> = emptyMap(),
    ) {
        operator fun component1() = notebook
        operator fun component2() = patterns
    }

    /** ZIP con el cuaderno, (opcional) los patrones que usa y la portada; todo como trazos vectoriales. */
    fun exportArchive(
        notebook: Notebook,
        patterns: List<InkPattern>,
        out: OutputStream,
        cover: ByteArray? = null,
        images: Map<String, ByteArray> = emptyMap(),
    ) {
        val ext = cover?.let { Covers.extensionOf(it) }.takeIf { notebook.coverKind == CoverKind.IMAGE }
        // Una imagen sin su archivo (o con un archivo que no es lo que dice) no viaja.
        val withImages = notebook.withAvailableImages { name -> images[name]?.let(PageImages::extensionOf) == name.substringAfter('.') }
        val nb = if (ext != null) withImages else withImages.copy(coverKind = CoverKind.FIRST_PAGE, coverImage = null)
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put(MANIFEST_ENTRY, """{"format":"trazo-archive","schemaVersion":$SCHEMA_VERSION}""".toByteArray(Charsets.UTF_8))
            put(NOTEBOOK_ENTRY, encodeNotebook(nb).toByteArray(Charsets.UTF_8))
            put(PATTERNS_ENTRY, encodePatterns(patterns).toByteArray(Charsets.UTF_8))
            if (ext != null) put("$COVER_ENTRY.$ext", cover!!)
            nb.imageAssets().forEach { name -> put("$IMAGE_PREFIX$name", images.getValue(name)) }
        }
    }

    /**
     * Lee un `.trazo`. El cuaderno importado queda «Sin carpeta» (las carpetas son de cada dispositivo);
     * una portada que falta, es muy grande o no es WEBP/PNG vuelve a la primera hoja.
     */
    fun importArchive(input: InputStream): Archive {
        // Contrato de los límites de la base v2: 32 MiB cuaderno, 8 MiB patrones,
        // 48 MiB global, 128 entradas. Además exige EOCD, CRC, nombres inequívocos
        // y JSON con profundidad limitada antes de deserializarlo.
        check(ArchiveImportGuard.MAX_NOTEBOOK_BYTES == MAX_NOTEBOOK_ENTRY_BYTES &&
              ArchiveImportGuard.MAX_PATTERNS_BYTES == MAX_PATTERNS_ENTRY_BYTES &&
              ArchiveImportGuard.MAX_TOTAL_BYTES == MAX_ARCHIVE_BYTES &&
              ArchiveImportGuard.MAX_ENTRIES == MAX_ARCHIVE_ENTRIES)
        val parts = try {
            ArchiveImportGuard.read(input)
        } catch (e: ArchiveImportGuard.LimitExceeded) {
            throw ArchiveLimitException(e.message ?: "Archive limit exceeded").also { it.initCause(e) }
        } catch (e: IllegalArgumentException) {
            throw UnsupportedSchemaException(e.message ?: "Invalid archive").also { it.initCause(e) }
        }
        val notebook = decodeNotebook(parts.notebook.toString(Charsets.UTF_8)).copy(folderId = null)
        val patterns = parts.patterns?.let { decodePatterns(it.toString(Charsets.UTF_8)) }.orEmpty()
        val cover = parts.cover?.takeIf { Covers.extensionOf(it) == parts.coverName?.substringAfter('.') }
        val withCover = if (cover != null && notebook.coverKind == CoverKind.IMAGE) notebook
        else notebook.copy(coverKind = CoverKind.FIRST_PAGE, coverImage = null)
        val images = parts.images.filter { (name, bytes) -> PageImages.isValidName(name) && PageImages.extensionOf(bytes) == name.substringAfter('.') }
        val withImages = withCover.withAvailableImages { it in images }
        val used = withImages.imageAssets()
        return Archive(withImages, patterns, cover.takeIf { withImages.coverKind == CoverKind.IMAGE }, images.filterKeys { it in used })
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        val buf = ByteArray(8192)
        var count = 0
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            if (count.toLong() + n > limit) throw ArchiveLimitException("Input exceeds $limit bytes")
            out.write(buf, 0, n)
            count += n
        }
    }

}
