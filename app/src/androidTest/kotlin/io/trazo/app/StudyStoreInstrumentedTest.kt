package io.trazo.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.trazo.app.data.ExamModeException
import io.trazo.app.data.StudyFeature
import io.trazo.app.data.StudyStore
import io.trazo.core.AiNote
import io.trazo.core.Bounds
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyStoreInstrumentedTest {
    @Test fun corruptInitialPageDoesNotOverwriteOriginalFile() {
        val app = ApplicationProvider.getApplicationContext<TrazoApp>()
        val book = app.repository.createNotebook("Corrupt page regression")
        app.repository.saveLazy(io.trazo.core.LazyNotebook.of(book).copy(slots = listOf(
            io.trazo.core.PageSlot.frozen("bad-page", """{"id":"bad-page","strokes":"invalid"}"""))))
        val file = java.io.File(app.filesDir, "notebooks/${book.id}.trazo.json")
        val before = file.readBytes()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        lateinit var vm: io.trazo.app.ui.EditorViewModel
        val owner = androidx.lifecycle.ViewModelStore()
        instrumentation.runOnMainSync {
            vm = io.trazo.app.ui.EditorViewModel(app, book.id)
            owner.put("corrupt", vm)
        }
        try {
            var loaded = false
            repeat(400) {
                instrumentation.runOnMainSync { loaded = !vm.loading }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue("Editor must leave loading state", loaded)
            instrumentation.runOnMainSync {
                assertNotNull(vm.pageLoadError)
                vm.saveNow()
                owner.clear()
            }
            assertArrayEquals(before, file.readBytes())
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            app.repository.deleteNotebook(book.id)
        }
    }

    @Test fun nativeInferenceLibrariesLoad() {
        val engine = io.trazo.app.ai.LlamaCppInference()
        try { assertTrue(engine.supportsVision) } finally { engine.close() }
        System.loadLibrary("llm_inference_engine_jni")
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val note = AiNote("n1", 1, "CALCULATION", "signo", bounds = Bounds(0f, 0f, 10f, 10f), reviewId = "r1")

    @Before @After
    fun clear() {
        context.getSharedPreferences("trazo_study", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun test_exam_lock_and_answers_survive_store_recreation_then_unlock_on_submit() {
        clear()
        val first = StudyStore(context)
        first.startExam(listOf(note), seed = 1L, questionCount = 2)
        assertTrue(first.examActive)
        try {
            first.requireAllowed(StudyFeature.AI_REVIEW)
            fail("AI should be blocked during exam")
        } catch (_: ExamModeException) { }
        try {
            first.requireAllowed(StudyFeature.CALCULATOR)
            fail("calculator should be blocked during exam")
        } catch (_: ExamModeException) { }

        val answer = first.state.value.exercises[0].correctAnswer
        first.submitExamAnswer(answer)
        val restored = StudyStore(context)
        assertTrue(restored.examActive)
        assertEquals(1, restored.state.value.index)
        assertEquals(answer, restored.state.value.examAnswers[0])
        restored.submitExamAnswer(restored.state.value.exercises[1].correctAnswer)
        assertEquals(2, restored.finishExam())
        assertFalse(restored.examActive)
        restored.requireAllowed(StudyFeature.AI_REVIEW)
        clear()
    }

    @Test fun test_practice_attempt_count_persists() {
        clear()
        val first = StudyStore(context)
        first.startPractice(listOf(note), seed = 0L)
        first.submitPracticeAnswer("wrong")
        val restored = StudyStore(context)
        assertEquals(1, restored.state.value.practiceAttempts)
        assertEquals("wrong", restored.state.value.practiceAnswer)
        assertFalse(restored.state.value.practiceCorrect ?: true)
        clear()
    }

    @Test fun test_damaged_saved_state_does_not_silently_unlock_an_active_exam() {
        clear()
        context.getSharedPreferences("trazo_study", android.content.Context.MODE_PRIVATE).edit()
            .putString("session", "{")
            .putBoolean("exam_active", true)
            .commit()
        val restored = StudyStore(context)
        assertTrue(restored.examActive)
        assertTrue(restored.state.value.examRecoveryLocked)
        assertTrue("No debe desbloquear un examen vacío", runCatching { restored.finishExam() }.isFailure)
        assertTrue("Recuperación requiere confirmación", runCatching { restored.abandonDamagedExam(false) }.isFailure)
        restored.abandonDamagedExam(true)
        assertFalse(restored.examActive)
        clear()
    }

    @Test fun test_timed_exam_expires_and_grades_unanswered_questions_after_recreation() {
        val times = object {
            var wall = 1_000_000L
            var elapsed = 10_000L
            fun now() = io.trazo.core.study.ExamClockReading(wall, elapsed)
        }
        val first = StudyStore(context, times::now)
        first.startExam(listOf(note), seed = 1L, questionCount = 2, durationMinutes = 1)
        val correct = first.state.value.exercises[0].correctAnswer
        first.submitExamAnswer(correct)
        times.wall += 61_000L; times.elapsed += 61_000L
        val restored = StudyStore(context, times::now)
        assertFalse(restored.examActive)
        assertEquals(io.trazo.app.data.ExamFinishReason.TIMEOUT, restored.state.value.examFinishReason)
        assertEquals(1, restored.state.value.examScore)
        restored.requireAllowed(StudyFeature.AI_REVIEW)
    }

    @Test fun test_wall_clock_rollback_never_increases_exam_time() {
        val times = object {
            var wall = 300_000L
            var elapsed = 20_000L
            fun now() = io.trazo.core.study.ExamClockReading(wall, elapsed)
        }
        val store = StudyStore(context, times::now)
        store.startExam(listOf(note), seed = 1L, questionCount = 1, durationMinutes = 1)
        times.wall += 15_000L; times.elapsed += 15_000L
        val remaining = store.examTime()!!.remainingMillis!!
        times.wall -= 90_000L; times.elapsed += 2_000L
        assertTrue(store.examTime()!!.remainingMillis!! <= remaining)
        store.refreshExam()
        assertTrue(store.state.value.examClock!!.clockAnomalies > 0)
        assertTrue(store.examActive)
    }

    @Test fun test_late_AI_callback_permit_never_revives_after_exam_finishes() {
        val store = StudyStore(context)
        val ticket = store.issueAid(io.trazo.core.study.ExamHelp.AI_RESULT_CALLBACK)
        store.startExam(listOf(note), seed = 1L, questionCount = 1)
        var changed = false
        assertFalse(store.acceptAid(ticket) { changed = true })
        store.submitExamAnswer(store.state.value.exercises[0].correctAnswer)
        store.finishExam()
        assertFalse(store.acceptAid(ticket) { changed = true })
        assertFalse(changed)
    }

    @Test fun test_marker_and_IDLE_record_disagreement_keeps_assistance_blocked() {
        val initial = StudyStore(context)
        initial.clearSession()
        context.getSharedPreferences("trazo_study", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("exam_active", true).commit()
        val restored = StudyStore(context)
        assertTrue(restored.examActive)
        assertTrue(restored.state.value.examRecoveryLocked)
        assertFalse(restored.isAllowed(StudyFeature.CALCULATOR))
    }
}
