package io.trazo.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.trazo.core.AiNote
import io.trazo.core.Bounds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StudyPracticeTransactionInstrumentedTest {
    private val note = AiNote("n", 1, "CALCULATION", "Error de signo", bounds = Bounds(0f, 0f, 1f, 1f))
    private fun base(): Context = ApplicationProvider.getApplicationContext<Context>().also { ctx ->
        listOf("trazo_study", "trazo_practice_progress").forEach {
            check(ctx.getSharedPreferences(it, 0).edit().clear().commit())
        }
    }
    private class CommitContext(base: Context, val commit: () -> Boolean) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(name, mode)
            if (name != "trazo_practice_progress") return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value); return this
                        }
                        override fun commit(): Boolean = if (this@CommitContext.commit.invoke()) editor.commit() else false
                    }
                }
            }
        }
    }
    @Test fun examTransitionCannotInterleaveWithActualPracticePersistence() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val ctx = CommitContext(base()) {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
        }
        val study = StudyStore(ctx)
        val progress = PracticeProgressStore(ctx) { study.examActive }
        val writer = Thread { try { progress.observeReviewedAttempt("attempt", listOf(note)) } catch (t: Throwable) { error.set(t) } }
        writer.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        val exam = Thread { try { study.startExam(listOf(note), 1L, 1); started.countDown() } catch (t: Throwable) { error.set(t) } }
        exam.start()
        assertFalse(started.await(100, TimeUnit.MILLISECONDS))
        release.countDown(); writer.join(5000); exam.join(5000)
        assertFalse(writer.isAlive); assertFalse(exam.isAlive); assertNull(error.get())
        assertTrue(study.examActive)
        assertEquals(1, progress.snapshot().sourceKeys.size)
        try { progress.observeReviewedAttempt("blocked", listOf(note)); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, progress.snapshot().sourceKeys.size)
    }
    @Test fun failedPracticeCommitNeverPublishesProgress() {
        val ctx = CommitContext(base()) { false }
        val study = StudyStore(ctx)
        val progress = PracticeProgressStore(ctx) { study.examActive }
        try { progress.observeReviewedAttempt("failed", listOf(note)); fail() } catch (_: IllegalStateException) { }
        assertTrue(progress.snapshot().sourceKeys.isEmpty())
        assertTrue(PracticeProgressStore(ctx) { study.examActive }.snapshot().sourceKeys.isEmpty())
        study.startExam(listOf(note), 2L, 1)
        assertTrue(study.examActive)
    }
}
