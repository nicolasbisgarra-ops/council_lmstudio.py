package io.trazo.app.data

import android.content.Context
import android.os.SystemClock
import io.trazo.core.study.ExamAccessGate
import io.trazo.core.study.ExamClockReading
import io.trazo.core.study.ExamHelp
import io.trazo.core.study.ExamRecoveryInputs
import io.trazo.core.study.ExamRecoveryPolicy
import io.trazo.core.study.ExamRecoveryStatus
import io.trazo.core.study.ExamTimeAssessment
import io.trazo.core.study.ExamTimeCheckpoint
import io.trazo.core.study.ExamTimePolicy
import io.trazo.core.AiNote
import io.trazo.core.PracticeExercise
import io.trazo.core.PracticePlanner
import io.trazo.core.PracticeSkill
import io.trazo.core.PracticeAnswerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class StudyMode { IDLE, PRACTICE, EXAM }
enum class StudyFeature { AI_REVIEW, CALCULATOR }
enum class ExamFinishReason { SUBMITTED, TIMEOUT }


data class StudyState(
    val mode: StudyMode = StudyMode.IDLE,
    val exercises: List<PracticeExercise> = emptyList(),
    val index: Int = 0,
    val practiceAnswer: String = "",
    val practiceAttempts: Int = 0,
    val practiceCorrect: Boolean? = null,
    val examAnswers: List<String?> = emptyList(),
    val examFinished: Boolean = false,
    val examScore: Int? = null,
    val examClock: ExamTimeCheckpoint? = null,
    val examRecoveryLocked: Boolean = false,
    val examFinishReason: ExamFinishReason? = null,
) {
    val examActive: Boolean get() = mode == StudyMode.EXAM && (!examFinished || examRecoveryLocked)
    val currentExercise: PracticeExercise? get() = exercises.getOrNull(index)
}

class ExamModeException(feature: StudyFeature) : IllegalStateException(
    when (feature) {
        StudyFeature.AI_REVIEW -> "El Modo IA está bloqueado durante el examen."
        StudyFeature.CALCULATOR -> "La calculadora está bloqueada durante el examen."
    },
)

/** Persistent practice/exam session. The lock is process-wide through TrazoApp.study. */
class StudyStore(
    context: Context,
    private val now: () -> ExamClockReading = {
        ExamClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    },
) {
    private val prefs = context.getSharedPreferences("trazo_study", Context.MODE_PRIVATE)
    private val lock = io.trazo.core.study.StudyTransactions.monitor
    private val _state = MutableStateFlow(read())
    val state: StateFlow<StudyState> = _state.asStateFlow()

    /** Shared by callbacks, queue drain, local models and shortcut handlers, not just UI. */
    val examGate = ExamAccessGate { _state.value.examActive }

    val practiceProgress by lazy { PracticeProgressStore(context) { _state.value.examActive } }

    init { refreshExam() }

    val examActive: Boolean get() { tryRefreshExam(); return _state.value.examActive }

    /** If an expiry cannot be committed, never unlock aids or crash a reader. */
    fun tryRefreshExam(): Boolean = runCatching { refreshExam() }.isSuccess

    fun isAllowed(feature: StudyFeature): Boolean {
        if (!tryRefreshExam()) return false
        return examGate.isAllowed(feature.toExamHelp())
    }

    fun requireAllowed(feature: StudyFeature) {
        if (!isAllowed(feature)) throw ExamModeException(feature)
    }

    private fun StudyFeature.toExamHelp(): ExamHelp = when (this) {
        StudyFeature.AI_REVIEW -> ExamHelp.AI_REVIEW
        StudyFeature.CALCULATOR -> ExamHelp.CALCULATOR
    }

    /** Use for every asynchronous AI job and every result/progress/ink callback. */
    fun issueAid(feature: ExamHelp): ExamAccessGate.Permit? {
        if (!tryRefreshExam()) return null
        return examGate.issue(feature)
    }

    fun acceptAid(permit: ExamAccessGate.Permit?, effect: () -> Unit): Boolean {
        if (!tryRefreshExam()) return false
        return examGate.accept(permit, effect)
    }

    /** UI-only preview: no disk write and no unbounded wall-time timer. */
    fun examTime(): ExamTimeAssessment? = _state.value.takeIf { it.examActive && !it.examRecoveryLocked }
        ?.examClock?.let { ExamTimePolicy.assess(it, now()) }

    /**
     * Must run before action methods and periodically from the countdown UI.
     * A timeout is graded and COMMITTED before assistance becomes available again.
     */
    fun refreshExam() {
        val initial = _state.value
        if (!initial.examActive || initial.examRecoveryLocked) return
        val checkpoint = initial.examClock ?: return
        val first = ExamTimePolicy.assess(checkpoint, now())
        if (!first.expired && !first.clockChanged) return
        examGate.transition {
            synchronized(lock) {
                val s = _state.value
                if (!s.examActive || s.examRecoveryLocked) return@synchronized
                val live = s.examClock ?: return@synchronized
                val assessment = ExamTimePolicy.assess(live, now())
                if (assessment.expired) {
                    val score = grade(s)
                    publish(s.copy(examFinished = true, examScore = score,
                        examFinishReason = ExamFinishReason.TIMEOUT, examClock = assessment.checkpoint))
                } else if (assessment.clockChanged) {
                    publish(s.copy(examClock = assessment.checkpoint))
                }
            }
        }
    }

    private fun grade(s: StudyState): Int = s.exercises.indices.count { i ->
        s.exercises[i].isCorrect(s.examAnswers.getOrNull(i).orEmpty())
    }

    fun startPractice(notes: List<AiNote>, seed: Long) = synchronized(lock) {
        check(!_state.value.examActive) { "No se puede iniciar práctica mientras hay un examen activo" }
        // The source key follows the student's ink, rather than the AI review revision.
        notes.filter { it.strokeIds.isNotEmpty() }.groupBy { it.strokeIds.sorted() }.forEach { (ids, marks) ->
            val source = java.security.MessageDigest.getInstance("SHA-256")
                .digest(ids.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
            practiceProgress.observeReviewedAttempt(source, marks)
        }
        val counts = PracticePlanner.counts(notes)
        val selected = practiceProgress.selectSkill(System.currentTimeMillis(), seed)
        val eligible = counts.filterKeys { it == selected }.ifEmpty { counts }
        val exercises = PracticePlanner.build(eligible, seed, 1).map { exercise ->
            if (exercise.skill != PracticeSkill.ONE_STEP_EQUATION) exercise else {
                val problem = io.trazo.core.PracticeLinearGenerator.generate(seed)
                val answer = when (val result = problem.solve()) {
                    is io.trazo.core.PracticeLinearSolution.Unique -> result.x.toString()
                    io.trazo.core.PracticeLinearSolution.None -> "sin solución"
                    io.trazo.core.PracticeLinearSolution.AllReal -> "todos los reales"
                }
                exercise.copy(prompt = problem.prompt(), correctAnswer = answer,
                    explanation = problem.explanation(), exactLinear = problem)
            }
        }
        publish(StudyState(mode = StudyMode.PRACTICE, exercises = exercises))
    }

    fun submitPracticeAnswer(answer: String): Boolean = synchronized(lock) {
        val s = _state.value
        check(s.mode == StudyMode.PRACTICE && s.currentExercise != null) { "No hay ejercicio de práctica activo" }
        if (s.practiceCorrect == true) return@synchronized true
        val exercise = s.currentExercise!!
        val correct = exercise.isCorrect(answer)
        // Persist deduplicated progress before publishing UI state. A failed session commit can
        // replay this same submission without incrementing the adaptive history twice.
        practiceProgress.submitExercise(exercise, answer,
            "${exercise.id}:${s.practiceAttempts}", System.currentTimeMillis())
        publish(s.copy(practiceAnswer = answer, practiceAttempts = s.practiceAttempts + 1, practiceCorrect = correct))
        correct
    }

    /** Commit the exam and its timer before the UI reports an active session. */
    fun startExam(notes: List<AiNote>, seed: Long, questionCount: Int = EXAM_QUESTION_COUNT,
        durationMinutes: Int? = null) = examGate.transition {
        synchronized(lock) {
            check(!_state.value.examActive) { "Ya hay un examen activo" }
            require(questionCount in 1..20) { "questionCount fuera de rango" }
            require(durationMinutes == null || durationMinutes in 1..240) { "Duración de 1 a 240 minutos" }
            val exercises = PracticePlanner.build(PracticePlanner.counts(notes), seed, questionCount)
            require(exercises.size == questionCount) { "El examen no tiene todas las preguntas" }
            val clock = ExamTimePolicy.begin(now(), durationMinutes?.times(60_000L))
            publish(StudyState(mode = StudyMode.EXAM, exercises = exercises,
                examAnswers = List(questionCount) { null }, examClock = clock))
        }
    }

    /** An active exam is never allowed to accept an answer after time ran out. */
    fun submitExamAnswer(answer: String) {
        refreshExam()
        synchronized(lock) {
            val s = _state.value
            check(s.examActive && !s.examRecoveryLocked && s.currentExercise != null) {
                "No hay pregunta de examen activa"
            }
            val assessment = ExamTimePolicy.assess(requireNotNull(s.examClock), now())
            check(!assessment.expired) { "Se agotó el tiempo del examen" }
            val answers = s.examAnswers.toMutableList()
            answers[s.index] = answer
            publish(s.copy(examAnswers = answers, index = (s.index + 1).coerceAtMost(s.exercises.size),
                examClock = assessment.checkpoint))
        }
    }

    /** Manual submit requires all questions (blank allowed); timeout submits unanswered as blank. */
    fun finishExam(): Int = examGate.transition {
        synchronized(lock) {
            val s = _state.value
            check(s.examActive && !s.examRecoveryLocked) { "No hay examen recuperable activo" }
            val assessment = ExamTimePolicy.assess(requireNotNull(s.examClock), now())
            check(s.index >= s.exercises.size || assessment.expired) {
                "Responde o deja en blanco todas las preguntas antes de entregar"
            }
            val score = grade(s)
            publish(s.copy(examFinished = true, examScore = score,
                examFinishReason = if (assessment.expired) ExamFinishReason.TIMEOUT else ExamFinishReason.SUBMITTED,
                examClock = assessment.checkpoint))
            score
        }
    }

    /** For corrupt data only, after a SEPARATE confirmation dialog; never erase page ink. */
    fun abandonDamagedExam(confirmed: Boolean) = examGate.transition {
        synchronized(lock) {
            require(confirmed) { "La recuperación requiere confirmación explícita" }
            check(_state.value.examRecoveryLocked) { "No hay examen corrupto que recuperar" }
            publish(StudyState())
        }
    }

    fun clearSession() = examGate.transition {
        synchronized(lock) {
            check(!_state.value.examActive) { "No se puede borrar una sesión de examen activa" }
            publish(StudyState())
        }
    }

    private fun publish(next: StudyState) {
        check(prefs.edit()
            .putString(KEY_STATE, encode(next).toString())
            .putBoolean(KEY_EXAM_ACTIVE, next.examActive)
            .commit()) { "No se pudo guardar la sesión de estudio" }
        _state.value = next
    }

    private fun read(): StudyState {
        val raw = prefs.getString(KEY_STATE, null)
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val restored = json?.let { runCatching { decode(it) }.getOrNull() }
        val marker = prefs.getBoolean(KEY_EXAM_ACTIVE, false)
        val active = restored?.examActive == true
        // v1 exams were untimed. Migrate them conservatively to an untimed checkpoint.
        val legacy = if (active && json?.optInt("schema", 1) == 1 && restored?.examClock == null)
            ExamTimePolicy.begin(now(), null) else null
        val complete = restored?.copy(examClock = restored.examClock ?: legacy)
        val recovery = ExamRecoveryPolicy.determine(ExamRecoveryInputs(
            activeMarker = marker, wasDecoded = complete != null, parsedAsActiveExam = active,
            questionCount = complete?.exercises?.size ?: 0, answerSlots = complete?.examAnswers?.size ?: 0,
            hasValidTimer = complete?.examClock != null,
        ))
        return when (recovery) {
            ExamRecoveryStatus.ACTIVE -> requireNotNull(complete)
            ExamRecoveryStatus.OPEN -> restored ?: StudyState()
            ExamRecoveryStatus.LOCKED_RECOVERY -> StudyState(mode = StudyMode.EXAM,
                examRecoveryLocked = true)
        }
    }

    private fun encode(s: StudyState) = JSONObject().apply {
        put("schema", 2)
        put("mode", s.mode.name); put("index", s.index); put("practiceAnswer", s.practiceAnswer)
        put("practiceAttempts", s.practiceAttempts); s.practiceCorrect?.let { put("practiceCorrect", it) }
        put("examFinished", s.examFinished); s.examScore?.let { put("examScore", it) }
        s.examFinishReason?.let { put("examFinishReason", it.name) }
        s.examClock?.let { c -> put("examClock", JSONObject().apply {
            put("startedWall", c.startedWallMillis); put("lastWall", c.lastWallMillis)
            put("lastElapsed", c.lastElapsedMillis); put("duration", c.durationMillis ?: JSONObject.NULL)
            put("remaining", c.remainingMillis ?: JSONObject.NULL)
            put("clockAnomalies", c.clockAnomalies); put("reboots", c.backwardsElapsedObservations)
        }) }
        put("exercises", JSONArray(s.exercises.map(::encodeExercise)))
        put("examAnswers", JSONArray().apply { s.examAnswers.forEach { put(it) } })
    }

    private fun decode(o: JSONObject): StudyState {
        val exercises = o.optJSONArray("exercises")?.let { a -> (0 until a.length()).map { decodeExercise(a.getJSONObject(it)) } }.orEmpty()
        val answers = o.optJSONArray("examAnswers")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getString(it) } }.orEmpty()
        val mode = runCatching { StudyMode.valueOf(o.optString("mode", "IDLE")) }.getOrDefault(StudyMode.IDLE)
        val clock = o.optJSONObject("examClock")?.let { c -> ExamTimeCheckpoint(
            startedWallMillis = c.getLong("startedWall"), lastWallMillis = c.getLong("lastWall"),
            lastElapsedMillis = c.getLong("lastElapsed"),
            durationMillis = if (c.isNull("duration")) null else c.getLong("duration"),
            remainingMillis = if (c.isNull("remaining")) null else c.getLong("remaining"),
            clockAnomalies = c.optInt("clockAnomalies").coerceAtLeast(0),
            backwardsElapsedObservations = c.optInt("reboots").coerceAtLeast(0),
        ) }
        // A malformed v2 clock must not turn into an unlimited v1 exam.
        if (o.optInt("schema", 1) >= 2 && mode == StudyMode.EXAM &&
            !o.optBoolean("examFinished") && clock == null) error("Corrupted v2 exam timer")
        val index = o.optInt("index").coerceIn(0, exercises.size)
        return StudyState(
            mode = mode, exercises = exercises, index = index,
            practiceAnswer = o.optString("practiceAnswer"), practiceAttempts = o.optInt("practiceAttempts").coerceAtLeast(0),
            practiceCorrect = if (o.has("practiceCorrect")) o.optBoolean("practiceCorrect") else null,
            examAnswers = answers, examFinished = o.optBoolean("examFinished"),
            examScore = if (o.has("examScore")) o.optInt("examScore") else null,
            examFinishReason = o.optString("examFinishReason").takeIf { it.isNotEmpty() }
                ?.let { runCatching { ExamFinishReason.valueOf(it) }.getOrNull() },
            examClock = clock,
        )
    }

    private fun encodeExercise(e: PracticeExercise) = JSONObject().apply {
        put("id", e.id); put("skill", e.skill.name); put("prompt", e.prompt); put("answer", e.correctAnswer)
        put("explanation", e.explanation); put("sourceErrorCount", e.sourceErrorCount); put("answerKind", e.answerKind.name)
        e.exactLinear?.let { p -> put("exactLinear", JSONArray(listOf(p.a, p.b, p.c, p.d).map { it.toString() })) }
    }

    private fun decodeExercise(o: JSONObject) = PracticeExercise(
        id = o.getString("id"), skill = PracticeSkill.valueOf(o.getString("skill")), prompt = o.getString("prompt"),
        correctAnswer = o.getString("answer"), explanation = o.getString("explanation"),
        sourceErrorCount = o.optInt("sourceErrorCount").coerceAtLeast(0),
        answerKind = PracticeAnswerKind.valueOf(o.getString("answerKind")),
        exactLinear = o.optJSONArray("exactLinear")?.let { a ->
            require(a.length() == 4)
            val c = (0..3).map { io.trazo.core.PracticeRational.parse(a.getString(it)) }
            io.trazo.core.PracticeLinearProblem(c[0], c[1], c[2], c[3])
        },
    )

    private companion object {
        const val KEY_STATE = "session"
        const val KEY_EXAM_ACTIVE = "exam_active"
        const val EXAM_QUESTION_COUNT = 5
    }
}
