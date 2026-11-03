package io.trazo.ai

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A persisted remote-only request. The null strokeIds denotes a FULL page; a nonempty list
 * denotes a selected area. The fingerprint identifies the source ink at enqueue time.
 * Never put OAuth tokens, API keys or entire rendered page images in the queue.
 */
data class PendingRemoteReview(
    val notebookId: String,
    val pageId: String,
    val strokeIds: List<String>?,
    val fingerprint: String,
    val createdAtMs: Long,
) {
    init {
        require(notebookId.isNotBlank() && pageId.isNotBlank() && fingerprint.isNotBlank())
        require(strokeIds == null || strokeIds.isNotEmpty())
        require(strokeIds == null || strokeIds.none { it.isBlank() })
    }

    val key: ReviewQueueKey get() = ReviewQueueKey(
        notebookId, pageId, strokeIds?.distinct()?.sorted()?.joinToString("\u001f") ?: "*",
    )
}

data class StoredRemoteReview(val revision: Long, val request: PendingRemoteReview)

/** Must write a COMPLETE snapshot or throw; never acknowledge a write before it is durable. */
interface ReviewQueueStorage {
    fun read(): List<StoredRemoteReview>
    fun write(snapshot: List<StoredRemoteReview>)
}

/**
 * File store for Android's app-private files directory or a JVM filesystem. No external libs.
 * A new file is fsync'ed and atomically renamed; if atomic replacement is unsupported, the
 * write FAILS CLOSED instead of deleting the previous snapshot. Do not use on an untrusted path.
 * For production Android, AtomicFile is an acceptable alternative ReviewQueueStorage adapter.
 */
class AtomicFileReviewQueueStorage(private val target: File) : ReviewQueueStorage {
    override fun read(): List<StoredRemoteReview> {
        if (!target.exists()) return emptyList()
        require(target.length() in 1..MAX_BYTES.toLong()) { "Invalid persisted queue size" }
        return DataInputStream(BufferedInputStream(FileInputStream(target))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid queue header" }
            require(input.readInt() == VERSION) { "Unsupported queue version" }
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid queue item count" }
            List(count) {
                val revision = input.readLong()
                require(revision > 0L) { "Invalid queue revision" }
                val notebookId = input.readUTF()
                val pageId = input.readUTF()
                val fingerprint = input.readUTF()
                val time = input.readLong()
                val selected = input.readBoolean()
                val ids = if (selected) {
                    val n = input.readInt()
                    require(n in 1..MAX_SELECTED_STROKES) { "Invalid selected ink count" }
                    List(n) { input.readUTF() }
                } else null
                StoredRemoteReview(revision, PendingRemoteReview(notebookId, pageId, ids, fingerprint, time))
            }.also { require(input.read() == -1) { "Trailing bytes in persisted queue" } }
        }
    }

    override fun write(snapshot: List<StoredRemoteReview>) {
        require(snapshot.size <= MAX_ENTRIES)
        val parent = target.absoluteFile.parentFile
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create queue directory" }
        val temp = File.createTempFile("trazo-ai-queue-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(snapshot.size)
                    snapshot.forEach { entry ->
                        val r = entry.request
                        output.writeLong(entry.revision)
                        output.writeUTF(r.notebookId)
                        output.writeUTF(r.pageId)
                        output.writeUTF(r.fingerprint)
                        output.writeLong(r.createdAtMs)
                        output.writeBoolean(r.strokeIds != null)
                        r.strokeIds?.let { list ->
                            require(list.size <= MAX_SELECTED_STROKES)
                            output.writeInt(list.size)
                            list.forEach(output::writeUTF)
                        }
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            require(temp.length() <= MAX_BYTES) { "Queue snapshot too large" }
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Filesystem does not support atomic queue replacement", e)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        const val MAGIC = 0x54525132 // TRQ2
        const val VERSION = 1
        const val MAX_ENTRIES = 2048
        const val MAX_SELECTED_STROKES = 8192
        const val MAX_BYTES = 4 * 1024 * 1024L
    }
}

/** A lease never escapes to disk. After process death, all unacked items are retryable. */
data class DurableReviewLease internal constructor(
    val token: Long,
    val revision: Long,
    val request: PendingRemoteReview,
)

/**
 * REAL durable queue adapter, not an in-memory ledger: writes are committed before changing
 * the in-memory view and an ACK removes a request only after successfully persisting removal.
 * Every public operation is serialized. Call blocking operations from Dispatchers.IO, not Main.
 *
 * A new enqueue while an old lease is in flight updates the durable revision. ACK of the old
 * lease must not delete the new request. The caller must ACK only after the reviewed result
 * has been durably committed to its original page. Failure/exam/cancel => release for retry.
 */
class DurableReviewQueue(private val storage: ReviewQueueStorage) {
    private data class Entry(var value: StoredRemoteReview, var lease: DurableReviewLease? = null)
    private val entries = LinkedHashMap<ReviewQueueKey, Entry>()
    private var revision = 0L
    private var leaseId = 0L

    init {
        for (s in storage.read()) {
            require(!entries.containsKey(s.request.key)) { "Duplicate persisted review key" }
            entries[s.request.key] = Entry(s)
            if (s.revision > revision) revision = s.revision
        }
    }

    /** One pending item per notebook/page/selection. Never enqueue an ON_DEVICE request. */
    @Synchronized fun enqueue(remote: PendingRemoteReview): Boolean {
        val key = remote.key
        val old = entries[key]
        if (old != null && old.lease == null && old.value.request == remote) return false
        val next = StoredRemoteReview(revision + 1, remote)
        val all = entries.values.map { if (it === old) next else it.value }.toMutableList()
        if (old == null) all.add(next)
        storage.write(all) // may throw; everything below remains untouched until success
        revision++
        if (old == null) entries[key] = Entry(next) else old.value = next
        return true
    }

    /** Eligibility allows the editor to drain only its own visible page and active notebook. */
    @Synchronized fun claimNext(eligible: (PendingRemoteReview) -> Boolean = { true }): DurableReviewLease? {
        for (entry in entries.values) {
            if (entry.lease == null && eligible(entry.value.request)) {
                val old = entry.value
                val lease = DurableReviewLease(++leaseId, old.revision, old.request)
                entry.lease = lease
                return lease
            }
        }
        return null
    }

    /** Returns true for a valid lease, but does NOT drop a newer revision of the same key. */
    @Synchronized fun acknowledge(lease: DurableReviewLease, successfullyCommitted: Boolean): Boolean {
        val e = entries[lease.request.key] ?: return false
        if (e.lease != lease) return false
        if (!successfullyCommitted) { e.lease = null; return true }
        if (e.value.revision != lease.revision) { e.lease = null; return true }
        val keep = entries.filterValues { it !== e }.values.map { it.value }
        storage.write(keep) // if this fails, original record and lease remain retryable
        entries.remove(lease.request.key)
        return true
    }

    /**
     * Correct ordering for a remotely completed review: persist the ORIGINAL-page review
     * first, then durably ACK the queue. If saving the page fails, the request is released
     * for retry (not acknowledged). If ACK persistence fails, the request remains on disk.
     * The supplied callback must itself be durable and idempotent by reviewId, since a
     * crash between the two writes can cause replay after restart.
     */
    fun completeAfterPersist(lease: DurableReviewLease, persistReview: () -> Unit): Boolean {
        try {
            persistReview()
        } catch (e: Throwable) {
            release(lease)
            throw e
        }
        return acknowledge(lease, successfullyCommitted = true)
    }

    @Synchronized fun release(lease: DurableReviewLease): Boolean = acknowledge(lease, false)

    @Synchronized fun discardPage(notebookId: String, pageId: String): Int {
        val remove = entries.keys.filter { it.notebookId == notebookId && it.pageId == pageId }.toSet()
        if (remove.isEmpty()) return 0
        storage.write(entries.filterKeys { it !in remove }.values.map { it.value })
        remove.forEach(entries::remove)
        return remove.size
    }

    @Synchronized fun pendingCount(): Int = entries.values.count { it.lease == null }
    @Synchronized fun pendingCount(notebookId: String): Int = entries.values.count {
        it.lease == null && it.value.request.notebookId == notebookId
    }
    @Synchronized fun size(): Int = entries.size
}
