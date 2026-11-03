package io.trazo.ai

/** Description of capabilities reported by the selected, installed local model. */
data class ReviewEngineCapabilities(
    val vision: Boolean,
    val incrementalText: Boolean,
)

class ReviewUnavailable(message: String) : IllegalStateException(message)
class ReviewDeferred : IllegalStateException("Offline remote request must be durably queued, never rerouted locally")

/** Input and output are ephemeral. The caller owns the image bytes; no credentials are stored. */
class ReviewPayload(val prompt: String, val imagePng: ByteArray? = null) {
    init { require(prompt.isNotBlank()) }
}

/**
 * Engine adapter: must be called on a worker thread, not the Compose main/UI dispatcher.
 * The local implementation must be offline-only; the remote adapter is injected separately.
 * CancelHandle ownership/lifecycle is controlled by ReviewLifecycleGate.
 */
fun interface ReviewCompletionEngine {
    fun complete(input: ReviewPayload, onText: (String) -> Unit): String

    /** Allows explicit engine consent/capabilities to normalize input before validation. */
    fun prepare(input: ReviewPayload): ReviewPayload = input
}

/**
 * Explicit dispatch: no automatic fallback LOCAL -> REMOTE or REMOTE -> LOCAL.
 * The caller must choose the route using ReviewRouting, after checking exam mode and availability.
 */
class ReviewBackendDispatcher(
    private val local: ReviewCompletionEngine,
    private val remote: ReviewCompletionEngine,
    private val localCapabilities: ReviewEngineCapabilities,
) {
    fun execute(route: ReviewRoute, request: ReviewPayload, onText: (String) -> Unit = {}): String {
        return when (route) {
            ReviewRoute.RUN_LOCAL -> {
                val prepared = local.prepare(request)
                if (prepared.imagePng != null && !localCapabilities.vision) {
                    throw ReviewUnavailable("Installed local model does not support images; select a vision model explicitly")
                }
                local.complete(prepared, if (localCapabilities.incrementalText) onText else { _ -> })
                    .also { if (!localCapabilities.incrementalText) onText(it) }
            }
            ReviewRoute.RUN_REMOTE -> remote.complete(request, onText)
            ReviewRoute.QUEUE_REMOTE -> throw ReviewDeferred()
            ReviewRoute.BLOCKED_EXAM -> throw ReviewUnavailable("AI assistance disabled in exam mode")
            ReviewRoute.BLOCKED_UNAVAILABLE -> throw ReviewUnavailable("Selected model backend is not available")
        }
    }
}
