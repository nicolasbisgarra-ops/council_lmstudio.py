package io.trazo.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.core.AiNote
import io.trazo.core.Bounds
import io.trazo.core.PracticeProgressEngine
import io.trazo.core.PracticeSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Must run on Android emulator/device; NOT included in the host-only 16/16 result. */
@RunWith(AndroidJUnit4::class)
class PracticeProgressStoreInstrumentedTest {
    @Test fun sharedTransactionSerializesExamAndPractice() {
        StudyPracticeTransactionInstrumentedTest().examTransitionCannotInterleaveWithActualPracticePersistence()
    }
    @Test fun failedProgressCommitDoesNotPublish() {
        StudyPracticeTransactionInstrumentedTest().failedPracticeCommitNeverPublishesProgress()
    }

    @Test fun reviewDedupPersistsAndExamGuardBlocksWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("trazo_practice_progress", Context.MODE_PRIVATE).edit().clear().commit()
        var exam = false
        val store = PracticeProgressStore(context) { exam }
        val mark = AiNote("a", 1, "CALCULATION", short = "Error de signo al sumar", bounds = Bounds(0f, 0f, 1f, 1f), reviewId = "r1")
        store.observeReviewedAttempt("page:attempt-1", listOf(mark))
        store.observeReviewedAttempt("page:attempt-1", listOf(mark.copy(reviewId = "r2")))
        assertEquals(1, PracticeProgressEngine.sourceFrequencies(store.snapshot())[PracticeSkill.SIGN_AND_ARITHMETIC])
        val reopened = PracticeProgressStore(context) { exam }
        assertEquals(1, PracticeProgressEngine.sourceFrequencies(reopened.snapshot())[PracticeSkill.SIGN_AND_ARITHMETIC])
        exam = true
        var blocked = false
        try { reopened.observeReviewedAttempt("another", listOf(mark)) }
        catch (_: IllegalStateException) { blocked = true }
        assertTrue(blocked)
        assertFalse(reopened.snapshot().sourceKeys.any { it.attemptId == "another" })
    }
}
