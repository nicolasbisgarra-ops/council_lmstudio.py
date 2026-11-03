package io.trazo.app.ai

import java.io.Closeable
import java.io.File

/**
 * A file-backed model managed by the on-device model screen.
 *
 * GGUF models may provide an optional llama.cpp mmproj sidecar. MediaPipe .task models leave
 * [projectorFile] null. The ID is stable across app launches and is what AiProfile.model stores.
 */
data class LocalModel(
    val id: String,
    val modelFile: File,
    val projectorFile: File? = null,
    val maxContextTokens: Int = 4096,
    val maxOutputTokens: Int = 512,
)

/**
 * Common app-facing boundary for local model engines.
 *
 * Images are encoded PNG/JPEG bytes so callers do not need a backend-specific Android image
 * type. Implementations must reject image input when the selected model/backend cannot read it.
 */
interface LocalInference : Closeable {
    /** Whether this backend can accept image input for a model which has the required sidecar. */
    val supportsVision: Boolean

    suspend fun generate(
        model: LocalModel,
        system: String,
        prompt: String,
        imageBytes: ByteArray? = null,
        onPartial: (String) -> Unit = {},
    ): String

    /** Requests cancellation of the current generation without waiting for it to finish. */
    fun cancel() {}

    /** Release a loaded model/context. Implementations may be reused after the next generate. */
    override fun close()
}
