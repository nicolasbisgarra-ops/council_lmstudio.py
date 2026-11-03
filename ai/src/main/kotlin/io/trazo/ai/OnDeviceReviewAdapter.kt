package io.trazo.ai

/**
 * Thin adapter to the ACTUAL stage-3 OnDeviceCompletion contract; avoids a competing local
 * inference API. Construction is only for ON_DEVICE profiles with a chosen imported model.
 * The caller passes a ReviewCancelHandle { adapter.cancel() } to ReviewLifecycleGate.start().
 */
class OnDeviceReviewAdapter(
    private val onDevice: OnDeviceCompletion,
    private val profile: AiProfile,
    private val systemPrompt: String,
    private val maxTokens: Int = profile.maxTokens,
) : ReviewCompletionEngine {
    init {
        require(profile.provider == AiProvider.ON_DEVICE) { "Not a local review profile" }
        require(profile.model.isNotBlank()) { "Select an imported local model" }
    }

    override fun prepare(input: ReviewPayload): ReviewPayload {
        // Explicit text-only consent is retained from the existing profile. Without consent,
        // losing the image because the model lacks vision must be rejected by the caller.
        return if (profile.localTextOnly && input.imagePng != null) ReviewPayload(input.prompt) else input
    }

    override fun complete(input: ReviewPayload, onText: (String) -> Unit): String {
        val prepared = prepare(input)
        return onDevice.stream(profile, systemPrompt, prepared.prompt, prepared.imagePng, maxTokens, onText)
    }

    fun cancel() = onDevice.cancel()
}
