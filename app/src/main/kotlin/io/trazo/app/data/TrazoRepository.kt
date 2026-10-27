package io.trazo.app.data

import android.util.AtomicFile
import io.trazo.core.DefaultPatterns
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.PatternLibrary
import io.trazo.core.PatternUpgrades
import io.trazo.core.TrazoFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class NotebookSummary(val id: String, val name: String, val pageCount: Int, val updatedAt: Long, val firstPage: Page)

/**
 * Persistencia local, offline. Cada cuaderno es un JSON versionado escrito de forma atómica
 * ([AtomicFile]); la biblioteca de patrones vive en `patterns.json`. Se guardan los trazos
 * originales, nunca solo una imagen.
 */
class TrazoRepository(root: File) {
    private val notebooksDir = File(root, "notebooks").apply { mkdirs() }
    private val patternsFile = AtomicFile(File(root, "patterns.json"))
    private val lock = Any()

    private val _patterns = MutableStateFlow(loadPatterns())
    val patterns: StateFlow<List<InkPattern>> = _patterns.asStateFlow()

    // ------------------------------------------------------------------ patrones

    private fun loadPatterns(): List<InkPattern> = synchronized(lock) {
        if (!patternsFile.baseFile.exists()) return DefaultPatterns.all().also { writePatterns(it) }
        val decoded = runCatching { TrazoFormat.decodePatterns(patternsFile.readFully().toString(Charsets.UTF_8)) }
            .getOrElse { return DefaultPatterns.all() }
        // Patrones de ejemplo guardados con una versión anterior: se completan sin perder los ejemplos del usuario.
        val upgraded = PatternUpgrades.upgrade(decoded)
        if (upgraded != decoded) runCatching { writePatterns(upgraded) }
        upgraded
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

    /** Vuelve a agregar (o restaura) los patrones de ejemplo sin tocar los del usuario. */
    fun restoreDefaultPatterns() {
        val defaults = DefaultPatterns.all()
        val list = _patterns.value.filterNot { p -> defaults.any { it.id == p.id } } + defaults
        _patterns.value = list
        writePatterns(list)
    }

    // ------------------------------------------------------------------ cuadernos

    private fun fileFor(id: String) = AtomicFile(File(notebooksDir, "${safe(id)}.trazo.json"))

    fun listNotebooks(): List<NotebookSummary> = synchronized(lock) {
        notebooksDir.listFiles { f -> f.name.endsWith(".trazo.json") }.orEmpty()
            .mapNotNull { f -> runCatching { TrazoFormat.decodeNotebook(AtomicFile(f).readFully().toString(Charsets.UTF_8)) }.getOrNull() }
            .map { NotebookSummary(it.id, it.name, it.pages.size, it.updatedAt, it.pages.first()) }
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
