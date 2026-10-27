package io.trazo.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.trazo.app.trazo
import io.trazo.core.GestureExamples
import io.trazo.core.History
import io.trazo.core.IdGenerator
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.InkPattern
import io.trazo.core.InkStroke
import io.trazo.core.OutputSizing
import io.trazo.core.PageState
import io.trazo.core.PatternUpgrades
import io.trazo.core.Stretch

/** Lienzo pequeño editable (gesto, salida o prueba). */
class Pad(strokes: List<InkStroke> = emptyList(), patterns: () -> List<InkPattern> = { emptyList() }, recognize: Boolean = false) {
    val controller = InkController(PageState(strokes), History(), patterns).apply {
        recognitionEnabled = recognize
        width = 3f
    }
    val strokes: List<InkStroke> get() = controller.page.strokes

    fun clear() {
        val all = strokes.toList()
        if (all.isNotEmpty()) controller.history.perform(controller.page, io.trazo.core.StrokeEdit(all, emptyList(), io.trazo.core.StrokeEdit.Kind.CLEAR))
        controller.attach(controller.page, controller.history)
    }
}

/**
 * Editor izquierda/derecha: "Gesto a reconocer" (uno o varios ejemplos) y "Cómo quiero que quede".
 * Al guardar se crea un [InkPattern] nuevo; las notas existentes no cambian (tienen copias).
 */
class PatternEditorViewModel(
    app: Application,
    private val patternId: String?,
    seedGesture: List<InkStroke>? = null,
) : AndroidViewModel(app) {
    private val repo = app.trazo.repository
    private val original = patternId?.let { id -> repo.patterns.value.firstOrNull { it.id == id } }

    var name by mutableStateOf(original?.name ?: "")
    var sizing by mutableStateOf(original?.sizing ?: OutputSizing.MATCH_HEIGHT)
    var outputScale by mutableFloatStateOf(original?.outputScale ?: 1f)
    var minScore by mutableFloatStateOf(original?.minScore ?: InkPattern.DEFAULT_MIN_SCORE)
    var customHold by mutableStateOf(original?.holdMillis != null)
    var holdMillis by mutableFloatStateOf((original?.holdMillis ?: 1000L).toFloat())
    var elastic by mutableStateOf(original?.stretch != null)
    /** Sección de la biblioteca; null = "Mis patrones". */
    var category by mutableStateOf(original?.category)
    var revision by mutableIntStateOf(0)
        private set
    var testResult by mutableStateOf<String?>(null)

    val gestureExamples = mutableStateListOf<Pad>().apply {
        // Un gesto enseñado desde la hoja llega como primer ejemplo.
        val initial = original?.gestureExamples ?: listOf(seedGesture?.let { GestureExamples.normalize(it) } ?: emptyList())
        initial.forEach { add(Pad(it)) }
    }
    var currentExample by mutableIntStateOf(0)
    val output = Pad(original?.outputInk ?: emptyList())
    val testPad = Pad(patterns = { listOfNotNull(draft()) }, recognize = true)

    init {
        val bump: (InkEvent) -> Unit = { if (it == InkEvent.PageChanged) revision++ }
        gestureExamples.forEach { it.controller.addListener(bump) }
        output.controller.addListener(bump)
        testPad.controller.addListener { e ->
            testResult = when (e) {
                is InkEvent.Converted -> "Reconocido (${"%.0f".format(e.score * 100)} %)"
                is InkEvent.Ambiguous -> "Dudoso: no se convierte"
                is InkEvent.NotRecognized -> "No reconocido: se conserva la escritura"
                else -> testResult
            }
        }
    }

    val isNew get() = original == null
    val isBuiltIn get() = original?.builtIn == true

    fun addExample() {
        gestureExamples += Pad().also { pad -> pad.controller.addListener { if (it == InkEvent.PageChanged) revision++ } }
        currentExample = gestureExamples.lastIndex
    }

    fun removeCurrentExample() {
        if (gestureExamples.size <= 1) { gestureExamples[0].clear(); return }
        gestureExamples.removeAt(currentExample)
        currentExample = currentExample.coerceAtMost(gestureExamples.lastIndex)
        revision++
    }

    /** Patrón con el estado actual o null si falta dibujar el gesto o la salida. */
    fun draft(): InkPattern? {
        val examples = gestureExamples.map { it.strokes.toList() }.filter { it.isNotEmpty() }
        val out = output.strokes.toList()
        if (examples.isEmpty() || out.isEmpty()) return null
        return InkPattern(
            id = original?.id ?: "draft",
            name = name.ifBlank { PatternUpgrades.UNNAMED },
            gestureExamples = examples,
            outputInk = out,
            sizing = sizing,
            outputScale = outputScale,
            minScore = minScore,
            holdMillis = if (customHold) holdMillis.toLong() else null,
            enabled = original?.enabled ?: true,
            builtIn = original?.builtIn ?: false,
            category = category,
            stretch = stretchFor(out),
        )
    }

    /** Zona elástica que se guardaría con [out]: la del patrón si la salida no cambió, si no la detectada. */
    private fun stretchFor(out: List<InkStroke>): Stretch? {
        if (!elastic || out.isEmpty()) return null
        return original?.takeIf { PatternUpgrades.sameInk(out, it.outputInk) }?.stretch ?: Stretch.detectBar(out)
    }

    /** La barra elástica está activada pero la salida no tiene una barra a la derecha. */
    fun elasticMissing(): Boolean {
        revision
        return elastic && output.strokes.isNotEmpty() && stretchFor(output.strokes.toList()) == null
    }

    fun canSave(): Boolean { revision; return draft() != null }

    fun save(): Boolean {
        val d = draft() ?: return false
        repo.upsertPattern(if (original == null) d.copy(id = IdGenerator.RANDOM.next()) else d)
        return true
    }

    fun clearTest() { testPad.clear(); testResult = null }
}
