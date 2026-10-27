package io.trazo.app.data

import android.util.AtomicFile
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.PatternCategory
import io.trazo.core.PatternLibrary
import io.trazo.core.PatternUpgrades
import io.trazo.core.PresetPatterns
import io.trazo.core.TrazoFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class NotebookSummary(
    val id: String,
    val name: String,
    val pageCount: Int,
    val updatedAt: Long,
    val firstPage: Page,
    val paper: Paper = Paper.LINES,
)

/**
 * Persistencia local, offline. Cada cuaderno es un JSON versionado escrito de forma atómica
 * ([AtomicFile]); la biblioteca de patrones vive en `patterns.json` y `presets-version` recuerda qué
 * versión de la biblioteca de partida ([PresetPatterns]) ya se instaló. Se guardan los trazos
 * originales, nunca solo una imagen.
 */
class TrazoRepository(root: File) {
    private val notebooksDir = File(root, "notebooks").apply { mkdirs() }
    private val patternsFile = AtomicFile(File(root, "patterns.json"))
    private val presetsFile = File(root, "presets-version")
    private val lock = Any()

    private val _patterns = MutableStateFlow(loadPatterns())
    val patterns: StateFlow<List<InkPattern>> = _patterns.asStateFlow()

    // ------------------------------------------------------------------ patrones

    private fun loadPatterns(): List<InkPattern> = synchronized(lock) {
        val fresh = !patternsFile.baseFile.exists()
        val decoded = if (fresh) emptyList() else {
            runCatching { TrazoFormat.decodePatterns(patternsFile.readFully().toString(Charsets.UTF_8)) }
                .getOrElse { return PresetPatterns.install(emptyList(), 0) }
        }
        // Una biblioteca guardada antes de existir `presets-version` ya tiene el núcleo (versión 1).
        val installed = if (fresh) 0 else presetsFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 1
        // Primero se completan los guardados con una versión anterior (y los del usuario reciben su nombre),
        // así los signos nuevos ya ven esos nombres al instalarse.
        var list = PatternUpgrades.upgrade(decoded)
        if (installed < PresetPatterns.VERSION) list = PresetPatterns.install(list, installed)
        runCatching {
            if (fresh || list != decoded) writePatterns(list)
            if (installed < PresetPatterns.VERSION) presetsFile.writeText(PresetPatterns.VERSION.toString())
        }
        list
    }

    private fun writePatterns(list: List<InkPattern>) = synchronized(lock) {
        write(patternsFile, TrazoFormat.encodePatterns(list))
    }

    fun upsertPattern(p: InkPattern) {
        val list = _patterns.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        val stamped = p.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list += stamped
        _patterns.value = list
        writePatterns(list)
    }

    fun deletePattern(id: String) {
        val list = _patterns.value.filterNot { it.id == id }
        _patterns.value = list
        writePatterns(list)
    }

    fun setPatternEnabled(id: String, enabled: Boolean) {
        _patterns.value.firstOrNull { it.id == id }?.let { upsertPattern(it.copy(enabled = enabled)) }
    }

    /** Activa o desactiva de una vez todos los patrones de una sección ([PatternCategory]). */
    fun setCategoryEnabled(category: String, enabled: Boolean) =
        setPatternsEnabled(_patterns.value.filter { PatternCategory.of(it) == category }.map { it.id }, enabled)

    /** Activa o desactiva varios patrones con una sola escritura (una sección o un resultado de búsqueda). */
    fun setPatternsEnabled(ids: Collection<String>, enabled: Boolean) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val wanted = ids.toSet()
        val list = _patterns.value.map {
            if (it.id in wanted && it.enabled != enabled) it.copy(enabled = enabled, updatedAt = now) else it
        }
        _patterns.value = list
        writePatterns(list)
    }

    /** Biblioteca completa como JSON versionado, para llevar los patrones a otro dispositivo. */
    fun exportPatterns(out: OutputStream) {
        out.write(TrazoFormat.encodePatterns(patterns.value).toByteArray(Charsets.UTF_8))
    }

    /** Fusiona los patrones de un archivo exportado; devuelve cuántos se agregaron. */
    fun importPatterns(input: InputStream): Int =
        mergePatterns(TrazoFormat.decodePatterns(input.readBytes().toString(Charsets.UTF_8)))

    private fun mergePatterns(incoming: List<InkPattern>): Int = synchronized(lock) {
        val before = _patterns.value
        val merged = PatternLibrary.merge(before, PatternUpgrades.upgrade(incoming))
        _patterns.value = merged
        writePatterns(merged)
        merged.size - before.size
    }

    /** Restaura integral, raíz y fracción y vuelve a agregar los signos que falten, sin tocar los del usuario. */
    fun restoreDefaultPatterns() = synchronized(lock) {
        val list = PresetPatterns.restore(_patterns.value)
        _patterns.value = list
        writePatterns(list)
    }

    // ------------------------------------------------------------------ cuadernos

    private fun fileFor(id: String) = AtomicFile(File(notebooksDir, "${safe(id)}.trazo.json"))

    fun listNotebooks(): List<NotebookSummary> = synchronized(lock) {
        notebooksDir.listFiles { f -> f.name.endsWith(".trazo.json") }.orEmpty()
            .mapNotNull { f -> runCatching { TrazoFormat.decodeNotebook(AtomicFile(f).readFully().toString(Charsets.UTF_8)) }.getOrNull() }
            .map { NotebookSummary(it.id, it.name, it.pages.size, it.updatedAt, it.pages.first(), it.paper) }
            .sortedByDescending { it.updatedAt }
    }

    fun loadNotebook(id: String): Notebook? = synchronized(lock) {
        val f = fileFor(id)
        if (!f.baseFile.exists()) return null
        runCatching { TrazoFormat.decodeNotebook(f.readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    fun saveNotebook(n: Notebook) = synchronized(lock) {
        write(fileFor(n.id), TrazoFormat.encodeNotebook(n))
    }

    fun createNotebook(name: String, paper: Paper = Paper.LINES): Notebook =
        Notebook.new(name.ifBlank { "Cuaderno" }).copy(paper = paper).also { saveNotebook(it) }

    fun renameNotebook(id: String, name: String) {
        loadNotebook(id)?.let { saveNotebook(it.copy(name = name, updatedAt = System.currentTimeMillis())) }
    }

    fun deleteNotebook(id: String) = synchronized(lock) { fileFor(id).delete() }

    fun exportNotebook(id: String, out: OutputStream) {
        val n = loadNotebook(id) ?: error("Notebook $id not found")
        val used = n.pages.flatMap { p -> p.strokes.mapNotNull { it.patternId } }.toSet()
        TrazoFormat.exportArchive(n, patterns.value.filter { it.id in used }, out)
    }

    /** Importa un `.trazo`; si el id ya existe crea una copia. Agrega patrones que falten. */
    fun importNotebook(input: InputStream): Notebook {
        val archive = TrazoFormat.importArchive(input)
        var n = archive.notebook
        if (fileFor(n.id).baseFile.exists()) n = n.copy(id = IdGenerator.RANDOM.next(), name = n.name + " (importado)")
        n = n.copy(updatedAt = System.currentTimeMillis())
        saveNotebook(n)
        mergePatterns(archive.patterns)
        return n
    }

    private fun write(file: AtomicFile, text: String) {
        val out = file.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
