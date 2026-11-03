package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReviewLifecycleGateTest {
    private val a = ReviewScope("notebookA", "page1", "hash1")
    private val b = ReviewScope("notebookA", "page2", "hash2")
    private fun gate() = ReviewLifecycleGate().also { it.openPage(a) }

    @Test fun localRunsWithoutInternetAndNoFallback() {
        assertEquals(ReviewRoute.RUN_LOCAL, ReviewRouting.choose(ReviewBackend.ON_DEVICE, false, true, false))
        assertEquals(ReviewRoute.RUN_LOCAL, ReviewRouting.choose(ReviewBackend.ON_DEVICE, true, true, false))
        assertEquals(ReviewRoute.BLOCKED_UNAVAILABLE, ReviewRouting.choose(ReviewBackend.ON_DEVICE, true, false, false))
    }

    @Test fun remoteOfflineQueuedNotSilentlyChangedToLocal() {
        assertEquals(ReviewRoute.QUEUE_REMOTE, ReviewRouting.choose(ReviewBackend.REMOTE, false, true, false))
        assertEquals(ReviewRoute.RUN_REMOTE, ReviewRouting.choose(ReviewBackend.REMOTE, true, false, false))
    }

    @Test fun examBlocksBothProviders() {
        for (b in ReviewBackend.entries) {
            assertEquals(ReviewRoute.BLOCKED_EXAM, ReviewRouting.choose(b, true, true, true))
        }
    }

    @Test fun requestCanCommitPartialThenFinalOnce() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        val count = AtomicInteger()
        assertTrue(g.commitPartial(t) { count.incrementAndGet() })
        assertTrue(g.commitFinal(t) { count.incrementAndGet() })
        assertFalse(g.commitPartial(t) { count.incrementAndGet() })
        assertFalse(g.commitFinal(t) { count.incrementAndGet() })
        assertEquals(2, count.get())
    }

    @Test fun queuedTargetCanCommitWhileDifferentPageIsVisible() {
        val g = gate()
        val t = g.startFor(b, ReviewBackend.REMOTE, ReviewCancelHandle {})!!
        assertTrue(g.commitPartialOnTarget(t, b) {})
        g.contentChanged(a.copy(fingerprint = "visible-page-edited"))
        assertTrue(g.commitFinalOnTarget(t, b) {})
    }

    @Test fun duplicateConcurrentStartRejected() {
        val g = gate()
        assertNotNull(g.start(ReviewBackend.REMOTE, ReviewCancelHandle {}))
        assertNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
    }

    @Test fun userCancelDiscardsLateMarksAndFinish() {
        val g = gate()
        val cancels = AtomicInteger()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle { cancels.incrementAndGet() })!!
        g.cancel(t)
        assertEquals(1, cancels.get())
        assertFalse(g.commitPartial(t) { fail("late partial mark") })
        assertFalse(g.commitFinal(t) { fail("late final") })
    }

    @Test fun changingPageInvalidatesReview() {
        val g = gate()
        val cancels = AtomicInteger()
        val t = g.start(ReviewBackend.REMOTE, ReviewCancelHandle { cancels.incrementAndGet() })!!
        g.openPage(b)
        assertEquals(1, cancels.get())
        assertFalse(g.commitFinal(t) { fail("old page mutated") })
        assertNotNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
    }

    @Test fun reopeningSamePageInvalidatesOldGeneration() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.openPage(a)
        assertFalse(g.commitPartial(t) { fail("old instance reopened") })
        val fresh = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        assertTrue(fresh.sequence > t.sequence)
    }

    @Test fun changedInkInvalidatesEvenWithSameIdsAndBounds() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.contentChanged("hash-of-edited-ink")
        assertFalse(g.commitFinal(t) { fail("stale ink") })
        assertEquals("hash-of-edited-ink", g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!.scope.fingerprint)
    }

    @Test fun sameFingerprintLeavesRequestIntact() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.contentChanged("hash1")
        assertTrue(g.commitFinal(t) {})
    }

    @Test fun startInExamDeniedCancelAndResumeNewGeneration() {
        val g = gate()
        val cancels = AtomicInteger()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle { cancels.incrementAndGet() })!!
        g.setExamMode(true)
        assertEquals(1, cancels.get())
        assertNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
        assertFalse(g.commitPartial(t) { fail("exam stale") })
        g.setExamMode(false)
        assertNotNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
    }

    @Test fun closeDiscardsAllRequestsForever() {
        val g = gate()
        val cancels = AtomicInteger()
        val t = g.start(ReviewBackend.REMOTE, ReviewCancelHandle { cancels.incrementAndGet() })!!
        g.close()
        g.close()
        g.openPage(b)
        assertEquals(1, cancels.get())
        assertNull(g.start(ReviewBackend.REMOTE, ReviewCancelHandle {}))
        assertFalse(g.commitFinal(t) { fail("after close") })
    }

    @Test fun failureIsTerminalAndNewRequestIsPossible() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        var errorCount = 0
        assertTrue(g.fail(t) { errorCount++ })
        assertFalse(g.fail(t) { errorCount++ })
        assertEquals(1, errorCount)
        assertNotNull(g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {}))
    }

    @Test fun cancellationDuringRepairRejectsRepairedJsonResult() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        assertTrue(g.commitPartial(t) {}) // partial, still same request while repair starts
        g.cancel(t)
        assertFalse(g.commitFinal(t) { fail("repaired JSON approved after cancel") })
    }

    @Test fun staleWorkerCannotReenableSpinnerAfterSwitch() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        g.openPage(b)
        var spinner = false
        assertFalse(g.commitPartial(t) { spinner = true })
        assertFalse(spinner)
    }

    @Test fun atomicCommitAndPageSwitchAreSerialized() {
        val g = gate()
        val t = g.start(ReviewBackend.ON_DEVICE, ReviewCancelHandle {})!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val result = pool.submit<Boolean> {
            g.commitFinal(t) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val switched = CountDownLatch(1)
        val other = pool.submit { g.openPage(b); switched.countDown() }
        assertFalse(switched.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(result.get(2, TimeUnit.SECONDS))
        other.get(2, TimeUnit.SECONDS)
        assertTrue(switched.count == 0L)
        assertFalse(g.commitFinal(t) { fail("duplicate") })
        pool.shutdownNow()
    }
}

class ReviewQueueLedgerTest {
    private val a = ReviewQueueKey("n1", "p1", "all")
    private val b = ReviewQueueKey("n1", "p2", "selected-x")
    private val c = ReviewQueueKey("n2", "p1", "all")

    @Test fun duplicatePendingEnqueueIsIdempotent() {
        val q = ReviewQueueLedger()
        repeat(100) { q.enqueue(a) }
        assertEquals(1, q.size())
        assertEquals(1, q.pendingCount())
    }

    @Test fun claimIsExclusiveAndAckRemovesOnlyAfterSuccess() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        val lease = q.claimNext()!!
        assertEquals(0, q.pendingCount())
        assertNull(q.claimNext())
        assertTrue(q.ack(lease))
        assertFalse(q.ack(lease))
        assertEquals(0, q.size())
    }

    @Test fun failureReleasesForRetryWithoutLoss() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        val old = q.claimNext()!!
        assertTrue(q.release(old))
        assertEquals(1, q.size())
        val fresh = q.claimNext()!!
        assertTrue(fresh.id != old.id)
        assertFalse(q.ack(old))
        assertTrue(q.ack(fresh))
    }

    @Test fun enqueueDuringInflightPreservesNewRevisionAfterAck() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        val first = q.claimNext()!!
        q.enqueue(a)
        assertTrue(q.ack(first))
        assertEquals(1, q.pendingCount())
        val second = q.claimNext()!!
        assertTrue(second.version > first.version)
        assertTrue(q.ack(second))
        assertEquals(0, q.size())
    }

    @Test fun twoNotebooksWithSamePageIdAreIndependent() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        q.enqueue(c)
        assertEquals(2, q.size())
        assertEquals(1, q.discardPage("n1", "p1"))
        assertEquals(c, q.claimNext()!!.key)
    }

    @Test fun deletedPageDiscardsOnlyItsRequests() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        q.enqueue(b)
        q.enqueue(c)
        val old = q.claimNext { it == a }!!
        assertEquals(1, q.discardPage("n1", "p1"))
        assertFalse(q.ack(old))
        assertEquals(2, q.size())
    }

    @Test fun eligibleQueueDrainNeverClaimsOtherPage() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        q.enqueue(b)
        assertEquals(b, q.claimNext { it.pageId == "p2" }!!.key)
        assertEquals(1, q.pendingCount())
    }

    @Test fun concurrentDrainObtainsOnlyOneLease() {
        val q = ReviewQueueLedger()
        q.enqueue(a)
        val pool = Executors.newFixedThreadPool(8)
        val futures = List(8) { pool.submit<ReviewQueueLease?> { q.claimNext() } }
        val leases = futures.mapNotNull { it.get(2, TimeUnit.SECONDS) }
        assertEquals(1, leases.size)
        pool.shutdownNow()
    }
}


class ReviewBackendDispatcherTest {
    private var localCalls = 0
    private var remoteCalls = 0
    private val local = ReviewCompletionEngine { _, cb -> localCalls++; cb("a"); "local" }
    private val remote = ReviewCompletionEngine { _, cb -> remoteCalls++; cb("b"); "remote" }

    @Test fun localOfflineNeverInvokesRemoteFake() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(true, true))
        val route = ReviewRouting.choose(ReviewBackend.ON_DEVICE, false, true, false)
        assertEquals("local", d.execute(route, ReviewPayload("check", byteArrayOf(1))))
        assertEquals(1, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test fun remoteOfflineNeverSilentlyFallsBackToLocalFake() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(true, true))
        val route = ReviewRouting.choose(ReviewBackend.REMOTE, false, true, false)
        try { d.execute(route, ReviewPayload("check")); fail("must defer") }
        catch (_: ReviewDeferred) { }
        assertEquals(0, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test fun localNoVisionExplicitlyRejectsImages() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(false, true))
        try { d.execute(ReviewRoute.RUN_LOCAL, ReviewPayload("check", byteArrayOf(1))); fail("must reject") }
        catch (_: ReviewUnavailable) { }
        assertEquals(0, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test fun localWithoutStreamingOnlyDeliversFinalTextOnce() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(true, false))
        val events = mutableListOf<String>()
        assertEquals("local", d.execute(ReviewRoute.RUN_LOCAL, ReviewPayload("check"), events::add))
        assertEquals(listOf("local"), events)
    }

    @Test fun remoteOnlineNeverUsesLocalFake() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(false, false))
        assertEquals("remote", d.execute(ReviewRoute.RUN_REMOTE, ReviewPayload("check")))
        assertEquals(0, localCalls)
        assertEquals(1, remoteCalls)
    }

    @Test fun examBlocksBeforeEitherBackend() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(true, true))
        try { d.execute(ReviewRoute.BLOCKED_EXAM, ReviewPayload("check")); fail("must reject") }
        catch (_: ReviewUnavailable) { }
        assertEquals(0, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test fun unavailableBackendFailsClosed() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(true, true))
        try { d.execute(ReviewRoute.BLOCKED_UNAVAILABLE, ReviewPayload("check")); fail("must reject") }
        catch (_: ReviewUnavailable) { }
        assertEquals(0, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test fun incrementalCallbackIsPreservedForVisionModel() {
        val d = ReviewBackendDispatcher(local, remote, ReviewEngineCapabilities(true, true))
        val deltas = mutableListOf<String>()
        assertEquals("local", d.execute(ReviewRoute.RUN_LOCAL, ReviewPayload("check"), deltas::add))
        assertEquals(listOf("a"), deltas)
    }
}
