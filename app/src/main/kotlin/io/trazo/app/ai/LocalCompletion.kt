package io.trazo.app.ai

import android.content.Context
import io.trazo.ai.AiProfile
import io.trazo.ai.OnDeviceCompletion
import io.trazo.app.data.LocalModelStore
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts the app's imported local model catalog to the Android-independent AI completion API.
 * AiProfile.model stores the LocalModelStore ID. .gguf uses llama.cpp; .task uses MediaPipe.
 */
class LocalCompletion(context: Context) : OnDeviceCompletion, Closeable {
    private val appContext = context.applicationContext
    private val store = LocalModelStore(appContext)
    private val engines = ConcurrentHashMap<String, LocalInference>()
    private val closed = AtomicBoolean(false)

    override fun complete(
        profile: AiProfile,
        system: String,
        text: String,
        png: ByteArray?,
        maxTokens: Int,
    ): String = infer(profile, system, text, png, maxTokens) {}

    override fun stream(
        profile: AiProfile,
        system: String,
        text: String,
        png: ByteArray?,
        maxTokens: Int,
        onText: (String) -> Unit,
    ): String = infer(profile, system, text, png, maxTokens, onText)

    private fun infer(
        profile: AiProfile,
        system: String,
        text: String,
        png: ByteArray?,
        maxTokens: Int,
        onText: (String) -> Unit,
    ): String {
        check(!closed.get()) { "La inferencia local ya se cerró." }
        val modelId = profile.model.trim()
        require(modelId.isNotEmpty()) { "Elegí un modelo local en Ajustes → Modelos en el dispositivo." }
        val stored = store.get(modelId)
            ?: throw IllegalStateException("El modelo local seleccionado ya no está disponible; elegí uno de la lista.")
        val model = stored.copy(maxOutputTokens = maxTokens.coerceIn(1, 8192))
        val kind = engineKind(model)
        val engine = engines.compute(model.id) { _, previous ->
            if (previous != null && previous.matches(kind)) previous
            else {
                previous?.close()
                when (kind) {
                    EngineKind.LLAMA_CPP -> LlamaCppInference { requested -> store.acquireModelLease(requested.id) }
                    EngineKind.MEDIAPIPE -> MediaPipeLocalInference(appContext) { requested -> store.acquireModelLease(requested.id) }
                }
            }
        } ?: error("No se pudo crear el motor local.")
        return runBlocking {
            engine.generate(model, system, text, png, onText)
        }
    }

    private enum class EngineKind { LLAMA_CPP, MEDIAPIPE }

    private fun LocalInference.matches(kind: EngineKind): Boolean = when (kind) {
        EngineKind.LLAMA_CPP -> this is LlamaCppInference
        EngineKind.MEDIAPIPE -> this is MediaPipeLocalInference
    }

    private fun engineKind(model: LocalModel): EngineKind =
        when (model.modelFile.extension.lowercase()) {
            "gguf" -> EngineKind.LLAMA_CPP
            "task" -> EngineKind.MEDIAPIPE
            else -> throw IllegalArgumentException(
                "Formato de modelo local no compatible: ${model.modelFile.extension}",
            )
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val current = engines.values.toList()
        current.forEach { runCatching { it.cancel() } }
        engines.clear()
        Thread({ current.forEach { runCatching { it.close() } } }, "trazo-local-inference-close").apply {
            isDaemon = true
            start()
        }
    }

    override fun cancel() {
        engines.values.forEach { runCatching { it.cancel() } }
    }
}
