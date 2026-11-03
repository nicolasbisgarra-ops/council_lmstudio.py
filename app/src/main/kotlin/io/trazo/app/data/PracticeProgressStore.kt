package io.trazo.app.data

import android.content.Context
import io.trazo.core.AiNote
import io.trazo.core.PracticeExercise
import io.trazo.core.PracticeLinearProblem
import io.trazo.core.PracticePlanner
import io.trazo.core.PracticeProgress
import io.trazo.core.PracticeProgressCodec
import io.trazo.core.PracticeProgressEngine
import io.trazo.core.PracticeSkill
import io.trazo.core.PracticeSubmission

/**
 * Additive adapter; deliberately does not modify StudyStore, ExamSession, navigation or UI.
 * Pass { studyStore.examActive } to enforce the existing exam lock at every mutation.
 * Integration owner must supply stable sourceAttemptId (NOT AI reviewId) for deduplication.
 */
class PracticeProgressStore(
    context: Context,
    private val examActive: () -> Boolean,
) {
    private val prefs = context.applicationContext.getSharedPreferences("trazo_practice_progress", Context.MODE_PRIVATE)
    // SharedPreferences is process-cached, but two Store instances previously held independent
    // snapshots and locks: a later writer could silently overwrite a newer committed attempt.
    // Use a process-wide lock and reload the latest persisted state before each operation.
    // This does not provide cross-process safety; the integration owner must keep one process.
    private val lock = io.trazo.core.study.StudyTransactions.monitor
    // A damaged primary may contain valid rows not present in the older backup.
    // Keep both original blobs intact until a dedicated recovery flow is approved.
    private var recoveryDegraded = false
    private var progress = synchronized(lock) { load() }

    fun snapshot(): PracticeProgress = synchronized(lock) { refresh(); progress }

    fun observeReviewedAttempt(sourceAttemptId: String, notes: List<AiNote>): PracticeProgress = synchronized(lock) {
        ensureNotExam()
        refresh()
        ensureWritable()
        var next = progress
        PracticePlanner.counts(notes).keys.forEach { skill ->
            next = PracticeProgressEngine.observeError(next, sourceAttemptId, skill)
        }
        save(next)
        progress
    }

    fun selectSkill(nowMillis: Long, seed: Long): PracticeSkill? = synchronized(lock) {
        refresh()
        PracticeProgressEngine.selectSkill(PracticeProgressEngine.sourceFrequencies(progress), progress, nowMillis, seed)
    }

    /**
     * Verify through the existing Trazo math engine before recording. A duplicate submission
     * ID is not regraded: the caller must retain its previous feedback across UI recreation.
     */
    fun submitExercise(exercise: PracticeExercise, rawAnswer: String, submissionId: String, nowMillis: Long): PracticeFeedback =
        submitVerified(exercise.skill, submissionId, nowMillis) { exercise.isCorrect(rawAnswer) }

    /** Restricted exact linear tasks include unique/none/all-real answers. */
    fun submitLinear(problem: PracticeLinearProblem, skill: PracticeSkill, rawAnswer: String,
                     submissionId: String, nowMillis: Long): PracticeFeedback =
        submitVerified(skill, submissionId, nowMillis) { problem.verifyAnswer(rawAnswer) }

    private fun submitVerified(skill: PracticeSkill, id: String, now: Long, verify: () -> Boolean): PracticeFeedback =
        synchronized(lock) {
            ensureNotExam()
            refresh()
            ensureWritable()
            if (id in progress.submissionIds) return@synchronized PracticeFeedback(null, true, progress.skills[skill]?.dueAtMillis)
            val correct = verify()
            val next = PracticeProgressEngine.submit(progress, PracticeSubmission(id, skill, correct, now))
            save(next)
            PracticeFeedback(correct, false, progress.skills[skill]?.dueAtMillis)
        }

    private fun ensureNotExam() { check(!examActive()) { "La práctica está bloqueada durante el examen" } }

    private fun ensureWritable() {
        check(!recoveryDegraded) {
            "Historial de práctica dañado: se muestran las filas recuperables, pero no se sobrescriben los originales"
        }
    }

    private fun refresh() { progress = load() }

    private fun save(next: PracticeProgress) {
        if (next == progress) return
        val encoded = PracticeProgressCodec.encode(next)
        val old = PracticeProgressCodec.encode(progress)
        check(prefs.edit().putString(KEY_BACKUP, old).putString(KEY_PRIMARY, encoded).commit()) {
            "No se pudo guardar el progreso de práctica"
        }
        progress = next
    }

    private fun load(): PracticeProgress {
        recoveryDegraded = false
        val primary = prefs.getString(KEY_PRIMARY, null)
        val backup = prefs.getString(KEY_BACKUP, null)
        if (primary == null && backup == null) return PracticeProgress()
        if (primary != null && primary.lineSequence().firstOrNull()?.startsWith("PRACTICE_V") == true &&
            primary.lineSequence().first() !in setOf("PRACTICE_V1", "PRACTICE_V2")) {
            error("Versión futura del progreso: no sobrescribir")
        }
        // Future schemas must not be silently overwritten. Corrupt individual rows are
        // recovered from a clean backup where possible, otherwise salvaged in memory.
        val a = primary?.let { runCatching { PracticeProgressCodec.decode(it) }.getOrNull() }
        val b = backup?.let { runCatching { PracticeProgressCodec.decode(it) }.getOrNull() }
        if (a != null && a.skippedRows == 0) return a.progress
        // A clean backup can be older than valid rows of a partially damaged primary.
        // A simple fallback to backup silently loses those newer rows (QA76).
        // Salvage valid rows from both snapshots for read-only use. Do not merge counters
        // by addition: a skill's primary CRC-valid row supersedes its older backup row.
        // In degraded mode all mutations fail closed, preserving both raw blobs for a
        // future user-approved repair rather than overwriting unrecoverable evidence.
        recoveryDegraded = true
        if (a != null && b != null) return PracticeProgress(
            skills = b.progress.skills + a.progress.skills,
            submissionIds = salvageKeys(b.progress.submissionIds, a.progress.submissionIds),
            sourceKeys = salvageKeys(b.progress.sourceKeys, a.progress.sourceKeys),
        )
        if (a != null) return a.progress
        if (b != null) return b.progress
        error("El historial de práctica no se puede leer; se conserva sin sobrescribir")
    }

    private fun <T> salvageKeys(older: Set<T>, newer: Set<T>): Set<T> {
        val keys = LinkedHashSet<T>()
        keys.addAll(older)
        keys.addAll(newer)
        while (keys.size > 1024) keys.remove(keys.first())
        return keys
    }

    private companion object {
        const val KEY_PRIMARY = "practice_progress_v2"
        const val KEY_BACKUP = "practice_progress_backup_v2"
    }
}

data class PracticeFeedback(val correct: Boolean?, val alreadyRecorded: Boolean, val nextDueAtMillis: Long?)
