package io.trazo.core.study

import kotlin.math.abs
import kotlin.math.min

/** Aids inside Trazo, not a lock on the Android operating system. */
enum class ExamHelp {
    AI_REVIEW, AI_DISCUSSION, AI_QUEUE_RETRY, AI_RESULT_CALLBACK, ON_DEVICE_MODEL,
    CALCULATOR, MATH_NOTES, SOLVE_SELECTION, SOLVE_COMPLEX, SHORTCUT,
}

class ExamHelpBlocked(val feature: ExamHelp) : IllegalStateException(
    "La ayuda ${feature.name} está bloqueada durante el examen."
)

/**
 * The same lock coordinates starting/ending an exam with accepting late callbacks.
 * ISSUE before starting a remote job; CHECK on every progress/mark/result callback.
 * A permit from a previous examination epoch NEVER revives after finishing the exam.
 * No HTTP or heavy work should execute inside [accept] or [transition].
 */
/** One process-wide monitor serializes persisted study mutations, including practice progress. */
object StudyTransactions { val monitor = Any() }

class ExamAccessGate(private val examActive: () -> Boolean) {
    data class Permit internal constructor(val generation: Long, val feature: ExamHelp)
    private val mutex = StudyTransactions.monitor
    private var generation: Long = 0L

    @Suppress("UNUSED_PARAMETER")
    fun isAllowed(feature: ExamHelp): Boolean = synchronized(mutex) { !examActive() }

    fun requireAllowed(feature: ExamHelp) {
        synchronized(mutex) { if (examActive()) throw ExamHelpBlocked(feature) }
    }

    /** Null rather than throwing lets callers exit quietly, e.g. a connectivity retry. */
    fun issue(feature: ExamHelp): Permit? = synchronized(mutex) {
        if (examActive()) null else Permit(generation, feature)
    }

    /**
     * Keeps the authorization check and the short side effect in one critical section.
     * This prevents startExam from interleaving between a check and a UI/ink mutation.
     */
    fun accept(permit: Permit?, effect: () -> Unit): Boolean = synchronized(mutex) {
        if (permit == null || permit.generation != generation || examActive()) return@synchronized false
        effect()
        true
    }

    /**
     * Persistent session transition and permit invalidation must be one atomic action.
     * Invalidate even if persistence throws; stale asynchronous results must not revive.
     */
    fun <T> transition(change: () -> T): T = synchronized(mutex) {
        generation += 1L
        change()
    }
}

/** Two separate time sources; elapsedRealtime is monotonic until a device reboot. */
data class ExamClockReading(val wallMillis: Long, val elapsedMillis: Long)

/** Serialize these primitive fields WITH the examination, not into a separate delayed write. */
data class ExamTimeCheckpoint(
    val startedWallMillis: Long,
    val lastWallMillis: Long,
    val lastElapsedMillis: Long,
    val remainingMillis: Long?,
    val durationMillis: Long?,
    val clockAnomalies: Int = 0,
    val backwardsElapsedObservations: Int = 0,
) {
    init {
        require(durationMillis == null || durationMillis > 0) { "Duration must be positive" }
        require(remainingMillis == null || remainingMillis >= 0) { "Remaining must be nonnegative" }
        require((durationMillis == null) == (remainingMillis == null)) { "Duration/remaining mismatch" }
        require(durationMillis == null || remainingMillis!! <= durationMillis) { "Remaining exceeds duration" }
        require(clockAnomalies >= 0 && backwardsElapsedObservations >= 0)
    }
}

data class ExamTimeAssessment(
    val checkpoint: ExamTimeCheckpoint,
    val remainingMillis: Long?,
    val expired: Boolean,
    val clockChanged: Boolean,
    val monotonicReset: Boolean,
)

/**
 * Rechecking never increases the saved remaining budget. While the same Android boot lasts,
 * elapsedRealtime gives accurate progress despite changes to the wall clock. Across reboot,
 * the persisted wall timestamp is the best available fallback. This is NOT anti-cheat proof:
 * changing the system clock across a reboot can conceal time spent powered off.
 */
object ExamTimePolicy {
    const val CLOCK_SKEW_TOLERANCE_MILLIS: Long = 30_000L

    fun begin(now: ExamClockReading, durationMillis: Long?): ExamTimeCheckpoint {
        require(durationMillis == null || durationMillis > 0L)
        require(now.elapsedMillis >= 0L)
        return ExamTimeCheckpoint(
            startedWallMillis = now.wallMillis,
            lastWallMillis = now.wallMillis,
            lastElapsedMillis = now.elapsedMillis,
            remainingMillis = durationMillis,
            durationMillis = durationMillis,
        )
    }

    fun assess(old: ExamTimeCheckpoint, now: ExamClockReading): ExamTimeAssessment {
        require(now.elapsedMillis >= 0L)
        val reset = now.elapsedMillis < old.lastElapsedMillis
        val monotonicDelta = if (reset) 0L else safePositiveDifference(now.elapsedMillis, old.lastElapsedMillis)
        val signedWallDelta = safeSignedDifference(now.wallMillis, old.lastWallMillis)
        val clockChanged = reset || safeAbsDifference(signedWallDelta, monotonicDelta) > CLOCK_SKEW_TOLERANCE_MILLIS
        val wallFromStart = safePositiveDifference(now.wallMillis, old.startedWallMillis)
        val remaining = old.durationMillis?.let { duration ->
            val fromWall = safeDecrement(duration, wallFromStart)
            val fromMonotonic = safeDecrement(old.remainingMillis!!, monotonicDelta)
            min(old.remainingMillis, min(fromMonotonic, fromWall))
        }
        val next = old.copy(
            lastWallMillis = now.wallMillis,
            lastElapsedMillis = now.elapsedMillis,
            remainingMillis = remaining,
            clockAnomalies = if (clockChanged) saturatingIncrement(old.clockAnomalies) else old.clockAnomalies,
            backwardsElapsedObservations = if (reset) saturatingIncrement(old.backwardsElapsedObservations) else old.backwardsElapsedObservations,
        )
        return ExamTimeAssessment(next, remaining, remaining == 0L, clockChanged, reset)
    }

    private fun safePositiveDifference(a: Long, b: Long): Long {
        if (a <= b) return 0L
        return try { Math.subtractExact(a, b) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    }

    private fun safeSignedDifference(a: Long, b: Long): Long = try {
        Math.subtractExact(a, b)
    } catch (_: ArithmeticException) {
        if (a > b) Long.MAX_VALUE else Long.MIN_VALUE
    }

    private fun safeAbsDifference(a: Long, b: Long): Long = try {
        Math.subtractExact(a, b).let { if (it == Long.MIN_VALUE) Long.MAX_VALUE else abs(it) }
    } catch (_: ArithmeticException) { Long.MAX_VALUE }

    private fun safeDecrement(current: Long, delta: Long): Long =
        if (delta >= current) 0L else current - delta // both nonnegative; no overflow

    private fun saturatingIncrement(value: Int) = if (value == Int.MAX_VALUE) value else value + 1
}

/**
 * The independent active-exam marker is deliberately stronger than malformed or stale JSON.
 * Recovery is an explicit action with confirmation; never use an empty exam to auto-unlock.
 */
enum class ExamRecoveryStatus { OPEN, ACTIVE, LOCKED_RECOVERY }

data class ExamRecoveryInputs(
    val activeMarker: Boolean,
    val wasDecoded: Boolean,
    val parsedAsActiveExam: Boolean,
    val questionCount: Int,
    val answerSlots: Int,
    val hasValidTimer: Boolean,
)

object ExamRecoveryPolicy {
    fun determine(input: ExamRecoveryInputs): ExamRecoveryStatus {
        if (!input.activeMarker && !input.parsedAsActiveExam) return ExamRecoveryStatus.OPEN
        if (!input.activeMarker && input.parsedAsActiveExam) return ExamRecoveryStatus.LOCKED_RECOVERY
        if (!input.wasDecoded || !input.parsedAsActiveExam ||
            input.questionCount <= 0 || input.questionCount > 20 ||
            input.answerSlots != input.questionCount || !input.hasValidTimer
        ) return ExamRecoveryStatus.LOCKED_RECOVERY
        return ExamRecoveryStatus.ACTIVE
    }
}
