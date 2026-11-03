@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.trazo.app.data.StudyMode
import io.trazo.app.data.StudyStore
import io.trazo.core.AiNote
import io.trazo.core.PracticePlanner

/** Practice uses deterministic exercises selected from supported, non-disputed AI review history. */
@Composable
fun PracticeScreen(store: StudyStore, errorNotes: List<AiNote>, onBack: () -> Unit) {
    val state by store.state.collectAsState()
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    val supported = PracticePlanner.counts(errorNotes).isNotEmpty()
    LaunchedEffect(supported, state.mode, state.exercises.isEmpty(), state.examActive) {
        if (supported && !state.examActive && (state.mode != StudyMode.PRACTICE || state.exercises.isEmpty())) {
            runCatching { store.startPractice(errorNotes, System.nanoTime()) }.onFailure { failure = it.message }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Práctica") }, navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                state.examActive -> Text("Hay un examen activo. Termínalo antes de iniciar una práctica.", color = MaterialTheme.colorScheme.error)
                !supported -> Text("Todavía no hay errores de revisión compatibles con ejercicios verificables. Revisa una resolución y vuelve aquí.")
                state.currentExercise == null -> Text("No hay un ejercicio disponible.")
                else -> {
                    val exercise = state.currentExercise!!
                    key(exercise.id) {
                        var answer by rememberSaveable(exercise.id) { mutableStateOf(state.practiceAnswer) }
                        Text(exercise.skill.label, style = MaterialTheme.typography.titleMedium)
                        Text("Este tema apareció en ${exercise.sourceErrorCount} marca(s) de revisión.", style = MaterialTheme.typography.bodySmall)
                        Text(exercise.prompt, style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            label = { Text("Tu respuesta") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = state.practiceCorrect != true,
                        )
                        Button(onClick = { runCatching { store.submitPracticeAnswer(answer) }.onSuccess { failure = null }.onFailure { failure = it.message } }, enabled = state.practiceCorrect != true && answer.isNotBlank()) {
                            Text("Comprobar")
                        }
                        when (state.practiceCorrect) {
                            true -> {
                                Text("Correcto. ${exercise.explanation}", color = MaterialTheme.colorScheme.primary)
                                Button(onClick = { runCatching { store.startPractice(errorNotes, System.nanoTime()) }.onSuccess { failure = null }.onFailure { failure = it.message } }) { Text("Otro ejercicio") }
                            }
                            false -> Text("Aún no. Revisa los signos y vuelve a intentarlo.", color = MaterialTheme.colorScheme.error)
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}
