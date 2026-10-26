@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.trazo.app.trazo
import io.trazo.core.Paper

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = LocalContext.current.trazo.settings
    val s by store.settings.collectAsState()
    val stylus = remember { hasStylusDevice() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(if (stylus) "Lápiz detectado en este dispositivo." else "No se detectó lápiz: podés habilitar el dedo para escribir.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text("Pausa para convertir: ${s.holdMillis} ms", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = s.holdMillis.toFloat(), valueRange = 500f..2000f, steps = 14,
                onValueChange = { v -> store.update { it.copy(holdMillis = (v / 100).toLong() * 100) } },
            )
            Text("Temblor tolerado: ${"%.0f".format(s.tremorRadiusDp)} dp", style = MaterialTheme.typography.titleSmall)
            Slider(value = s.tremorRadiusDp, valueRange = 2f..16f, onValueChange = { v -> store.update { it.copy(tremorRadiusDp = v) } })
            Text("Tamaño de la goma: ${"%.0f".format(s.eraserRadiusDp)} dp", style = MaterialTheme.typography.titleSmall)
            Slider(value = s.eraserRadiusDp, valueRange = 4f..40f, onValueChange = { v -> store.update { it.copy(eraserRadiusDp = v) } })
            SwitchRow("Gestos con pausa de lápiz", s.gesturesEnabled) { on -> store.update { it.copy(gesturesEnabled = on) } }
            SwitchRow("Escribir también con el dedo (el dedo deja de desplazar la hoja)", s.fingerDraws) { on -> store.update { it.copy(fingerDraws = on) } }
            Spacer(Modifier.height(16.dp))
            Text("Papel de los cuadernos nuevos (cada cuaderno puede cambiarlo):", style = MaterialTheme.typography.bodySmall)
            Row {
                Paper.entries.forEach { p -> FilterChip(selected = s.paper == p, onClick = { store.update { it.copy(paper = p) } }, label = { Text(paperName(p)) }) }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Todo funciona sin conexión. Los cuadernos se guardan como trazos editables en este dispositivo.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text("  $label")
    }
}
