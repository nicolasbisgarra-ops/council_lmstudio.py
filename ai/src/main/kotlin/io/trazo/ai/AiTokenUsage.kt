package io.trazo.ai

/** Token counts reported by a provider. Missing fields stay unknown; no values are estimated. */
data class AiTokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val imageInputTokens: Int? = null,
) {
    internal val hasAnyCount: Boolean
        get() = inputTokens != null || outputTokens != null || reasoningTokens != null ||
            cachedInputTokens != null || imageInputTokens != null

    /** SSE events are request-level cumulative snapshots, not per-event increments. */
    internal fun mergeSnapshot(next: AiTokenUsage): AiTokenUsage = AiTokenUsage(
        inputTokens = maxKnown(inputTokens, next.inputTokens),
        outputTokens = maxKnown(outputTokens, next.outputTokens),
        reasoningTokens = maxKnown(reasoningTokens, next.reasoningTokens),
        cachedInputTokens = maxKnown(cachedInputTokens, next.cachedInputTokens),
        imageInputTokens = maxKnown(imageInputTokens, next.imageInputTokens),
    )

    /** Review repair is a second request. Keep a field only when both requests reported it. */
    internal fun plusWhenBothReported(other: AiTokenUsage): AiTokenUsage = AiTokenUsage(
        inputTokens = addKnown(inputTokens, other.inputTokens),
        outputTokens = addKnown(outputTokens, other.outputTokens),
        reasoningTokens = addKnown(reasoningTokens, other.reasoningTokens),
        cachedInputTokens = addKnown(cachedInputTokens, other.cachedInputTokens),
        imageInputTokens = addKnown(imageInputTokens, other.imageInputTokens),
    )

    private fun maxKnown(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun addKnown(a: Int?, b: Int?): Int? = if (a == null || b == null) null else
        (a.toLong() + b.toLong()).takeIf { it <= Int.MAX_VALUE }?.toInt()
}
