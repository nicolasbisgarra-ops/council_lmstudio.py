package io.trazo.ai

/** Blocking local inference, implemented by the Android runtime and called on an IO worker. */
interface OnDeviceCompletion {
    fun cancel() {}
    fun complete(profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int): String

    fun stream(
        profile: AiProfile, system: String, text: String, png: ByteArray?, maxTokens: Int,
        onText: (String) -> Unit,
    ): String = complete(profile, system, text, png, maxTokens).also(onText)
}
