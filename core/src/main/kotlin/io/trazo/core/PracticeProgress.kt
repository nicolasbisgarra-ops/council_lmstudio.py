package io.trazo.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32

/**
 * Independent practice history. No Android, AI, network, notebook or exam dependencies.
 * The caller MUST obtain [PracticeSubmission.correct] from a deterministic math verifier,
 * never from a language-model verdict.
 */
data class PracticeSkillState(
    val attempts: Int = 0,
    val correct: Int = 0,
    val streak: Int = 0,
    val box: Int = 0,
    val dueAtMillis: Long = 0L,
    val lastAtMillis: Long = 0L,
)

data class PracticeProgress(
    val skills: Map<PracticeSkill, PracticeSkillState> = emptyMap(),
    /** Idempotency keys for submissions, scoped to a stable session/exercise/attempt. */
    val submissionIds: Set<String> = emptySet(),
    /** One source attempt per skill; multiple AI marks/reviews cannot inflate the same attempt. */
    val sourceKeys: Set<PracticeSourceKey> = emptySet(),
)

data class PracticeSourceKey(val attemptId: String, val skill: PracticeSkill) {
    init { require(attemptId.isNotBlank() && attemptId.length <= 256) }
}

data class PracticeSubmission(
    val id: String,
    val skill: PracticeSkill,
    val correct: Boolean,
    val atMillis: Long,
) {
    init { require(id.isNotBlank() && id.length <= 256); require(atMillis >= 0) }
}

object PracticeProgressEngine {
    // Deterministic, monotonic spacing after correct answers; incorrect retry after 5 minutes.
    private val intervals = longArrayOf(0L, 10 * 60_000L, 60 * 60_000L,
        24 * 60 * 60_000L, 3 * 24 * 60 * 60_000L, 7 * 24 * 60 * 60_000L)
    private const val INCORRECT_DELAY = 5 * 60_000L
    private const val RECENT_KEY_LIMIT = 1024

    /** Count each original student attempt once per skill, regardless of review revisions. */
    fun observeError(state: PracticeProgress, attemptId: String, skill: PracticeSkill): PracticeProgress {
        val key = PracticeSourceKey(attemptId, skill)
        if (key in state.sourceKeys) return state
        return state.copy(sourceKeys = retainRecent(state.sourceKeys, key))
    }

    fun sourceFrequencies(state: PracticeProgress): Map<PracticeSkill, Int> =
        state.sourceKeys.groupingBy { it.skill }.eachCount()

    /** Replaying the same submission ID does not alter attempts or next due date. */
    fun submit(state: PracticeProgress, event: PracticeSubmission): PracticeProgress {
        if (event.id in state.submissionIds) return state
        val old = state.skills[event.skill] ?: PracticeSkillState()
        // Device clock may move backward. Scheduling never moves back relative to the last attempt.
        val effectiveNow = maxOf(event.atMillis, old.lastAtMillis)
        val box = if (event.correct) (old.box + 1).coerceAtMost(intervals.lastIndex) else 0
        val due = saturatingAdd(effectiveNow, if (event.correct) intervals[box] else INCORRECT_DELAY)
        val next = PracticeSkillState(
            attempts = increment(old.attempts),
            correct = if (event.correct) increment(old.correct) else old.correct,
            streak = if (event.correct) increment(old.streak) else 0,
            box = box,
            dueAtMillis = due,
            lastAtMillis = effectiveNow,
        )
        return state.copy(skills = state.skills + (event.skill to next), submissionIds = retainRecent(state.submissionIds, event.id))
    }

    /**
     * Deterministic adaptive choice. Only skills with a supported error count are eligible.
     * Due skills are preferred, then frequency, then low streak. A not-yet-due skill is
     * still selectable when it is the only eligible skill (no empty-screen dead end).
     */
    fun selectSkill(
        frequencies: Map<PracticeSkill, Int>,
        state: PracticeProgress,
        nowMillis: Long,
        seed: Long,
    ): PracticeSkill? {
        require(nowMillis >= 0)
        val candidates = PracticeSkill.entries.filter { (frequencies[it] ?: 0) > 0 }
        if (candidates.isEmpty()) return null
        val due = candidates.filter { (state.skills[it]?.dueAtMillis ?: 0L) <= nowMillis }
        val pool = if (due.isNotEmpty()) due else candidates
        val ranked = pool.sortedWith(compareByDescending<PracticeSkill> { frequencies[it] ?: 0 }
            .thenBy { state.skills[it]?.streak ?: 0 }
            .thenBy { it.name })
        val highestFrequency = frequencies[ranked.first()] ?: 0
        val tied = ranked.filter { (frequencies[it] ?: 0) == highestFrequency &&
            (state.skills[it]?.streak ?: 0) == (state.skills[ranked.first()]?.streak ?: 0) }
        return tied[Math.floorMod(seed, tied.size.toLong()).toInt()]
    }

    /** Bounded replay/dedup window; older keys expire but aggregate skill stats remain. */
    private fun <T> retainRecent(existing: Set<T>, added: T): Set<T> {
        val result = LinkedHashSet(existing)
        result.add(added)
        while (result.size > RECENT_KEY_LIMIT) result.remove(result.first())
        return result
    }
    private fun increment(n: Int): Int = if (n == Int.MAX_VALUE) n else n + 1
    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}

/**
 * Versioned, bounded and per-row CRC-protected format. No Android/JSON dependency.
 * A damaged row is discarded without discarding independent valid rows. Unsupported
 * future versions fail explicitly rather than being overwritten with an empty history.
 * V1 is an explicitly documented legacy *prototype* format (not the existing StudyStore JSON).
 */
object PracticeProgressCodec {
    private const val MAX_TEXT_BYTES = 1024 * 1024
    private const val MAX_KEYS = 1024
    private const val MAX_ROWS = 5000
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class ReadResult(val progress: PracticeProgress, val skippedRows: Int, val migratedV1: Boolean)

    fun encode(state: PracticeProgress): String {
        require(state.submissionIds.size <= MAX_KEYS && state.sourceKeys.size <= MAX_KEYS)
        val lines = mutableListOf("PRACTICE_V2")
        state.skills.toSortedMap(compareBy { it.name }).forEach { (skill, p) ->
            lines += row("S", skill.name, p.attempts.toString(), p.correct.toString(),
                p.streak.toString(), p.box.toString(), p.dueAtMillis.toString(), p.lastAtMillis.toString())
        }
        state.submissionIds.forEach { lines += row("A", token(it)) }
        state.sourceKeys.forEach { lines += row("E", token(it.attemptId), it.skill.name) }
        lines += row("END", (lines.size - 1).toString())
        return lines.joinToString("\n", postfix = "\n").also { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) }
    }

    fun decode(text: String): ReadResult {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) { "practice history too large" }
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(MAX_ROWS + 2).toList()
        require(lines.isNotEmpty()) { "missing practice history header" }
        val v1 = when (lines.first()) {
            "PRACTICE_V2" -> false
            "PRACTICE_V1" -> true
            else -> throw IllegalArgumentException("unsupported practice history version")
        }
        var skipped = if (lines.size > MAX_ROWS + 1) 1 else 0
        val skills = mutableMapOf<PracticeSkill, PracticeSkillState>()
        val attempts = linkedSetOf<String>()
        val sources = linkedSetOf<PracticeSourceKey>()
        var endSeen = false
        var rowCount = 0
        for (line in lines.drop(1).take(MAX_ROWS)) {
            try {
                val parts = line.split('\t')
                require(!endSeen) { "unexpected row after footer" }
                if (!v1) {
                    require(parts.size >= 3)
                    val payload = parts.dropLast(1).joinToString("\t")
                    require(crc(payload) == parts.last()) { "CRC mismatch" }
                }
                when (parts[0]) {
                    "END" -> {
                        require(!v1 && parts.size == 3 && parts[1].toInt() == rowCount)
                        endSeen = true
                    }
                    "S" -> {
                        require(parts.size == if (v1) 6 else 9)
                        val skill = PracticeSkill.valueOf(parts[1])
                        val n = parts[2].toInt(); val c = parts[3].toInt()
                        val streak = parts[4].toInt()
                        val box = if (v1) 0 else parts[5].toInt()
                        val due = if (v1) parts[5].toLong() else parts[6].toLong()
                        val last = if (v1) 0L else parts[7].toLong()
                        require(n >= 0 && c in 0..n && streak in 0..n && box in 0..5 && due >= 0 && last >= 0)
                        require(skill !in skills)
                        skills[skill] = PracticeSkillState(n, c, streak, box, due, last)
                    }
                    "A" -> {
                        require(!v1 && parts.size == 3 && attempts.size < MAX_KEYS)
                        val id = untoken(parts[1]); require(id.isNotBlank() && id.length <= 256)
                        require(attempts.add(id))
                    }
                    "E" -> {
                        require(!v1 && parts.size == 4 && sources.size < MAX_KEYS)
                        require(sources.add(PracticeSourceKey(untoken(parts[1]), PracticeSkill.valueOf(parts[2]))))
                    }
                    else -> error("unknown row")
                }
                if (parts[0] != "END") rowCount++
            } catch (_: RuntimeException) { skipped++; rowCount++ }
        }
        if (!v1 && !endSeen) skipped++ // truncated at a valid row boundary
        return ReadResult(PracticeProgress(skills, attempts, sources), skipped, v1)
    }

    private fun token(s: String): String = encoder.encodeToString(s.toByteArray(StandardCharsets.UTF_8))
    private fun untoken(s: String): String = String(decoder.decode(s), StandardCharsets.UTF_8)
    private fun row(vararg parts: String): String {
        val payload = parts.joinToString("\t")
        return "$payload\t${crc(payload)}"
    }
    private fun crc(s: String): String = CRC32().apply { update(s.toByteArray(StandardCharsets.UTF_8)) }
        .value.toString(16).padStart(8, '0')
}
