package io.trazo.ai

/**
 * Independent, dependency-free gate for an editor review. The owner of the actual page data
 * must run commitPartial/commitFinal on the same UI dispatcher as page edits. Their callbacks
 * must not suspend. The gate serializes the check and mutation; isCurrent() alone cannot.
 *
 * No credential, network request, model engine or Android object is owned by this class.
 * This contract can be wired by the editor without changing the remote inference client.
 */
enum class ReviewBackend { ON_DEVICE, REMOTE }

enum class ReviewRoute { RUN_LOCAL, RUN_REMOTE, QUEUE_REMOTE, BLOCKED_EXAM, BLOCKED_UNAVAILABLE }

object ReviewRouting {
    fun choose(
        backend: ReviewBackend,
        isOnline: Boolean,
        localEngineReady: Boolean,
        inExam: Boolean,
    ): ReviewRoute {
        if (inExam) return ReviewRoute.BLOCKED_EXAM
        return when (backend) {
            ReviewBackend.ON_DEVICE -> if (localEngineReady) ReviewRoute.RUN_LOCAL else ReviewRoute.BLOCKED_UNAVAILABLE
            ReviewBackend.REMOTE -> if (isOnline) ReviewRoute.RUN_REMOTE else ReviewRoute.QUEUE_REMOTE
        }
    }
}

/** Must be idempotent. Do not block the main dispatcher while cancelling an inference engine. */
fun interface ReviewCancelHandle { fun cancel() }

data class ReviewScope(val notebookId: String, val pageId: String, val fingerprint: String) {
    init {
        require(notebookId.isNotBlank() && pageId.isNotBlank() && fingerprint.isNotBlank())
    }
}

data class ReviewTicket internal constructor(
    val sequence: Long,
    val scope: ReviewScope,
    val backend: ReviewBackend,
)

/**
 * Linearizable state machine: one review at a time, linked to its ORIGINAL page+fingerprint.
 * Navigating to another page can preserve that in-flight review, but it may only commit
 * to the original page after rechecking its current content on the UI dispatcher.
 * Every terminal callback is accepted at most once; cancel, exam and close revoke it.
 *
 * The caller must also check the actual ink fingerprint when mutating the page and invalidate
 * this gate whenever the relevant ink changes; this class cannot inspect Android ink itself.
 */
class ReviewLifecycleGate {
    private val mutex = Any()
    private data class Active(val ticket: ReviewTicket, val cancel: ReviewCancelHandle)
    private var scope: ReviewScope? = null
    private var active: Active? = null
    private var nextSequence = 0L
    private var exam = false
    private var closed = false

    /** Explicit hard reset (e.g. closing/reopening a notebook). Cancels active review. */
    fun openPage(next: ReviewScope) {
        val old = synchronized(mutex) {
            if (closed) return
            scope = next
            invalidateLocked()
        }
        old?.cancel()
    }

    /**
     * Ordinary navigation: retain a running review for the PREVIOUS page. If its source page
     * has not been edited, commitOnTarget() may safely write the result to that page.
     * This is deliberately different from openPage(), which hard-resets the session.
     */
    fun navigateTo(next: ReviewScope) {
        synchronized(mutex) {
            if (!closed) scope = next
        }
    }

    /**
     * An edit to an explicitly identified page: revoke only when it alters the original
     * review scope. Editing the currently visible DIFFERENT page must not kill background
     * work for its source page. Call from the page-edit dispatcher without suspension.
     */
    fun contentChanged(changed: ReviewScope) {
        val old = synchronized(mutex) {
            if (closed) return
            if (scope?.notebookId == changed.notebookId && scope?.pageId == changed.pageId) {
                scope = changed
            }
            val running = active?.ticket?.scope ?: return
            if (running.notebookId != changed.notebookId || running.pageId != changed.pageId ||
                running.fingerprint == changed.fingerprint) return
            invalidateLocked()
        }
        old?.cancel()
    }

    /**
     * Re-check the TARGET page content synchronously on the same UI dispatcher that edits
     * or saves it. The caller must fetch target from the stored original pageId, not
     * from the currently visible page. The mutation callback cannot suspend.
     */
    fun commitPartialOnTarget(ticket: ReviewTicket, actual: ReviewScope, apply: () -> Unit): Boolean {
        var cancelled: ReviewCancelHandle? = null
        val accepted = synchronized(mutex) {
            if (!matchesTicketLocked(ticket)) false
            else if (actual != ticket.scope) {
                cancelled = invalidateLocked()
                false
            } else {
                apply()
                true
            }
        }
        cancelled?.cancel()
        return accepted
    }

    fun commitFinalOnTarget(ticket: ReviewTicket, actual: ReviewScope, apply: () -> Unit): Boolean {
        var cancelled: ReviewCancelHandle? = null
        val accepted = synchronized(mutex) {
            if (!matchesTicketLocked(ticket)) false
            else if (actual != ticket.scope) {
                cancelled = invalidateLocked()
                false
            } else {
                try { apply() } finally { active = null }
                true
            }
        }
        cancelled?.cancel()
        return accepted
    }

    /** Supersede an active discussion when the selected note changes. */
    fun revokeCurrent() {
        val old = synchronized(mutex) { if (closed) return; invalidateLocked() }
        old?.cancel()
    }

    /** On an edit, fingerprint MUST be computed from the current selection/page content. */
    fun contentChanged(fingerprint: String) {
        require(fingerprint.isNotBlank())
        val changed = synchronized(mutex) { scope?.copy(fingerprint = fingerprint) } ?: return
        contentChanged(changed)
    }

    fun start(backend: ReviewBackend, cancelHandle: ReviewCancelHandle): ReviewTicket? = synchronized(mutex) {
        val s = scope ?: return null
        if (closed || exam || active != null) return null
        ReviewTicket(++nextSequence, s, backend).also { active = Active(it, cancelHandle) }
    }

    /** Start work for a captured page while another page is visible (for durable queue draining). */
    fun startFor(target: ReviewScope, backend: ReviewBackend, cancelHandle: ReviewCancelHandle): ReviewTicket? = synchronized(mutex) {
        if (closed || exam || active != null) return null
        ReviewTicket(++nextSequence, target, backend).also { active = Active(it, cancelHandle) }
    }

    /** Non-atomic observation for UI display only; NEVER use it to authorize a later write. */
    fun isCurrent(ticket: ReviewTicket): Boolean = synchronized(mutex) { matchesLocked(ticket) }

    /** Applies a partial mark under the same lock as scope invalidation; callback must not suspend. */
    fun commitPartial(ticket: ReviewTicket, apply: () -> Unit): Boolean = synchronized(mutex) {
        if (!matchesLocked(ticket)) return false
        apply()
        true
    }

    /** May be used while repairing malformed JSON; finishing the repair still requires this ticket. */
    fun commitFinal(ticket: ReviewTicket, apply: () -> Unit): Boolean = synchronized(mutex) {
        if (!matchesLocked(ticket)) return false
        try { apply() } finally { active = null }
        true
    }

    fun fail(ticket: ReviewTicket, applyFailure: () -> Unit): Boolean = commitFinal(ticket, applyFailure)

    /** Finish a failed request; clear its lease even when the original page is in background. */
    fun finishFailure(ticket: ReviewTicket, reportVisibleError: () -> Unit = {}): Boolean = synchronized(mutex) {
        if (!matchesTicketLocked(ticket)) return false
        try {
            if (scope == ticket.scope) reportVisibleError()
        } finally { active = null }
        true
    }

    fun cancel(ticket: ReviewTicket) {
        val handle = synchronized(mutex) {
            // A background request can be cancelled even when another page is visible.
            if (!matchesTicketLocked(ticket)) return
            invalidateLocked()
        }
        handle?.cancel()
    }

    /** Entering exam mode revokes running requests and blocks both local and remote inference. */
    fun setExamMode(enabled: Boolean) {
        val handle = synchronized(mutex) {
            if (closed || exam == enabled) return
            exam = enabled
            if (enabled) invalidateLocked() else null
        }
        handle?.cancel()
    }

    fun close() {
        val handle = synchronized(mutex) {
            if (closed) return
            closed = true
            scope = null
            invalidateLocked()
        }
        handle?.cancel()
    }

    private fun matchesTicketLocked(ticket: ReviewTicket): Boolean =
        !closed && !exam && active?.ticket == ticket

    // Legacy commits are restricted to the visible page; background work MUST use the
    // explicit target-aware variants above. No implicit cross-page writes.
    private fun matchesLocked(ticket: ReviewTicket): Boolean =
        matchesTicketLocked(ticket) && scope == ticket.scope

    private fun invalidateLocked(): ReviewCancelHandle? = active?.cancel.also { active = null }
}

/**
 * Pure queue lease ledger for REMOTE review requests. It does not persist entries; a journal
 * must persist changes atomically before announcing success. Local reviews MUST NOT enter it.
 * ACK only after a successful, durable review; release after any failure/timeout/cancellation.
 */
data class ReviewQueueKey(val notebookId: String, val pageId: String, val selectionSignature: String) {
    init { require(notebookId.isNotBlank() && pageId.isNotBlank()) }
}

data class ReviewQueueLease internal constructor(val id: Long, val key: ReviewQueueKey, val version: Long)

class ReviewQueueLedger {
    private data class Entry(var version: Long, var inFlight: ReviewQueueLease? = null)
    private val entries = LinkedHashMap<ReviewQueueKey, Entry>()
    private var nextVersion = 0L
    private var nextLease = 0L

    @Synchronized fun enqueue(key: ReviewQueueKey) {
        val existing = entries[key]
        when {
            existing == null -> entries[key] = Entry(++nextVersion)
            existing.inFlight != null -> existing.version = ++nextVersion
            // Pending duplicate: keep one item and stable ordering.
        }
    }

    /** A claim is exclusive, preserving all other queued pages. */
    @Synchronized fun claimNext(eligible: (ReviewQueueKey) -> Boolean = { true }): ReviewQueueLease? {
        for ((key, e) in entries) {
            if (e.inFlight == null && eligible(key)) {
                return ReviewQueueLease(++nextLease, key, e.version).also { e.inFlight = it }
            }
        }
        return null
    }

    /** An ACK of an old revision cannot erase a new request for the same key. */
    @Synchronized fun ack(lease: ReviewQueueLease): Boolean {
        val entry = entries[lease.key] ?: return false
        if (entry.inFlight != lease) return false
        if (entry.version == lease.version) entries.remove(lease.key) else entry.inFlight = null
        return true
    }

    /** Do not drop a request when a remote call fails or the editor closes. */
    @Synchronized fun release(lease: ReviewQueueLease): Boolean {
        val entry = entries[lease.key] ?: return false
        if (entry.inFlight != lease) return false
        entry.inFlight = null
        return true
    }

    /** Delete only requests explicitly belonging to this notebook and page. */
    @Synchronized fun discardPage(notebookId: String, pageId: String): Int {
        val keys = entries.keys.filter { it.notebookId == notebookId && it.pageId == pageId }
        keys.forEach(entries::remove)
        return keys.size
    }

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun pendingCount(): Int = entries.values.count { it.inFlight == null }
}
