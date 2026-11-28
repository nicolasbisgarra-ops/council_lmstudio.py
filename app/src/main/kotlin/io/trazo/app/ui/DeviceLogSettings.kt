package io.trazo.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.trazo.app.data.InkProbe
import io.trazo.app.trazo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Interruptor y acciones del registro de rendimiento (Ajustes › General). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceLogSettings(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val log = context.trazo.deviceLog
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { summary = withContext(Dispatchers.IO) { log.summary() } }

    SwitchRow(
        "Registro de rendimiento", enabled,
        { on ->
            onChange(on)
            InkProbe.enabled = on
            val window = (context as? android.app.Activity)?.window
            if (on && window != null) log.start(window) else if (!on) log.stop()
        },
        subtitle = "Mientras Trazo está abierto anota cada acción con su hora (toques, pantallas, botones del editor, cuadros lentos) y cada 5 s " +
            "los cuadros atrasados, el dibujo de la hoja, la memoria, la temperatura y la batería, para cruzarlos y optimizar Trazo. " +
            "Queda solo en este equipo; no guarda nada de lo escrito.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        summary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { log.exportFile(context.cacheDir) }.getOrNull() }
                        ?: return@launch
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Registro de rendimiento de Trazo")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir registro"))
                }
            }) { Text("Compartir registro") }
            TextButton(onClick = { version++ }) { Text("Actualizar") }
            TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { log.clear() }; version++ }
            }) { Text("Borrar") }
        }
    }
}
