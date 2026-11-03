@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.trazo.app.data.ExamFinishReason
import io.trazo.app.data.StudyStore
import io.trazo.core.AiNote
import io.trazo.core.PracticePlanner
import kotlinx.coroutines.delay

/** No anti-cheat/kiosk guarantee: only in-app assistance is blocked and the exam survives navigation. */
@Composable
fun StudySessionScreen(store: StudyStore, errorNotes: List<AiNote>, onBack: () -> Unit) {
    val state by store.state.collectAsState()
    val supported = PracticePlanner.counts(errorNotes).isNotEmpty()
    var duration by rememberSaveable { mutableIntStateOf(0) }
    var clockPulse by remember { mutableIntStateOf(0) }
    var showRecoveryConfirmation by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    fun attempt(action: () -> Unit) {
        actionError = runCatching { action() }.exceptionOrNull()?.message
    }

    LaunchedEffect(state.examActive, state.examRecoveryLocked) {
        if (state.examActive && !state.examRecoveryLocked) {
            while (true) {
                delay(1_000L)
                clockPulse++
                if (!store.tryRefreshExam()) actionError = "No se pudo guardar el cierre por tiempo. El examen sigue bloqueado."
            }
        }
    }

    if (showRecoveryConfirmation) {
        AlertDialog(
            onDismissRequest = { showRecoveryConfirmation = false },
            title = { Text("Recuperar la aplicación") },
            text = { Text("El registro del examen está dañado. Se descartará SOLO la sesión de examen. Las hojas y la tinta no se borran. La finalización quedará sin puntaje.") },
            confirmButton = { TextButton(onClick = {
                attempt { store.abandonDamagedExam(confirmed = true) }
                if (actionError == null) showRecoveryConfirmation = false
            }) { Text("Descartar sesión dañada") } },
            dismissButton = { TextButton(onClick = { showRecoveryConfirmation = false }) { Text("Mantener bloqueo") } },
        )
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Modo examen") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Volver") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                state.examRecoveryLocked -> {
                    Text("Examen bloqueado: datos dañados", style = MaterialTheme.typography.headlineSmall)
                    Text("Por seguridad, la IA y la calculadora continúan deshabilitadas. Podés volver a tus hojas sin perder tinta.")
                    Button(onClick = { showRecoveryConfirmation = true }) { Text("Recuperar sesión") }
                }
                state.examFinished -> {
                    Text(if (state.examFinishReason == ExamFinishReason.TIMEOUT) "Tiempo agotado" else "Resultado",
                        style = MaterialTheme.typography.headlineSmall)
                    Text("Puntaje: ${state.examScore ?: 0} de ${state.exercises.size}")
                    state.exercises.forEachIndexed { i, exercise ->
                        val correct = exercise.isCorrect(state.examAnswers.getOrNull(i).orEmpty())
                        Text("${i + 1}. ${if (correct) "Correcta" else "Revisar"}: ${exercise.prompt}\n" +
                            "Tu respuesta: ${state.examAnswers.getOrNull(i).orEmpty().ifBlank { "(sin respuesta)" }}\n" +
                            "Respuesta: ${exercise.correctAnswer}. ${exercise.explanation}")
                    }
                    Button(onClick = { attempt { store.clearSession() } }) { Text("Cerrar resultado") }
                }
                state.examActive -> {
                    // clockPulse forces the preview to update once a second without writing to disk.
                    @Suppress("UNUSED_VARIABLE") val currentPulse = clockPulse
                    val remaining = store.examTime()?.remainingMillis
                    if (remaining != null) {
                        val seconds = (remaining + 999L) / 1_000L
                        Text("Tiempo restante: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.titleMedium)
                    } else Text("Examen sin límite de tiempo", style = MaterialTheme.typography.titleMedium)
                    if ((state.examClock?.clockAnomalies ?: 0) > 0) {
                        Text("Se detectó un cambio del reloj. El tiempo de examen no se amplió.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    val exercise = state.currentExercise
                    if (exercise == null) {
                        Text("Respondiste todas las preguntas. Entrega el examen para ver el resultado.")
                        Button(onClick = { attempt { store.finishExam() } }) { Text("Entregar examen") }
                    } else {
                        Text("Pregunta ${state.index + 1} de ${state.exercises.size}", style = MaterialTheme.typography.titleMedium)
                        Text(exercise.prompt, style = MaterialTheme.typography.headlineSmall)
                        var answer by rememberSaveable(exercise.id) { mutableStateOf(state.examAnswers.getOrNull(state.index).orEmpty()) }
                        OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("Tu respuesta") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { attempt { store.submitExamAnswer(answer) } }) {
                            Text(if (state.index == state.exercises.lastIndex) "Guardar última respuesta" else "Guardar y continuar")
                        }
                        TextButton(onClick = { attempt { store.submitExamAnswer("") } }) { Text("Dejar en blanco") }
                    }
                    Text("Las ayudas dentro de Trazo están bloqueadas hasta entregar o agotar el tiempo. Salir de esta pantalla NO termina el examen.",
                        style = MaterialTheme.typography.bodySmall)
                }
                !supported -> Text("Para preparar el examen hacen falta marcas compatibles de una revisión anterior.")
                else -> {
                    Text("Examen de 5 preguntas", style = MaterialTheme.typography.headlineSmall)
                    Text("Elegí la duración. Las ayudas IA y calculadora de Trazo quedan bloqueadas. No bloquea otras aplicaciones Android.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in listOf(0, 30, 60)) {
                            FilterChip(selected = duration == option, onClick = { duration = option },
                                label = { Text(if (option == 0) "Sin límite" else "$option min") })
                        }
                    }
                    Button(onClick = { attempt {
                        store.startExam(errorNotes, System.nanoTime(), durationMinutes = duration.takeIf { it > 0 })
                    } }) { Text("Empezar examen") }
                }
            }
        }
    }
}
