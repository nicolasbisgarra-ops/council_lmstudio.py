package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DurableReviewQueueTest {
    private fun dir() = Files.createTempDirectory("trazo-queue-").toFile().apply { deleteOnExit() }
    private fun request(page: String = "page1", notebook: String = "n1", ink: String = "ink1", time: Long = 1L) =
        PendingRemoteReview(notebook, page, null, ink, time)
    private fun queue(d: File) = DurableReviewQueue(AtomicFileReviewQueueStorage(File(d, "pending.bin")))

    @Test fun pendingRemoteSurvivesProcessRestart() {
        val d = dir()
        queue(d).enqueue(request())
        val resumed = queue(d)
        assertEquals(1, resumed.size())
        assertEquals(request(), resumed.claimNext()!!.request)
    }

    @Test fun claimWithoutAckSurvivesRestartForRetry() {
        val d = dir()
        val first = queue(d)
        first.enqueue(request())
        assertNotNull(first.claimNext())
        val afterCrash = queue(d)
        assertEquals(1, afterCrash.pendingCount())
        assertTrue(afterCrash.acknowledge(afterCrash.claimNext()!!, true))
        assertEquals(0, queue(d).size())
    }

    @Test fun failedServerKeepsPendingEvenAfterRestart() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request())
        val lease = q.claimNext()!!
        assertTrue(q.acknowledge(lease, false))
        assertEquals(1, queue(d).size())
    }

    @Test fun noAckAfterFailureToPersist() {
        val calls = AtomicInteger()
        val store = object : ReviewQueueStorage {
            var state = emptyList<StoredRemoteReview>()
            override fun read() = state
            override fun write(snapshot: List<StoredRemoteReview>) {
                if (calls.incrementAndGet() == 2) error("simulated ENOSPC")
                state = snapshot.toList()
            }
        }
        val q = DurableReviewQueue(store)
        q.enqueue(request())
        val lease = q.claimNext()!!
        try { q.acknowledge(lease, true); fail("ACK must fail if durable removal fails") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("ENOSPC")) }
        assertEquals(1, q.size())
        assertEquals(1, DurableReviewQueue(store).pendingCount())
    }

    @Test fun newEnqueueDuringInflightSurvivesOlderAckAndRestart() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request(ink="v1", time=1))
        val lease = q.claimNext()!!
        q.enqueue(request(ink="v2", time=2))
        assertTrue(q.acknowledge(lease, true))
        assertEquals(1, q.pendingCount())
        val recovered = queue(d)
        assertEquals("v2", recovered.claimNext()!!.request.fingerprint)
        assertEquals(1, recovered.size())
    }

    @Test fun writeFailureOnEnqueueDoesNotMutateMemoryOrDisk() {
        val store = object : ReviewQueueStorage {
            override fun read() = emptyList<StoredRemoteReview>()
            override fun write(snapshot: List<StoredRemoteReview>): Unit = error("disk full")
        }
        val q = DurableReviewQueue(store)
        try { q.enqueue(request()); fail("expected disk failure") } catch (_: IllegalStateException) {}
        assertEquals(0, q.size())
    }

    @Test fun samePageIdDifferentNotebooksNeverMerge() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request(notebook="n1"))
        q.enqueue(request(notebook="n2"))
        assertEquals(2, q.size())
        assertEquals(1, q.discardPage("n1", "page1"))
        assertEquals("n2", queue(d).claimNext()!!.request.notebookId)
    }

    @Test fun selectedStrokeOrderIsCanonicalAndDoesNotDuplicateKey() {
        val q = queue(dir())
        q.enqueue(PendingRemoteReview("n", "p", listOf("b", "a"), "x", 1))
        q.enqueue(PendingRemoteReview("n", "p", listOf("a", "b"), "x", 2))
        assertEquals(1, q.size())
    }

    @Test fun secondNotebookPageIsNotClaimedByFirst() {
        val q = queue(dir())
        q.enqueue(request(page="one"))
        q.enqueue(request(page="two"))
        val l = q.claimNext { it.pageId == "two" }!!
        assertEquals("two", l.request.pageId)
        assertEquals(1, q.pendingCount())
        assertNull(q.claimNext { it.pageId == "two" })
    }

    @Test fun corruptFileNeverSilentlyDeletesQueuedWork() {
        val d = dir()
        val file = File(d, "pending.bin")
        file.writeText("corrupted")
        try { queue(d); fail("should reject corrupt data") } catch (_: Exception) {}
        assertEquals("corrupted", file.readText())
    }

    @Test fun concurrentDrainSingleLeaseAndPendingItemIsKept() {
        val q = queue(dir())
        q.enqueue(request())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val claims = (0 until 8).map { pool.submit<DurableReviewLease?> { q.claimNext() } }
            assertEquals(1, claims.mapNotNull { it.get(3, TimeUnit.SECONDS) }.size)
            assertEquals(1, q.size())
        } finally { pool.shutdownNow() }
    }

    @Test fun completedReviewIsSavedBeforeQueueAck() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request())
        val lease = q.claimNext()!!
        var saved = false
        assertTrue(q.completeAfterPersist(lease) {
            assertEquals(1, queue(d).size()) // ACK has not yet happened
            saved = true
        })
        assertTrue(saved)
        assertEquals(0, queue(d).size())
    }

    @Test fun failedPageSaveKeepsRequestDurableForRetry() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request())
        val lease = q.claimNext()!!
        try { q.completeAfterPersist(lease) { error("page write failed") }; fail("expected fail") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("page write")) }
        assertEquals(1, queue(d).pendingCount())
        assertNotNull(q.claimNext())
    }

    @Test fun newerRequestEnqueuedInsidePageSaveIsNotLostByOldCompletion() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request(ink="version1"))
        val lease = q.claimNext()!!
        assertTrue(q.completeAfterPersist(lease) { q.enqueue(request(ink="version2", time=2)) })
        assertEquals("version2", queue(d).claimNext()!!.request.fingerprint)
    }

    @Test fun atomicCommitCannotBeAckedAfterNewEnqueueRaces() {
        val d = dir()
        val q = queue(d)
        q.enqueue(request(ink="old"))
        val lease = q.claimNext()!!
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            q.enqueue(request(ink="new", time=2))
        }
        thread.start()
        assertTrue(started.await(3, TimeUnit.SECONDS))
        release.countDown()
        thread.join(3000)
        assertTrue(q.acknowledge(lease, true))
        assertEquals("new", queue(d).claimNext()!!.request.fingerprint)
    }
}

class BackgroundReviewGateTest {
    private val original = ReviewScope("nb", "page1", "ink-A")
    private val other = ReviewScope("nb", "page2", "ink-B")
    private fun started() = ReviewLifecycleGate().also { it.openPage(original) }

    @Test fun backgroundReviewCanFinishInOriginalPageAfterNavigation() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        var wroteTo = ""
        assertTrue(g.commitPartialOnTarget(t, original) { wroteTo = original.pageId })
        assertTrue(g.commitFinalOnTarget(t, original) { wroteTo = original.pageId })
        assertEquals("page1", wroteTo)
        assertFalse(g.commitFinalOnTarget(t, original) { fail("duplicate final") })
        assertNotNull(g.start(ReviewBackend.REMOTE, ReviewCancelHandle {}))
    }

    @Test fun cannotWriteOldResultOntoCurrentlyVisibleDifferentPage() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        assertFalse(g.commitPartialOnTarget(t, other) { fail("must not apply to visible page2") })
    }

    @Test fun editingAnotherPageDoesNotInvalidateOriginalReview() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        g.contentChanged(other.copy(fingerprint = "other-edit"))
        assertTrue(g.commitFinalOnTarget(t, original) {})
    }

    @Test fun editingOriginalPageRejectsOldReviewEvenIfBackground() {
        val g = started()
        val cancels = AtomicInteger()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle { cancels.incrementAndGet() })!!
        g.navigateTo(other)
        g.contentChanged(original.copy(fingerprint = "new-ink"))
        assertFalse(g.commitFinalOnTarget(t, original) { fail("old review") })
        assertEquals(1, cancels.get())
    }

    @Test fun staleActualInkCannotCommitWhenNoEditNotificationArrived() {
        val g = started()
        val cancels = AtomicInteger()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle { cancels.incrementAndGet() })!!
        g.navigateTo(other)
        assertFalse(g.commitFinalOnTarget(t, original.copy(fingerprint = "different")) { fail("stale") })
        assertEquals(1, cancels.get())
        assertNotNull(g.start(ReviewBackend.REMOTE, ReviewCancelHandle {}))
    }

    @Test fun cancelRepairThenNavigateBlocksDelayedCallback() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.commitPartialOnTarget(t, original) {}
        g.cancel(t)
        g.navigateTo(other)
        assertFalse(g.commitFinalOnTarget(t, original) { fail("cancelled repair") })
    }

    @Test fun examBlocksBackgroundCompletionAndBothBackends() {
        val g = started()
        val t = g.start(ReviewBackend.REMOTE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        g.setExamMode(true)
        assertFalse(g.commitFinalOnTarget(t, original) { fail("exam leak") })
        assertNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
    }

    @Test fun discussionSupersededByAnotherSelectionCannotUpdateOldNote() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.revokeCurrent() // user selected a different note
        assertFalse(g.commitFinalOnTarget(t, original) { fail("outdated discussion") })
    }

    @Test fun cancelBackgroundAfterNavigationRevokesItsLease() {
        val g = started()
        val canceled = AtomicInteger()
        val t = g.start(ReviewBackend.REMOTE, ReviewCancelHandle { canceled.incrementAndGet() })!!
        g.navigateTo(other)
        g.cancel(t)
        assertEquals(1, canceled.get())
        assertFalse(g.commitFinalOnTarget(t, original) { fail("cancelled in background") })
        assertNotNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
    }

    @Test fun backgroundFailureReleasesLeaseWithoutShowingWrongPageError() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        var visibleError = false
        assertTrue(g.finishFailure(t) { visibleError = true })
        assertFalse(visibleError)
        assertNotNull(g.start(ReviewBackend.REMOTE, ReviewCancelHandle {}))
    }

    @Test fun editingOtherVisiblePageViaLegacyCallKeepsOriginalTicket() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        g.contentChanged("changed-visible-page")
        assertTrue(g.commitFinalOnTarget(t, original) {})
    }

    @Test fun closingEditorInvalidatesBackgroundWork() {
        val g = started()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.navigateTo(other)
        g.close()
        assertFalse(g.commitPartialOnTarget(t, original) { fail("closed") })
    }
}
