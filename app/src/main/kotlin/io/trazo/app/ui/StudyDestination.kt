package io.trazo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import io.trazo.app.trazo
import io.trazo.core.AiNote
import io.trazo.core.PracticePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Load saved review history off the UI thread, including pages outside the editor's lazy window. */
@Composable
fun StudyDestination(notebookId: String, exam: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.trazo
    val notes by produceState<List<AiNote>?>(null, notebookId) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.repository.loadNotebook(notebookId) }.getOrNull()?.let { PracticePlanner.notesFromPages(it.pages) }.orEmpty()
        }
    }
    val loaded = notes
    if (loaded == null) Text("Preparando ejercicios…")
    else if (exam) StudySessionScreen(app.study, loaded, onBack)
    else PracticeScreen(app.study, loaded, onBack)
}
