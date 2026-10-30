package io.trazo.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.AtomicFile
import io.trazo.core.CoverKind
import io.trazo.core.Covers
import io.trazo.core.Folder
import io.trazo.core.FolderCatalog
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.PatternCategory
import io.trazo.core.PatternLibrary
import io.trazo.core.PatternUpgrades
import io.trazo.core.PresetPatterns
import io.trazo.core.LazyNotebook
import io.trazo.core.NotebookOrder
import io.trazo.core.TrazoFormat
import io.trazo.core.normalizedCover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class NotebookSummary(
    val id: String,
    val name: String,
    val pageCount: Int,
    val updatedAt: Long,
    val firstPage: Page,
    val paper: Paper = Paper.LINES,
    val folderId: String? = null,
    /** Archivo de la portada elegida; null muestra la primera hoja. */
    val coverFile: File? = null,
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
    private val foldersFile = AtomicFile(File(root, "folders.json"))
    private val orderFile = AtomicFile(File(root, "notebook-order.json"))
    private val coversDir = File(root, "covers").apply { mkdirs() }
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
            // Para la portada alcanza con decodificar la primera página.
            .mapNotNull { f -> runCatching { TrazoFormat.decodeLazy(AtomicFile(f).readFully().toString(Charsets.UTF_8)) }.getOrNull() }
            .map {
                val (kind, image) = Covers.normalized(it.coverKind, it.coverImage)
                val cover = image?.let { name -> File(coversDir, name) }?.takeIf { f -> kind == CoverKind.IMAGE && f.isFile }
                NotebookSummary(it.id, it.name, it.pageCount, it.updatedAt, it.page(0), it.paper, it.folderId, cover)
            }
            .sortedByDescending { it.updatedAt }
    }

    // ------------------------------------------------------------------ carpetas y orden manual

    fun folders(): FolderCatalog = synchronized(lock) {
        FolderCatalog.decode(runCatching { foldersFile.readFully().toString(Charsets.UTF_8) }.getOrNull())
    }

    private fun saveFolders(c: FolderCatalog) = write(foldersFile, FolderCatalog.encode(c))

    fun addFolder(name: String, colorArgb: Long = Folder.DEFAULT_COLOR): Folder = synchronized(lock) {
        val (c, f) = folders().add(name, colorArgb)
        saveFolders(c)
        f
    }

    fun renameFolder(id: String, name: String) = synchronized(lock) { saveFolders(folders().renamed(id, name)) }

    fun recolorFolder(id: String, colorArgb: Long) = synchronized(lock) { saveFolders(folders().recolored(id, colorArgb)) }

    /** Elimina una carpeta vacía; si tiene cuadernos lanza [io.trazo.core.FolderNotEmptyException]. */
    fun deleteFolder(id: String) = synchronized(lock) {
        saveFolders(folders().removed(id, listNotebooks().map { it.folderId }))
    }

    /** Mueve el cuaderno a [folderId] (null: «Sin carpeta») sin cambiar su fecha de modificación. */
    fun moveToFolder(id: String, folderId: String?) = synchronized(lock) {
        if (folderId != null && folders()[folderId] == null) return
        loadLazy(id)?.let { if (it.folderId != folderId) saveLazy(it.copy(folderId = folderId)) }
    }

    fun manualOrder(): List<String> = synchronized(lock) {
        NotebookOrder.decode(runCatching { orderFile.readFully().toString(Charsets.UTF_8) }.getOrNull())
    }

    /** Guarda el orden manual tal como se ve ([ids] completos, de arriba hacia abajo). */
    fun saveManualOrder(ids: List<String>) = synchronized(lock) { write(orderFile, NotebookOrder.encode(ids)) }

    // ------------------------------------------------------------------ portadas

    /**
     * Usa la imagen como portada: se reduce a [Covers.MAX_SIDE] px, se endereza según su EXIF y se guarda
     * como WEBP de hasta [Covers.MAX_BYTES] en `covers/<sha256>.webp`. [open] se llama más de una vez.
     */
    fun setCoverImage(id: String, open: () -> InputStream) {
        val bytes = encodeCover(open) ?: error("La imagen no se pudo leer")
        val name = sha256(bytes) + ".webp"
        synchronized(lock) {
            val n = loadLazy(id) ?: return
            val f = File(coversDir, name)
            if (!f.isFile) write(AtomicFile(f), bytes)
            saveLazy(n.copy(coverKind = CoverKind.IMAGE, coverImage = name))
            n.coverImage?.let { dropCoverIfUnused(it) }
        }
    }

    /** Vuelve a mostrar la primera hoja; la imagen se borra si ningún otro cuaderno la usa. */
    fun clearCover(id: String) = synchronized(lock) {
        val n = loadLazy(id) ?: return
        if (n.coverKind == CoverKind.FIRST_PAGE && n.coverImage == null) return
        saveLazy(n.copy(coverKind = CoverKind.FIRST_PAGE, coverImage = null))
        n.coverImage?.let { dropCoverIfUnused(it) }
    }

    private fun dropCoverIfUnused(name: String) {
        if (!Covers.isValidName(name)) return
        val used = notebooksDir.listFiles { f -> f.name.endsWith(".trazo.json") }.orEmpty().any { f ->
            runCatching { TrazoFormat.decodeLazy(AtomicFile(f).readFully().toString(Charsets.UTF_8)).coverImage == name }.getOrDefault(true)
        }
        if (!used) File(coversDir, name).delete()
    }

    private fun coverBytes(n: Notebook): ByteArray? =
        n.coverImage?.takeIf { n.coverKind == CoverKind.IMAGE && Covers.isValidName(it) }
            ?.let { File(coversDir, it) }?.takeIf { it.isFile }?.readBytes()

    private fun encodeCover(open: () -> InputStream): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Covers.MAX_SIDE) sample *= 2
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val scale = minOf(1f, Covers.MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale < 1f) m.postScale(scale, scale)
        val bmp = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        try {
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                bmp.compress(format, quality, out)
                val bytes = out.toByteArray()
                if (Covers.extensionOf(bytes) == "webp") return bytes
            }
            return null
        } finally {
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun loadNotebook(id: String): Notebook? = synchronized(lock) {
        val f = fileFor(id)
        if (!f.baseFile.exists()) return null
        runCatching { TrazoFormat.decodeNotebook(f.readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    /** El cuaderno con las páginas fijas, sin decodificar sus trazos (lo que usa el editor). */
    fun loadLazy(id: String): LazyNotebook? = synchronized(lock) {
        val f = fileFor(id)
        if (!f.baseFile.exists()) return null
        runCatching { TrazoFormat.decodeLazy(f.readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    fun saveLazy(n: LazyNotebook) = synchronized(lock) {
        write(fileFor(n.id), TrazoFormat.encodeLazy(n))
    }

    fun saveNotebook(n: Notebook) = synchronized(lock) {
        write(fileFor(n.id), TrazoFormat.encodeNotebook(n))
    }

    fun renameNotebook(id: String, name: String) {
        loadNotebook(id)?.let { saveNotebook(it.copy(name = name, updatedAt = System.currentTimeMillis())) }
    }

    fun createNotebook(name: String, paper: Paper = Paper.LINES, folderId: String? = null): Notebook =
        Notebook.new(name.ifBlank { "Cuaderno" }).copy(paper = paper, folderId = folderId?.takeIf { folders()[it] != null })
            .also { saveNotebook(it) }

    fun deleteNotebook(id: String) = synchronized(lock) {
        val cover = loadLazy(id)?.coverImage
        fileFor(id).delete()
        cover?.let { dropCoverIfUnused(it) }
    }

    fun exportNotebook(id: String, out: OutputStream) {
        val n = loadNotebook(id) ?: error("Notebook $id not found")
        val used = n.pages.flatMap { p -> p.strokes.mapNotNull { it.patternId } }.toSet()
        TrazoFormat.exportArchive(n, patterns.value.filter { it.id in used }, out, coverBytes(n))
    }

    /** Importa un `.trazo`; si el id ya existe crea una copia. Agrega patrones que falten. */
    fun importNotebook(input: InputStream): Notebook {
        val archive = TrazoFormat.importArchive(input)
        var n = archive.notebook
        if (fileFor(n.id).baseFile.exists()) n = n.copy(id = IdGenerator.RANDOM.next(), name = n.name + " (importado)")
        n = n.copy(updatedAt = System.currentTimeMillis())
        // La portada se guarda con el nombre de su contenido, sea cual sea el que traía el archivo.
        val cover = archive.cover
        n = if (cover != null) {
            val name = sha256(cover) + "." + Covers.extensionOf(cover)
            synchronized(lock) { File(coversDir, name).takeIf { !it.isFile }?.let { write(AtomicFile(it), cover) } }
            n.copy(coverKind = CoverKind.IMAGE, coverImage = name)
        } else n.normalizedCover()
        saveNotebook(n)
        mergePatterns(archive.patterns)
        return n
    }

    private fun write(file: AtomicFile, text: String) = write(file, text.toByteArray(Charsets.UTF_8))

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
