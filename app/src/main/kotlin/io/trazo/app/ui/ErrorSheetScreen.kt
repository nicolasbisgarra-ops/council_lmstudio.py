@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.trazo.app.trazo
import io.trazo.core.ErrorSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hoja de errores del cuaderno: fallas frecuentes por tipo y tema; tocar un ejemplo abre su hoja. */
@Composable
fun ErrorSheetScreen(notebookId: String, onBack: () -> Unit, onOpenPage: (Int) -> Unit, onPractice: () -> Unit) {
    val app = LocalContext.current.trazo
    val sheet by produceState<ErrorSheet.Sheet?>(null, notebookId) {
        value = withContext(Dispatchers.IO) {
            ErrorSheet.build(runCatching { app.repository.loadNotebook(notebookId) }.getOrNull()?.pages.orEmpty())
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Hoja de errores") }, navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } })
    }) { padding ->
        val s = sheet
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when {
                s == null -> Text("Juntando las marcas…")
                s.isEmpty -> Text("Todavía no hay errores marcados en este cuaderno. Revisá una hoja con el Modo IA y volvé acá.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("${s.total} errores marcados", style = MaterialTheme.typography.titleMedium)
                        if (s.skills.isNotEmpty()) {
                            Text(
                                s.skills.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key.label}: ${it.value}" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = onPractice, modifier = Modifier.padding(top = 8.dp)) { Text("Practicar estos errores") }
                        }
                    }
                    items(s.kinds, key = { it.kind }) { kind ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val confirmed = if (kind.confirmed > 0) " · ${kind.confirmed} comprobados" else ""
                                Text("${kind.label} — ${kind.count}$confirmed", style = MaterialTheme.typography.titleSmall)
                                kind.topics.take(8).forEach { topic ->
                                    val times = if (topic.count > 1) " ×${topic.count}" else ""
                                    Text("${topic.title}$times", style = MaterialTheme.typography.bodyMedium)
                                    topic.examples.forEach { ex ->
                                        val fix = if (ex.fix.isNotBlank()) " → ${ex.fix}" else ""
                                        Text(
                                            "Hoja ${ex.pageIndex + 1}$fix",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.fillMaxWidth().clickable { onOpenPage(ex.pageIndex) }
                                                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                                        )
                                    }
                                }
                                if (kind.topics.size > 8) Text("y ${kind.topics.size - 8} temas más", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
