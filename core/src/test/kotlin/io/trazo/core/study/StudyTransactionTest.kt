package io.trazo.core.study

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StudyTransactionTest {
    @Test fun examTransitionWaitsForPracticeCommit() {
        val active = AtomicBoolean(false)
        val gate = ExamAccessGate { active.get() }
        val practiceEntered = CountDownLatch(1)
        val finishPractice = CountDownLatch(1)
        val examStarted = CountDownLatch(1)
        val committedBeforeExam = AtomicBoolean(false)
        val practice = Thread {
            synchronized(StudyTransactions.monitor) {
                check(!active.get())
                practiceEntered.countDown()
                check(finishPractice.await(5, TimeUnit.SECONDS))
                committedBeforeExam.set(!active.get())
            }
        }
        practice.start()
        assertTrue(practiceEntered.await(5, TimeUnit.SECONDS))
        val exam = Thread { gate.transition { active.set(true); examStarted.countDown() } }
        exam.start()
        assertFalse(examStarted.await(100, TimeUnit.MILLISECONDS))
        finishPractice.countDown()
        practice.join(5000); exam.join(5000)
        assertFalse(practice.isAlive); assertFalse(exam.isAlive)
        assertTrue(committedBeforeExam.get()); assertTrue(active.get())
        synchronized(StudyTransactions.monitor) { assertTrue(active.get()) }
    }

    @Test fun failedExamPersistenceInvalidatesOldCallbacks() {
        val gate = ExamAccessGate { false }
        val before = gate.issue(ExamHelp.AI_RESULT_CALLBACK)
        try { gate.transition { error("disk full") }; fail() } catch (_: IllegalStateException) { }
        assertFalse(gate.accept(before) { fail("stale callback") })
        assertTrue(gate.accept(gate.issue(ExamHelp.AI_RESULT_CALLBACK)) {})
    }
}
