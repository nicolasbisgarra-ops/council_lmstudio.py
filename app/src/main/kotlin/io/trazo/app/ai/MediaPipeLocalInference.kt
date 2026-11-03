package io.trazo.app.ai

import android.content.Context
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Local text inference backed by Google's official MediaPipe Tasks GenAI Android SDK. */
class MediaPipeLocalInference(
    context: Context,
    private val acquireModelLease: (LocalModel) -> Closeable = { Closeable {} },
) : LocalInference {
    private val appContext = context.applicationContext
    private val generationMutex = Mutex()
    private val stateLock = Any()

    private var inference: LlmInference? = null
    private var loadedPath: String? = null
    private var loadedOutputLimit: Int? = null
    private var modelLease: Closeable? = null
    private var activeSession: LlmInferenceSession? = null
    private var activeContinuation: kotlinx.coroutines.CancellableContinuation<String>? = null
    private var activeOutput: StringBuilder? = null
    private var activePartial: (String) -> Unit = {}
    private var generationId = 0L

    override val supportsVision: Boolean = false

    override suspend fun generate(
        model: LocalModel,
        system: String,
        prompt: String,
        imageBytes: ByteArray?,
        onPartial: (String) -> Unit,
    ): String {
        if (imageBytes != null) {
            throw IllegalArgumentException("Este modelo MediaPipe está configurado solo para texto; no se envió la imagen.")
        }
        require(model.modelFile.isFile && model.modelFile.extension.equals("task", ignoreCase = true)) {
            "MediaPipe Tasks GenAI necesita un modelo local .task válido."
        }
        val input = buildString {
            if (system.isNotBlank()) append("Instrucciones:\n").append(system.trim()).append("\n\n")
            append("Solicitud:\n").append(prompt.trim())
        }

        return generationMutex.withLock {
            withContext(Dispatchers.IO) {
                val engine = ensureLoaded(model)
                val session = LlmInferenceSession.createFromOptions(
                    engine,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder().build(),
                )
                session.addQueryChunk(input)

                suspendCancellableCoroutine { continuation ->
                    val id = synchronized(stateLock) {
                        generationId += 1
                        activeSession = session
                        activeContinuation = continuation
                        activeOutput = StringBuilder()
                        activePartial = onPartial
                        generationId
                    }
                    continuation.invokeOnCancellation { cancelGeneration(id) }
                    if (!continuation.isActive) {
                        session.close()
                        return@suspendCancellableCoroutine
                    }
                    try {
                        val future = session.generateResponseAsync { partial, done ->
                            onProgress(id, session, partial, done)
                        }
                        observeFailure(id, future)
                    } catch (failure: Throwable) {
                        failGeneration(id, failure)
                    }
                }
            }
        }
    }

    private fun ensureLoaded(model: LocalModel): LlmInference {
        val canonical = model.modelFile.canonicalPath
        val outputLimit = model.maxOutputTokens.coerceIn(1, 8192)
        synchronized(stateLock) {
            inference?.takeIf { loadedPath == canonical && loadedOutputLimit == outputLimit }?.let { return it }
            inference?.close()
            inference = null
            loadedPath = null
            loadedOutputLimit = null
            modelLease?.close()
            modelLease = null

            val lease = acquireModelLease(model)

            val created = try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(canonical)
                    .setMaxTokens(outputLimit)
                    .build()
                LlmInference.createFromOptions(appContext, options)
            } catch (failure: Throwable) {
                runCatching { lease.close() }
                throw IllegalStateException(
                    "No se pudo cargar el modelo MediaPipe. Verificá que el archivo .task sea compatible con este dispositivo.",
                    failure,
                )
            }
            inference = created
            loadedPath = canonical
            loadedOutputLimit = outputLimit
            modelLease = lease
            return created
        }
    }

    private fun onProgress(id: Long, session: LlmInferenceSession, partial: String, done: Boolean) {
        var continuation: kotlinx.coroutines.CancellableContinuation<String>? = null
        var output: String? = null
        var callbackFailure: Throwable? = null
        synchronized(stateLock) {
            if (generationId != id || activeSession !== session) return
            activeOutput?.append(partial)
            try {
                activePartial(partial)
            } catch (failure: Throwable) {
                callbackFailure = failure
            }
            if (done || callbackFailure != null) {
                output = activeOutput?.toString().orEmpty()
                continuation = activeContinuation
                activeContinuation = null
                activeOutput = null
                activePartial = {}
                activeSession = null
            }
        }
        if (callbackFailure != null) {
            runCatching { session.cancelGenerateResponseAsync() }
            session.close()
            continuation?.let { if (it.isActive) it.resumeWithException(callbackFailure!!) }
        } else if (done) {
            session.close()
            continuation?.let { if (it.isActive) it.resume(output.orEmpty()) }
        }
    }

    private fun observeFailure(id: Long, future: ListenableFuture<String>) {
        future.addListener({
            val failure = try {
                future.get()
                null
            } catch (e: ExecutionException) {
                e.cause ?: e
            } catch (e: Throwable) {
                e
            }
            if (failure != null) failGeneration(id, failure)
        }, DIRECT_EXECUTOR)
    }

    private fun failGeneration(id: Long, failure: Throwable) {
        val (session, continuation) = synchronized(stateLock) {
            if (generationId != id) return
            val current = activeSession
            val pending = activeContinuation
            activeSession = null
            activeContinuation = null
            activeOutput = null
            activePartial = {}
            generationId += 1
            current to pending
        }
        runCatching { session?.close() }
        continuation?.let { if (it.isActive) it.resumeWithException(failure) }
    }

    private fun cancelGeneration(expectedId: Long?) {
        val (session, continuation) = synchronized(stateLock) {
            if (expectedId != null && generationId != expectedId) return
            val current = activeSession
            val pending = activeContinuation
            activeSession = null
            activeContinuation = null
            activeOutput = null
            activePartial = {}
            generationId += 1
            current to pending
        }
        runCatching { session?.cancelGenerateResponseAsync() }
        runCatching { session?.close() }
        continuation?.let { if (it.isActive) it.cancel(CancellationException("Generación local cancelada.")) }
    }

    override fun cancel() = cancelGeneration(expectedId = null)

    override fun close() {
        cancel()
        synchronized(stateLock) {
            inference?.close()
            inference = null
            loadedPath = null
            loadedOutputLimit = null
            modelLease?.close()
            modelLease = null
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
