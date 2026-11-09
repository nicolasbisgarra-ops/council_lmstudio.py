package io.trazo.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import io.trazo.core.CoverKind
import io.trazo.core.Covers
import io.trazo.core.Folder
import io.trazo.core.FolderCatalog
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.Notebook
import io.trazo.core.Page
import io.trazo.core.PageImages
import io.trazo.core.imageAssets
import io.trazo.core.Paper
import io.trazo.core.PatternCategory
import io.trazo.core.PatternLibrary
import io.trazo.core.PatternUpgrades
import io.trazo.core.PdfImport
import io.trazo.core.PresetPatterns
import io.trazo.core.LazyNotebook
import io.trazo.core.NotebookOrder
import io.trazo.core.TrazoFormat
import io.trazo.core.normalizedCover
import io.trazo.core.remapPatternIds
import io.trazo.core.sync.NotebookPrint
import io.trazo.core.sync.SyncHash
import io.trazo.core.sync.SyncKey
import io.trazo.core.sync.SyncMerge
import io.trazo.core.sync.SyncProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    /** Imágenes de las hojas, por contenido: `images/<sha256>.webp|png`. */
    val imagesDir = File(root, "images").apply { mkdirs() }
    private val lock = Any()
    private val manualOrderRevision = AtomicLong()

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
        mergePatterns(TrazoFormat.decodePatterns(input))

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

    fun addFolder(name: String, colorArgb: Long = Folder.DEFAULT_COLOR, parentId: String? = null): Folder = synchronized(lock) {
        val (c, f) = folders().add(name, colorArgb, parentId = parentId)
        saveFolders(c)
        f
    }

    fun moveFolder(id: String, parentId: String?) = synchronized(lock) { saveFolders(folders().moved(id, parentId)) }

    fun renameFolder(id: String, name: String) = synchronized(lock) { saveFolders(folders().renamed(id, name)) }

    fun recolorFolder(id: String, colorArgb: Long) = synchronized(lock) { saveFolders(folders().recolored(id, colorArgb)) }

    /** Elimina una carpeta vacía; si tiene cuadernos lanza [io.trazo.core.FolderNotEmptyException]. */
    fun deleteFolder(id: String) = synchronized(lock) {
        saveFolders(folders().removed(id, listNotebooks().map { it.folderId }))
    }

    /** Mueve el cuaderno a [folderId] (null: «Sin carpeta») sin cambiar su fecha de modificación. */
    fun moveToFolder(id: String, folderId: String?): Unit = synchronized(lock) {
        if (folderId != null && folders()[folderId] == null) return
        loadLazy(id)?.let { if (it.folderId != folderId) saveLazy(it.copy(folderId = folderId)) }
    }

    fun manualOrder(): List<String> = synchronized(lock) {
        NotebookOrder.decode(runCatching { orderFile.readFully().toString(Charsets.UTF_8) }.getOrNull())
    }

    /** Reserva una versión antes de despachar una escritura fuera del hilo principal. */
    fun beginManualOrderSave(): Long = manualOrderRevision.incrementAndGet()

    /** Guarda el orden manual; escrituras de versiones viejas no pueden pisar una más nueva. */
    fun saveManualOrder(ids: List<String>) = saveManualOrder(ids, beginManualOrderSave())

    fun saveManualOrder(ids: List<String>, revision: Long) = synchronized(lock) {
        if (revision == manualOrderRevision.get()) write(orderFile, NotebookOrder.encode(ids))
    }

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
    fun clearCover(id: String): Unit = synchronized(lock) {
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

    // ------------------------------------------------------------------ imágenes de las hojas

    /** Guarda los bytes (WEBP o PNG) de una imagen de hoja y devuelve su nombre. */
    fun storeImage(bytes: ByteArray): String {
        val ext = PageImages.extensionOf(bytes) ?: error("Formato de imagen no válido")
        require(bytes.size <= PageImages.MAX_BYTES) { "La imagen es demasiado grande" }
        val name = sha256(bytes) + "." + ext
        synchronized(lock) {
            val f = File(imagesDir, name)
            if (!f.isFile) write(AtomicFile(f), bytes)
        }
        return name
    }

    private fun imageBytes(n: Notebook): Map<String, ByteArray> =
        n.imageAssets().filter(PageImages::isValidName).mapNotNull { name ->
            File(imagesDir, name).takeIf { it.isFile }?.let { name to it.readBytes() }
        }.toMap()

    /**
     * Borra las imágenes que ningún cuaderno nombra. Solo las que tienen más de una hora: una recién
     * guardada puede pertenecer a una hoja que todavía no se escribió.
     */
    fun collectUnusedImages(now: Long = System.currentTimeMillis()) {
        val files = imagesDir.listFiles { f -> PageImages.isValidName(f.name) && now - f.lastModified() > 3_600_000L }.orEmpty()
        if (files.isEmpty()) return
        synchronized(lock) {
            val used = HashSet<String>()
            for (f in notebooksDir.listFiles { f -> f.name.endsWith(".trazo.json") }.orEmpty()) {
                // Un cuaderno que no se pudo leer conserva todo: no se borra nada.
                val text = runCatching { AtomicFile(f).readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return
                IMAGE_NAME.findAll(text).forEach { used += it.value }
            }
            files.filter { it.name !in used }.forEach { it.delete() }
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
        writeNotebook(fileFor(n.id), n)
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

    /**
     * Cuaderno nuevo con una hoja por página del PDF (hasta [PdfImport.MAX_PAGES]): cada página se dibuja
     * sobre blanco a [PDF_RENDER_WIDTH] px de ancho y queda como fondo fijo, para escribir encima.
     */
    fun importPdf(fd: ParcelFileDescriptor, name: String, folderId: String? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): Notebook {
        val rendered = PdfRenderer(fd).use { pdf ->
            val count = minOf(pdf.pageCount, PdfImport.MAX_PAGES)
            require(count > 0) { "El PDF no tiene hojas" }
            (0 until count).map { i ->
                onProgress(i, count)
                pdf.openPage(i).use { page ->
                    val w = PDF_RENDER_WIDTH
                    // Hojas muy largas: se limita el alto para no quedarse sin memoria.
                    val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, PDF_RENDER_WIDTH * 3)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val bytes = ImageImport.encode(bmp) ?: error("La hoja ${i + 1} es demasiado grande")
                        PdfImport.RenderedPage(storeImage(bytes), w, h)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }
        return PdfImport.notebook(name, rendered).copy(folderId = folderId?.takeIf { folders()[it] != null })
            .also { saveNotebook(it) }
    }

    fun deleteNotebook(id: String) = synchronized(lock) {
        val cover = loadLazy(id)?.coverImage
        fileFor(id).delete()
        cover?.let { dropCoverIfUnused(it) }
        // An old queued order may still include this deleted notebook: invalidate its revision.
        val current = manualOrder()
        if (id in current) saveManualOrder(current.filterNot { it == id }, beginManualOrderSave())
    }

    fun exportNotebook(id: String, out: OutputStream) {
        val n = loadNotebook(id) ?: error("Notebook $id not found")
        val used = n.pages.flatMap { p ->
            p.strokes.mapNotNull { it.patternId } + p.parametricShapes.mapNotNull { it.sourcePatternId }
        }.toSet()
        TrazoFormat.exportArchive(n, patterns.value.filter { it.id in used }, out, coverBytes(n), imageBytes(n))
    }

    /**
     * Valida el archivo antes de modificar datos. El cuaderno y la biblioteca de patrones
     * se escriben bajo un mismo lock; si falla guardar el cuaderno se intenta revertir
     * el cambio previo de patrones y se elimina la portada recién creada.
     */
    fun importNotebook(input: InputStream): Notebook {
        val archive = TrazoFormat.importArchive(input)
        return synchronized(lock) {
            var n = archive.notebook
            if (fileFor(n.id).baseFile.exists()) n = n.copy(id = IdGenerator.RANDOM.next(), name = n.name + " (importado)")
            n = n.copy(updatedAt = System.currentTimeMillis())
            val oldPatterns = _patterns.value
            val merge = PatternLibrary.mergeWithRemapping(oldPatterns, PatternUpgrades.upgrade(archive.patterns))
            val nextPatterns = merge.patterns
            n = n.remapPatternIds(merge.idRemap)
            var createdCover: File? = null
            val createdImages = ArrayList<File>()
            var patternsWritten = false
            try {
                for ((name, bytes) in archive.images) {
                    if (!PageImages.isValidName(name)) continue
                    val f = File(imagesDir, name)
                    if (!f.isFile) {
                        write(AtomicFile(f), bytes)
                        createdImages += f
                    }
                }
                val cover = archive.cover
                n = if (cover == null) n.normalizedCover() else {
                    val name = sha256(cover) + "." + Covers.extensionOf(cover)
                    val f = File(coversDir, name)
                    if (!f.isFile) {
                        write(AtomicFile(f), cover)
                        createdCover = f
                    }
                    n.copy(coverKind = CoverKind.IMAGE, coverImage = name)
                }
                if (nextPatterns != oldPatterns) {
                    writePatterns(nextPatterns)
                    patternsWritten = true
                }
                saveNotebook(n)
                _patterns.value = nextPatterns
            } catch (error: Exception) {
                if (patternsWritten) runCatching { writePatterns(oldPatterns) }
                    .onFailure { error.addSuppressed(it) }
                createdCover?.delete()
                createdImages.forEach { it.delete() }
                throw error
            }
            n
        }
    }

    // ------------------------------------------------------------------ sincronización entre dispositivos

    private val rootDir = root
    private val _changes = MutableStateFlow(0L)
    /** Cambia cuando la sincronización escribe algo: la lista de cuadernos se vuelve a leer. */
    val changes: StateFlow<Long> = _changes.asStateFlow()
    private val openNotebooks = HashMap<String, Int>()
    private val printCache = HashMap<String, Triple<Long, Long, NotebookPrint>>()

    /** El editor tiene abierto el cuaderno: la sincronización no lo toca hasta que se cierre. */
    fun markOpen(id: String): Unit = synchronized(lock) { openNotebooks[id] = (openNotebooks[id] ?: 0) + 1 }

    fun markClosed(id: String): Unit = synchronized(lock) {
        val n = (openNotebooks[id] ?: 0) - 1
        if (n <= 0) openNotebooks.remove(id) else openNotebooks[id] = n
    }

    fun openNotebookIds(): Set<String> = synchronized(lock) { openNotebooks.keys.toSet() }

    /** Huellas de todos los cuadernos; solo se vuelven a calcular los archivos que cambiaron. */
    fun syncPrints(now: Long = System.currentTimeMillis()): Map<String, NotebookPrint> = synchronized(lock) {
        val out = HashMap<String, NotebookPrint>()
        val seen = HashSet<String>()
        for (f in notebooksDir.listFiles { f -> f.name.endsWith(".trazo.json") }.orEmpty()) {
            seen += f.name
            val len = f.length()
            val mod = f.lastModified()
            val cached = printCache[f.name]?.takeIf { it.first == len && it.second == mod }?.third
            val print = cached ?: runCatching {
                SyncMerge.print(TrazoFormat.decodeLazy(AtomicFile(f).readFully().toString(Charsets.UTF_8)))
            }.getOrNull()?.also {
                // Un archivo recién escrito puede cambiar dentro del mismo tic del reloj: no se guarda en caché.
                if (now - mod > 3_000L) printCache[f.name] = Triple(len, mod, it)
            }
            print?.let { out[it.id] = it }
        }
        printCache.keys.retainAll(seen)
        out
    }

    private fun syncFile(key: SyncKey): AtomicFile = when (key) {
        is SyncKey.NotebookKey -> fileFor(key.id)
        SyncKey.Patterns -> patternsFile
        SyncKey.Folders -> foldersFile
        SyncKey.Order -> orderFile
    }

    fun syncRead(key: SyncKey): String? = synchronized(lock) {
        val f = syncFile(key)
        if (!f.baseFile.exists()) null else runCatching { f.readFully().toString(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * Reemplaza un archivo con lo que trajo la sincronización, solo si sigue siendo la versión que se leyó
     * ([expect]). El cuaderno conserva la última hoja abierta en este dispositivo.
     */
    fun syncReplace(key: SyncKey, expect: String, text: String?): Boolean = synchronized(lock) {
        if (key is SyncKey.NotebookKey && key.id in openNotebooks) return false
        // Del archivo actual solo hacen falta su huella y la última hoja: no se lo retiene entero.
        val keep = syncRead(key).let { current ->
            if (SyncHash.fingerprint(current) != expect) return false
            if (key is SyncKey.NotebookKey) current?.let(TrazoFormat::lastPage) ?: 0 else 0
        }
        val f = syncFile(key)
        when (key) {
            is SyncKey.NotebookKey -> if (text == null) f.delete() else {
                writeNotebook(f, TrazoFormat.decodeLazy(text).copy(lastPage = keep))
            }
            SyncKey.Patterns -> {
                val list = TrazoFormat.decodePatterns(text ?: return false)
                write(f, text)
                _patterns.value = list
            }
            SyncKey.Folders -> write(f, text ?: return false)
            SyncKey.Order -> {
                // Una escritura del orden ya despachada con la versión vieja no pisa la nueva.
                manualOrderRevision.incrementAndGet()
                write(f, text ?: return false)
            }
        }
        _changes.value = _changes.value + 1
        true
    }

    fun syncBlobNames(): Set<String> = synchronized(lock) {
        val images = imagesDir.list().orEmpty().filter(PageImages::isValidName).map { "images/$it" }
        val covers = coversDir.list().orEmpty().filter(Covers::isValidName).map { "covers/$it" }
        (images + covers).toSet()
    }

    fun syncReadBlob(name: String): ByteArray? =
        if (!SyncProtocol.isValidBlob(name)) null else File(rootDir, name).takeIf { it.isFile }?.readBytes()

    fun syncWriteBlob(name: String, bytes: ByteArray) {
        if (!SyncProtocol.isValidBlob(name)) return
        synchronized(lock) {
            val f = File(rootDir, name)
            if (!f.isFile) write(AtomicFile(f), bytes)
        }
    }

    private fun write(file: AtomicFile, text: String) = write(file, text.toByteArray(Charsets.UTF_8))

    /** Un cuaderno directo al archivo, sin armar su texto ni sus bytes enteros en memoria. */
    private fun writeNotebook(file: AtomicFile, n: LazyNotebook) {
        val out = file.startWrite()
        try {
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
            TrazoFormat.writeLazy(n, w)
            w.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        } catch (e: OutOfMemoryError) {
            file.failWrite(out)
            throw e
        }
    }

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

    private companion object {
        /** Ancho en píxeles de cada hoja de un PDF importado: nítido para leer con zoom sin pesar demasiado. */
        const val PDF_RENDER_WIDTH = 1600
        val IMAGE_NAME = Regex("[0-9a-f]{64}\\.(webp|png)")
    }
}
