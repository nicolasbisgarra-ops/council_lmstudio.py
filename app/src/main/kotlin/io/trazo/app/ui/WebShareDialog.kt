package io.trazo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.trazo.app.sync.SyncWebServer
import io.trazo.app.trazo
import kotlinx.coroutines.delay

/**
 * Prende la web de revisión y vincula un navegador (PC o teléfono de la misma red): QR o código de 8
 * dígitos. El navegador vinculado después entra directo, sin volver a pasar por acá.
 */
@Composable
internal fun WebShareDialog(onDismiss: () -> Unit) {
    val web = LocalContext.current.trazo.web
    val st by web.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        web.showCode()
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // Fuera del diálogo no queda ningún código vigente: solo entran los navegadores ya vinculados.
    DisposableEffect(Unit) { onDispose { web.cancelCode() } }

    val left = st.codeExpiresAt - now
    val code = st.code?.takeIf { left > 0 }
    val address = st.addresses.firstOrNull()
    val link = if (code != null && address != null) SyncWebServer.pairUrl(address, st.port, code) else null
    val qr = remember(link) {
        link?.let { runCatching { QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ver en otro dispositivo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Acceso web", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "La hoja abierta y su chat, en el navegador de otro equipo de la misma red. Anda mientras Trazo esté abierto.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = st.enabled, onCheckedChange = web::setEnabled)
                }
                st.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when {
                    !st.enabled -> Unit
                    !st.running -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    address == null -> Text("Conectate a una red Wi‑Fi (o a Tailscale) para entrar desde otro equipo.")
                    else -> {
                        HorizontalDivider()
                        Text("Vincular un navegador", style = MaterialTheme.typography.titleSmall)
                        if (code == null) {
                            Text("El código venció.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            qr?.let { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(it) } }
                            Text(
                                "Escaneá el QR, o abrí la dirección de abajo y escribí este código:",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                code.take(4) + " " + code.drop(4),
                                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp,
                            )
                            val s = left / 1000
                            Text("Vence en %d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelMedium)
                        }
                        SelectionContainer {
                            Column {
                                st.addresses.forEach { Text(SyncWebServer.url(it, st.port), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        Text(
                            "Se vincula una sola vez: después ese navegador entra directo a esa dirección. Para tenerla como app, " +
                                "en el menú de Chrome elegí «Agregar a la pantalla principal» (teléfono) o «Guardar y compartir → " +
                                "Instalar página como app» (PC).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (st.devices.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Navegadores vinculados", style = MaterialTheme.typography.titleSmall)
                    st.devices.forEach { d ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { web.forget(d.id) }) { Text("Desvincular") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            if (st.enabled && st.running && address != null) TextButton(onClick = web::newCode) { Text("Código nuevo") }
        },
    )
}
