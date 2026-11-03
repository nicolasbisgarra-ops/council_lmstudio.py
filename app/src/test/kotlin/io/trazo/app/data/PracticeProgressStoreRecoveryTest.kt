package io.trazo.app.data

import android.app.Application
import android.content.Context
import io.trazo.core.AiNote
import io.trazo.core.Bounds
import io.trazo.core.PracticeExercise
import io.trazo.core.PracticePlanner
import io.trazo.core.PracticeProgress
import io.trazo.core.PracticeProgressCodec
import io.trazo.core.PracticeSkill
import io.trazo.core.PracticeSkillState
import io.trazo.core.PracticeSourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class PracticeProgressStoreRecoveryTest {
    private val skill = PracticeSkill.ONE_STEP_EQUATION
    private val primaryKey = "practice_progress_v2"
    private val backupKey = "practice_progress_backup_v2"

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun prefs(context: Context = context()) = context.applicationContext
        .getSharedPreferences("trazo_practice_progress", Context.MODE_PRIVATE)

    private fun reset(context: Context = context()) {
        assertTrue(prefs(context).edit().clear().commit())
    }

    private fun progress(
        sources: List<String> = emptyList(),
        submissions: List<String> = emptyList(),
        attempts: Int = 0,
    ) = PracticeProgress(
        skills = if (attempts == 0) emptyMap() else mapOf(skill to PracticeSkillState(attempts = attempts)),
        submissionIds = LinkedHashSet(submissions),
        sourceKeys = LinkedHashSet(sources.map { PracticeSourceKey(it, skill) }),
    )

    private fun corruptRow(text: String, type: String, occurrence: Int = 0): String {
        val lines = text.lines().toMutableList()
        val matches = lines.indices.filter { lines[it].startsWith("$type\t") }
        val index = matches[occurrence]
        val row = lines[index]
        lines[index] = row.dropLast(1) + if (row.last() == '0') "1" else "0"
        return lines.joinToString("\n")
    }

    private fun writeBlobs(context: Context, primary: String, backup: String) {
        assertTrue(prefs(context).edit()
            .putString(primaryKey, primary)
            .putString(backupKey, backup)
            .commit())
    }

    private fun sourceIds(state: PracticeProgress) = state.sourceKeys.map { it.attemptId }.toSet()

    private fun note(id: String) = AiNote(
        id = id,
        line = 1,
        kind = "CALCULATION",
        short = "Error de signo al sumar",
        bounds = Bounds(0f, 0f, 1f, 1f),
        reviewId = "review-$id",
    )

    private fun exercise() = PracticePlanner.build(mapOf(skill to 1), seed = 7L).single()

    @Test fun `partial primary adds valid recent rows to clean older backup`() {
        val context = context()
        reset(context)
        val backup = PracticeProgressCodec.encode(progress(sources = listOf("source-old"), attempts = 1))
        val primary = PracticeProgressCodec.encode(progress(
            sources = listOf("source-old", "source-new-a", "source-new-b"), attempts = 3,
        ))
        val damagedPrimary = corruptRow(primary, "E")
        assertTrue(PracticeProgressCodec.decode(damagedPrimary).skippedRows > 0)
        assertEquals(0, PracticeProgressCodec.decode(backup).skippedRows)
        writeBlobs(context, damagedPrimary, backup)

        val recovered = PracticeProgressStore(context) { false }.snapshot()

        assertEquals(setOf("source-old", "source-new-a", "source-new-b"), sourceIds(recovered))
        assertEquals(3, recovered.skills.getValue(skill).attempts)
    }

    @Test fun `two partial blobs salvage valid rows from both sides`() {
        val context = context()
        reset(context)
        val primarySources = (0 until 700).map { "primary-$it" }
        val backupSources = (0 until 700).map { "backup-$it" }
        val primarySubmissions = (0 until 700).map { "primary-sub-$it" }
        val backupSubmissions = (0 until 700).map { "backup-sub-$it" }
        val primary = corruptRow(
            PracticeProgressCodec.encode(progress(sources = primarySources, submissions = primarySubmissions)),
            "E",
            occurrence = 0,
        )
        val backup = corruptRow(
            PracticeProgressCodec.encode(progress(sources = backupSources, submissions = backupSubmissions)),
            "E",
            occurrence = 0,
        )
        assertTrue(PracticeProgressCodec.decode(primary).skippedRows > 0)
        assertTrue(PracticeProgressCodec.decode(backup).skippedRows > 0)
        writeBlobs(context, primary, backup)

        val recovered = PracticeProgressStore(context) { false }.snapshot()

        val recoveredSources = sourceIds(recovered)
        assertEquals(1024, recoveredSources.size)
        assertTrue("primary rows are newer and retained", "primary-1" in recoveredSources && "primary-699" in recoveredSources)
        assertTrue("latest backup row remains", "backup-699" in recoveredSources)
        assertFalse("oldest backup rows are trimmed", "backup-1" in recoveredSources)
        assertEquals(1024, recovered.submissionIds.size)
        assertTrue("new primary submission remains", "primary-sub-0" in recovered.submissionIds)
        assertTrue("latest backup submission remains", "backup-sub-699" in recovered.submissionIds)
        assertFalse("oldest backup submissions are trimmed", "backup-sub-0" in recovered.submissionIds)
    }

    @Test fun `sampled CRC failures discard only their own S A and E rows`() {
        val sample = PracticeProgressCodec.encode(progress(
            sources = listOf("sample-source"), submissions = listOf("sample-submission"), attempts = 4,
        ))
        for (rowType in listOf("S", "A", "E")) {
            val decoded = PracticeProgressCodec.decode(corruptRow(sample, rowType))
            assertEquals("CRC damage in $rowType should skip exactly one row", 1, decoded.skippedRows)
            when (rowType) {
                "S" -> assertFalse(skill in decoded.progress.skills)
                "A" -> assertFalse("sample-submission" in decoded.progress.submissionIds)
                "E" -> assertFalse("sample-source" in sourceIds(decoded.progress))
            }
        }

        val context = context()
        reset(context)
        val primary = corruptRow(PracticeProgressCodec.encode(progress(
            sources = listOf("primary-source"), submissions = listOf("primary-submission"), attempts = 7,
        )), "A")
        val backup = PracticeProgressCodec.encode(progress(
            sources = listOf("backup-source"), submissions = listOf("backup-submission"), attempts = 2,
        ))
        writeBlobs(context, primary, backup)

        val recovered = PracticeProgressStore(context) { false }.snapshot()

        assertEquals(7, recovered.skills.getValue(skill).attempts) // primary row wins; counters are not added
        assertEquals(setOf("backup-submission"), recovered.submissionIds)
        assertEquals(setOf("backup-source", "primary-source"), sourceIds(recovered))
    }

    @Test fun `degraded recovery blocks writes and preserves both original blobs`() {
        val context = context()
        reset(context)
        val backup = PracticeProgressCodec.encode(progress(sources = listOf("older")))
        val primary = corruptRow(PracticeProgressCodec.encode(progress(sources = listOf("older", "newer"))), "E")
        writeBlobs(context, primary, backup)
        val store = PracticeProgressStore(context) { false }
        assertEquals(setOf("older", "newer"), sourceIds(store.snapshot()))
        val beforePrimary = prefs(context).getString(primaryKey, null)
        val beforeBackup = prefs(context).getString(backupKey, null)

        assertThrows(IllegalStateException::class.java) {
            store.observeReviewedAttempt("blocked-source", listOf(note("blocked")))
        }
        assertThrows(IllegalStateException::class.java) {
            store.submitExercise(exercise(), "5", "blocked-submission", 100L)
        }

        assertEquals(beforePrimary, prefs(context).getString(primaryKey, null))
        assertEquals(beforeBackup, prefs(context).getString(backupKey, null))
        assertFalse("blocked-source" in sourceIds(store.snapshot()))
    }

    @Test fun `store instances refresh under shared transaction and exam gate blocks mutations`() {
        val context = context()
        reset(context)
        var examActive = false
        val first = PracticeProgressStore(context) { examActive }
        val second = PracticeProgressStore(context) { examActive }

        first.observeReviewedAttempt("instance-a", listOf(note("a")))
        second.observeReviewedAttempt("instance-b", listOf(note("b")))
        val expected = setOf("instance-a", "instance-b")
        assertEquals(expected, sourceIds(first.snapshot()))
        assertEquals(expected, sourceIds(second.snapshot()))

        examActive = true
        val beforePrimary = prefs(context).getString(primaryKey, null)
        val beforeBackup = prefs(context).getString(backupKey, null)
        assertThrows(IllegalStateException::class.java) {
            first.observeReviewedAttempt("during-exam", listOf(note("exam")))
        }
        assertThrows(IllegalStateException::class.java) {
            second.submitExercise(exercise(), "5", "during-exam-submission", 200L)
        }
        assertEquals(expected, sourceIds(first.snapshot()))
        assertEquals(beforePrimary, prefs(context).getString(primaryKey, null))
        assertEquals(beforeBackup, prefs(context).getString(backupKey, null))
    }
}
