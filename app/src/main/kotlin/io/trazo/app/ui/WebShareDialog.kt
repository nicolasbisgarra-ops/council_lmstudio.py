package io.trazo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

/** Muestra el QR y el enlace para ver la revisión desde una PC o un teléfono de la misma red. */
@Composable
internal fun WebShareDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val share = vm.webShare
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(share?.expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = share?.let { it.expiresAt - now } ?: 0L
    val expired = share != null && left <= 0
    val url = share?.urls?.firstOrNull()
    val qr = remember(url) {
        url?.let {
            runCatching { QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ver en otro dispositivo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    share == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    expired -> Text("Se apagó. Tocá «Compartir de nuevo» para generar otro enlace.")
                    url == null -> Text("Conectate a una red Wi‑Fi (o a Tailscale) para poder compartir.")
                    else -> {
                        qr?.let { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(it) } }
                        SelectionContainer {
                            Column { share.urls.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                        }
                        val s = left / 1000
                        Text("Se apaga en %d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Abrí este enlace en una PC o teléfono conectado a la misma red Wi‑Fi. Muestra la hoja que tengas abierta " +
                                "y su chat. Solo se ve mientras Trazo esté abierto; se apaga sola en 30 minutos.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (expired) TextButton(onClick = vm::startWebShare) { Text("Compartir de nuevo") }
            else TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = {
            TextButton(onClick = { vm.stopWebShare(); onDismiss() }) { Text(if (expired) "Cerrar" else "Dejar de compartir") }
        },
    )
}
