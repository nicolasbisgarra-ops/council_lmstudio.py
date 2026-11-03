package io.trazo.app.ai

import kotlinx.coroutines.Dispatchers
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * JNI-backed llama.cpp engine. It loads GGUF files directly and never shells out to a CLI/server.
 *
 * Native resources are held by one handle and all operations are serialized because llama contexts
 * are not safe for concurrent decode calls.
 */
class LlamaCppInference(
    private val acquireModelLease: (LocalModel) -> Closeable = { Closeable {} },
) : LocalInference {
    private val lock = ReentrantLock()
    private val nativeHandleLock = Any()
    @Volatile private var handle = nativeCreate()
    private val closing = AtomicBoolean(false)
    private var loadedId: String? = null
    private var loadedPath: String? = null
    private var loadedProjector: String? = null
    private var modelLease: Closeable? = null

    override val supportsVision: Boolean = true

    init {
        check(handle != 0L) { "No se pudo inicializar llama.cpp." }
    }

    override suspend fun generate(
        model: LocalModel,
        system: String,
        prompt: String,
        imageBytes: ByteArray?,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closing.get() && handle != 0L) { "El motor llama.cpp ya se cerró." }
            require(model.modelFile.isFile) { "No se encuentra el modelo GGUF: ${model.modelFile.name}" }
            if (imageBytes != null) {
                require(model.projectorFile?.isFile == true) {
                    "Este modelo GGUF no tiene un proyector de imagen mmproj; quite la imagen o instale el archivo mmproj correspondiente."
                }
            }
            val modelPath = model.modelFile.absolutePath
            val projectorPath = model.projectorFile?.absolutePath.orEmpty()
            if (loadedId != model.id || loadedPath != modelPath || loadedProjector != projectorPath) {
                nativeUnload(handle)
                modelLease?.close()
                modelLease = null
                loadedId = null
                loadedPath = null
                loadedProjector = null
                val lease = acquireModelLease(model)
                try {
                    val error = nativeLoad(handle, modelPath, projectorPath, model.maxContextTokens.coerceIn(512, 131072))
                    check(error.isNullOrBlank()) { error ?: "No se pudo cargar el modelo GGUF." }
                    modelLease = lease
                } catch (failure: Throwable) {
                    runCatching { nativeUnload(handle) }
                    runCatching { lease.close() }
                    throw failure
                }
                loadedId = model.id
                loadedPath = modelPath
                loadedProjector = projectorPath
            }
            require(model.maxOutputTokens > 0) { "maxOutputTokens debe ser mayor que cero." }
            synchronized(nativeHandleLock) {
                check(!closing.get() && handle != 0L) { "El motor llama.cpp ya se cerró." }
                nativeResetCancel(handle)
            }
            nativeGenerate(
                handle,
                system,
                prompt,
                imageBytes,
                model.maxOutputTokens.coerceIn(1, 8192),
                onPartial,
            )
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        synchronized(nativeHandleLock) {
            if (handle == 0L) return
            nativeCancel(handle)
        }
        // JNI generation can take minutes. Never make a caller (often the UI thread) wait for it.
        Thread({
            lock.withLock {
                synchronized(nativeHandleLock) {
                    if (handle != 0L) {
                        nativeDestroy(handle)
                        handle = 0L
                        modelLease?.close()
                        modelLease = null
                        loadedId = null
                        loadedPath = null
                        loadedProjector = null
                    }
                }
            }
        }, "trazo-llama-close").apply {
            isDaemon = true
            start()
        }
    }

    override fun cancel() {
        synchronized(nativeHandleLock) {
            if (handle != 0L) nativeCancel(handle)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeLoad(handle: Long, modelPath: String, projectorPath: String, contextTokens: Int): String?
    private external fun nativeUnload(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        system: String,
        prompt: String,
        imageBytes: ByteArray?,
        maxTokens: Int,
        onPartial: (String) -> Unit,
    ): String
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResetCancel(handle: Long)
    private external fun nativeCancel(handle: Long)

    private companion object {
        init {
            System.loadLibrary("trazo_llama")
        }
    }
}
