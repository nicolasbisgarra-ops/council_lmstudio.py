package io.trazo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.trazo.app.TrazoApp
import io.trazo.app.data.TrazoSettings
import io.trazo.core.settings.SymbolColors

/**
 * Página «Colores de símbolos»: cada patrón con color fijo escribe siempre con él al reconocerse,
 * sin importar la pluma. Quitar el color («Predeterminado») vuelve a usar el color de la pluma.
 */
@Composable
internal fun SymbolColorsPage(
    s: TrazoSettings,
    update: ((TrazoSettings) -> TrazoSettings) -> Unit,
    pick: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as TrazoApp
    val patterns by app.repository.patterns.collectAsState()
    var adding by remember { mutableStateOf(false) }
    fun nameOf(id: String) = patterns.firstOrNull { it.id == id }?.name ?: id

    PrefCard("Colores de símbolos") {
        Text(
            "Cada símbolo de esta lista se escribe siempre con su color, sin importar el color de la pluma. " +
                "Los que no están en la lista usan el color de la pluma.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.symbolColors.isEmpty()) {
            Text("Ningún símbolo tiene color fijo.", style = MaterialTheme.typography.bodyMedium)
        }
        s.symbolColors.forEach { (id, color) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(color, selected = false, onClick = { pick(id) }, size = 32.dp, description = "Cambiar el color de ${nameOf(id)}")
                Spacer(Modifier.width(12.dp))
                Text(nameOf(id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { update { st -> st.copy(symbolColors = st.symbolColors - id) } }) { Text("Predeterminado") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("Agregar símbolo") }
            TextButton(onClick = { update { it.copy(symbolColors = SymbolColors.defaults) } }) { Text("Restaurar valores") }
        }
    }

    if (adding) {
        val available = patterns.filter { it.id !in s.symbolColors }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Agregar símbolo") },
            text = {
                if (available.isEmpty()) {
                    Text("Todos los patrones ya tienen color.")
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        available.forEach { p ->
                            TextButton(
                                onClick = {
                                    adding = false
                                    // Arranca con el color de la pluma; el selector se abre para elegir otro.
                                    update { st -> st.copy(symbolColors = st.symbolColors + (p.id to st.penColor)) }
                                    pick(p.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(p.name, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cerrar") } },
        )
    }
}
