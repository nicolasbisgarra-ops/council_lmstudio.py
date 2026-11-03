package io.trazo.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.trazo.app.ai.LocalModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.Closeable
import java.util.UUID

/** App-private, durable catalog of user-imported on-device models. */
class LocalModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("local_models", Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "local_models").apply { mkdirs() }
    private val mutex = Mutex()
    private val downloader = LocalModelDownloader(directory)

    private val _downloadProgress = MutableStateFlow<ModelDownloadProgress?>(null)
    val downloadProgress: StateFlow<ModelDownloadProgress?> = _downloadProgress.asStateFlow()

    private val _projectors = MutableStateFlow(scanProjectors())
    val projectors: StateFlow<List<File>> = _projectors.asStateFlow()

    private val _models = MutableStateFlow(scan())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _selectedModelId = MutableStateFlow(prefs.getString(KEY_SELECTED, null).takeIf { id -> id != null && get(id) != null })
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    init {
        if (prefs.getString(KEY_SELECTED, null) != _selectedModelId.value) prefs.edit().remove(KEY_SELECTED).apply()
    }

    /** Refreshes the small catalog so a long-lived inference adapter sees imports made by the UI. */
    @Synchronized
    fun refresh() {
        _projectors.value = scanProjectors()
        _models.value = scan()
    }

    /** Resolves an ID saved in an AI profile. */
    fun get(id: String): LocalModel? {
        refresh()
        return _models.value.firstOrNull { it.id == id }
    }

    /** Copies the picked SAF document into app-private storage; the persisted URI permission is not needed. */
    suspend fun import(uri: Uri): LocalModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = sourceMetadata(uri)
            val extension = localModelExtension(source.name)?.takeIf { it in MODEL_EXTENSIONS }
                ?: throw IllegalArgumentException("Formato no compatible. Importá un modelo MediaPipe .task o GGUF .gguf.")
            val modelId = UUID.randomUUID().toString()
            val stem = safeModelStem(source.name.substringBeforeLast('.', source.name))
            val destination = File(directory, "$stem-$modelId.$extension")
            val partial = File(directory, "$modelId.importing")
            try {
                copyBounded(uri, partial, source.size)
                if (!hasValidModelHeader(extension, readHeader(partial))) {
                    throw IllegalArgumentException(headerError(extension))
                }
                if (!partial.renameTo(destination)) throw IllegalStateException("No se pudo guardar el modelo en el dispositivo.")
                val model = LocalModel(destination.nameWithoutExtension, destination)
                _models.value = scan()
                select(model.id)
                model
            } catch (e: Throwable) {
                partial.delete()
                destination.delete()
                throw e
            }
        }
    }

    /**
     * Downloads a digest-verified model over HTTPS. Partial data is hidden from scan() and
     * a server-side interrupted Range transfer can be resumed on the next attempt.
     * The caller must supply a pinned Hugging Face revision or a SHA256 digest.
     */
    suspend fun download(request: ModelDownloadRequest): LocalModel = withContext(Dispatchers.IO) {
        require(localModelExtension(request.fileName) in MODEL_EXTENSIONS) { "Solo .task o .gguf se seleccionan como modelos." }
        mutex.withLock {
            try {
                val ctx = currentCoroutineContext()
                val file = downloader.download(
                    request,
                    checkActive = { ctx.ensureActive() },
                    onProgress = { _downloadProgress.value = it },
                )
                _models.value = scan()
                val model = get(file.nameWithoutExtension)
                    ?: throw IllegalStateException("El modelo descargado no apareció en el catálogo.")
                select(model.id)
                model
            } finally { _downloadProgress.value = null }
        }
    }

    suspend fun downloadProjector(request: ModelDownloadRequest): File = withContext(Dispatchers.IO) {
        require(localModelExtension(request.fileName) == "mmproj") { "Un proyector necesita extensión .mmproj." }
        mutex.withLock {
            try {
                val ctx = currentCoroutineContext()
                val file = downloader.download(
                    request,
                    checkActive = { ctx.ensureActive() },
                    onProgress = { _downloadProgress.value = it },
                )
                _projectors.value = scanProjectors()
                file
            } finally { _downloadProgress.value = null }
        }
    }

    /**
     * Must be held by LocalCompletion while a model is loaded or being used; the store prevents
     * deleting/replacing an in-use model. This API is NOT hooked into Agente 3's VM yet.
     */
    fun acquireModelLease(id: String): Closeable =
        leases.acquire(requireNotNull(get(id)) { "Modelo no encontrado." }.modelFile)

    /** Imports a llama.cpp multimodal projector sidecar; pair it with a GGUF below. */
    suspend fun importProjector(uri: Uri): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = sourceMetadata(uri)
            require(localModelExtension(source.name) == "mmproj") {
                "El archivo seleccionado no es un proyector .mmproj."
            }
            val id = UUID.randomUUID().toString()
            val stem = safeModelStem(source.name.substringBeforeLast('.', source.name))
            val destination = File(directory, "$stem-$id.mmproj")
            val partial = File(directory, "$id.importing")
            try {
                copyBounded(uri, partial, source.size)
                if (!hasValidModelHeader("mmproj", readHeader(partial))) {
                    throw IllegalArgumentException("El proyector no tiene el encabezado GGUF esperado.")
                }
                if (!partial.renameTo(destination)) throw IllegalStateException("No se pudo guardar el proyector en el dispositivo.")
                _projectors.value = scanProjectors()
                destination
            } catch (e: Throwable) {
                partial.delete()
                destination.delete()
                throw e
            }
        }
    }

    fun select(id: String?) {
        val exists = id == null || get(id) != null
        if (!exists) return
        prefs.edit().putString(KEY_SELECTED, id).apply()
        _selectedModelId.value = id
    }

    fun attachProjector(modelId: String, projector: File?) {
        val model = get(modelId) ?: return
        require(model.modelFile.extension.equals("gguf", ignoreCase = true)) {
            "Los proyectores solo se pueden asociar a modelos GGUF."
        }
        require(projector == null || _projectors.value.any { it.canonicalFile == projector.canonicalFile }) {
            "El proyector no está en el catálogo local."
        }
        leases.whileUnused(model.modelFile) {
            val editor = prefs.edit()
            if (projector == null) editor.remove(projectorKey(modelId))
            else editor.putString(projectorKey(modelId), projector.name)
            editor.apply()
            _models.value = scan()
        }
    }

    suspend fun removeProjector(projector: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = _projectors.value.firstOrNull { it.canonicalFile == projector.canonicalFile } ?: return@withLock
            leases.whileUnused(_models.value.filter { it.projectorFile?.name == stored.name }.map { it.modelFile }) {
                if (!stored.delete() && stored.exists()) throw IllegalStateException("No se pudo quitar ${stored.name}.")
                prefs.edit().let { editor ->
                    _models.value.forEach { model ->
                        if (model.projectorFile?.name == stored.name) editor.remove(projectorKey(model.id))
                    }
                    editor.apply()
                }
                _projectors.value = scanProjectors()
                _models.value = scan()
            }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val model = get(id) ?: return@withLock
            leases.whileUnused(model.modelFile) {
                if (!model.modelFile.delete() && model.modelFile.exists()) {
                    throw IllegalStateException("No se pudo quitar ${model.modelFile.name}.")
                }
                prefs.edit().remove(projectorKey(model.id)).apply()
                _models.value = scan()
                if (_selectedModelId.value == id) select(null)
            }
        }
    }

    /** Human-readable label derived from the imported file name. */
    fun displayName(model: LocalModel): String = model.modelFile.nameWithoutExtension
        .replace(Regex("-[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}$"), "")
        .ifBlank { model.modelFile.name }

    private fun scan(): List<LocalModel> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && localModelExtension(it.name) in setOf("task", "gguf") }
        .sortedBy { it.name.lowercase() }
        .map { file ->
            val id = file.nameWithoutExtension
            val projectorName = prefs.getString(projectorKey(id), null)
            val projector = projectorName?.let { name -> _projectors.value.firstOrNull { it.name == name } }
            LocalModel(id, file, projectorFile = projector)
        }

    private fun scanProjectors(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("mmproj", ignoreCase = true) }
        .sortedBy { it.name.lowercase() }

    private data class SourceMetadata(val name: String, val size: Long?)

    private fun sourceMetadata(uri: Uri): SourceMetadata {
        val query = appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        return query?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            SourceMetadata(
                name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "local-model.task" else "local-model.task",
                size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null,
            )
        } ?: SourceMetadata(uri.lastPathSegment?.substringAfterLast('/') ?: "local-model.task", null)
    }

    private suspend fun copyBounded(uri: Uri, partial: File, expectedSize: Long?) {
        val availableAtStart = directory.usableSpace
        if (!hasImportBudget(expectedSize, availableAtStart, MAX_MODEL_BYTES, RESERVED_FREE_BYTES)) {
            if (expectedSize != null && expectedSize > MAX_MODEL_BYTES) {
                throw IllegalArgumentException("El archivo supera el límite de ${MAX_MODEL_BYTES / BYTES_PER_GIB} GB por modelo.")
            } else throw IllegalArgumentException("No hay espacio libre suficiente para importar el modelo.")
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
        var copied = 0L
        input.use { source ->
            FileOutputStream(partial).use { target ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (!canContinueModelCopy(copied, count, directory.usableSpace, MAX_MODEL_BYTES, RESERVED_FREE_BYTES)) {
                        if (count.toLong() > MAX_MODEL_BYTES - copied) {
                            throw IllegalArgumentException("El archivo supera el límite de ${MAX_MODEL_BYTES / BYTES_PER_GIB} GB por modelo.")
                        }
                        throw IllegalArgumentException("Se agotó el espacio libre durante la importación.")
                    }
                    copied += count
                    target.write(buffer, 0, count)
                }
                target.fd.sync()
            }
        }
        if (copied == 0L) throw IllegalArgumentException("El archivo del modelo está vacío.")
        if (expectedSize != null && expectedSize > 0L && copied != expectedSize) {
            throw IOException("La copia quedó incompleta: se esperaban $expectedSize bytes y llegaron $copied.")
        }
    }

    private fun readHeader(file: File): ByteArray = FileInputStream(file).use { input ->
        val header = ByteArray(8)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count <= 0) break
            offset += count
        }
        header.copyOf(offset)
    }

    private fun headerError(extension: String): String = when (extension) {
        "gguf" -> "El archivo no tiene el encabezado GGUF esperado."
        "task" -> "El archivo no parece un contenedor .task de MediaPipe (ZIP o FlatBuffer TFL3)."
        "mmproj" -> "El proyector no tiene el encabezado GGUF esperado."
        else -> "El formato del modelo no es compatible."
    }

    companion object {
        // Shared by ALL LocalModelStore instances, including those created by the UI and
        // the local inference adapter. Keys are canonical file paths, not UI list indices.
        private val leases = LocalModelLeases()
        private const val KEY_SELECTED = "selected_model_id"
        private const val COPY_BUFFER_SIZE = 128 * 1024
        private const val BYTES_PER_GIB = 1024L * 1024L * 1024L
        private const val MAX_MODEL_BYTES = 8L * BYTES_PER_GIB
        private const val RESERVED_FREE_BYTES = 32L * 1024L * 1024L
        private val MODEL_EXTENSIONS = setOf("task", "gguf")
    }

    private fun projectorKey(modelId: String) = "projector_$modelId"
}

internal fun localModelExtension(fileName: String): String? =
    fileName.substringAfterLast('.', "").lowercase().takeIf { it in setOf("task", "gguf", "mmproj") }

internal fun hasValidModelHeader(extension: String, header: ByteArray): Boolean = when (extension.lowercase()) {
    "gguf", "mmproj" -> header.startsWith(byteArrayOf(0x47, 0x47, 0x55, 0x46))
    "task" -> header.startsWith(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) ||
        (header.size >= 8 && header.copyOfRange(4, 8).contentEquals(byteArrayOf(0x54, 0x46, 0x4c, 0x33)))
    else -> false
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

internal fun safeModelStem(name: String): String = name
    .replace(Regex("[^A-Za-z0-9._-]"), "_")
    .trim('.', '-', '_')
    .take(64)
    .ifBlank { "local-model" }

internal fun hasImportBudget(expectedSize: Long?, availableBytes: Long, maxBytes: Long, reserveBytes: Long): Boolean {
    if (expectedSize == null) return true
    if (expectedSize < 0L || expectedSize > maxBytes) return false
    return availableBytes <= 0L || expectedSize <= (availableBytes - reserveBytes).coerceAtLeast(0L)
}

internal fun canContinueModelCopy(
    copiedBytes: Long,
    nextChunkBytes: Int,
    availableBytes: Long,
    maxBytes: Long,
    reserveBytes: Long,
): Boolean {
    if (copiedBytes < 0L || nextChunkBytes <= 0 || nextChunkBytes.toLong() > maxBytes - copiedBytes) return false
    return availableBytes <= 0L || nextChunkBytes.toLong() + reserveBytes <= availableBytes
}
