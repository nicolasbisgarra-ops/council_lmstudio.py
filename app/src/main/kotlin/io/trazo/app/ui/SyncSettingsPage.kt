package io.trazo.app.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.trazo.app.sync.BluetoothLink
import io.trazo.app.sync.NearbyLink
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trazo.app.trazo
import io.trazo.core.sync.PairingCode
import io.trazo.core.sync.PairingQr
import kotlinx.coroutines.delay

/** Sincronización entre los dispositivos del usuario: vincular con un código y ver el estado. */
@Composable
internal fun SyncSettingsPage() {
    val sync = LocalContext.current.trazo.sync
    val state by sync.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    PrefCard("Sincronización") {
        SwitchRow(
            "Sincronizar con mis dispositivos", state.config.enabled, { sync.setEnabled(it) },
            subtitle = "Cuadernos, carpetas, patrones e imágenes van directo de un dispositivo al otro, cifrados y sin " +
                "servidores. Solo cuando están en la misma red (la de tu casa), conectados por Tailscale, por Bluetooth o «cerca, sin red».",
        )
        if (state.bluetoothAvailable) {
            val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                sync.setBluetooth(granted)
            }
            SwitchRow(
                "Usar también Bluetooth", state.config.bluetooth,
                { on -> if (on && BluetoothLink.PERMISSION != null && !state.bluetoothReady) askBt.launch(BluetoothLink.PERMISSION) else sync.setBluetooth(on) },
                subtitle = "Para cuando no hay red ni Tailscale: con los dispositivos ya emparejados en los ajustes de " +
                    "Bluetooth de Android. Es más lento; conviene para pocos cambios." +
                    if (state.config.bluetooth && !state.bluetoothReady) " Ahora está apagado o sin permiso." else "",
            )
        }
        if (state.nearbyAvailable) {
            val askNear = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                sync.setNearby(res.values.all { it })
            }
            SwitchRow(
                "Cerca, sin red", state.config.nearby,
                { on -> if (on && !state.nearbyReady) askNear.launch(NearbyLink.PERMISSIONS) else sync.setNearby(on) },
                subtitle = "Los dos equipos se conectan directo entre sí (Bluetooth y Wi‑Fi directo), sin router, internet " +
                    "ni Tailscale. Activalo en los dos y dejá la app abierta." +
                    when {
                        !state.config.nearby -> ""
                        !state.nearbyReady -> " Falta el permiso de «Dispositivos cercanos»."
                        state.nearbySeen > 0 -> " Ve ${state.nearbySeen} cerca."
                        else -> " Buscando…"
                    },
            )
        }
        var name by rememberSaveable(state.config.deviceId) { mutableStateOf(state.config.name) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40); sync.setName(name) },
            label = { Text("Nombre de este dispositivo") },
            placeholder = { Text(state.deviceName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.addresses.isNotEmpty()) {
            Text(
                state.addresses.partition { it.startsWith("100.") || it.startsWith("fd7a:115c:a1e0", ignoreCase = true) }
                    .let { (ts, home) ->
                        listOfNotNull(
                            home.takeIf { it.isNotEmpty() }?.let { "Red: " + it.joinToString(" · ") },
                            ts.takeIf { it.isNotEmpty() }?.let { "Tailscale: " + it.joinToString(" · ") },
                        ).joinToString("\n")
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    PrefCard("Vincular un dispositivo") {
        val pairing = state.pairing
        var entering by rememberSaveable { mutableStateOf(false) }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { sync.linkScanned(it) } }
        if (pairing != null) {
            Text("En el otro dispositivo, elegí «Escanear QR» o «Ingresar código» y escribí:", style = MaterialTheme.typography.bodyMedium)
            val qr = remember(pairing.code, state.addresses) {
                runCatching {
                    QRCodeWriter().encode(
                        PairingQr.encode(pairing.code, state.addresses), BarcodeFormat.QR_CODE, 0, 0,
                        mapOf(EncodeHintType.MARGIN to 1),
                    )
                }.getOrNull()
            }
            qr?.let { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(it) } }
            Text(
                PairingCode.pretty(pairing.code),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val left = ((pairing.expiresAt - now) / 1000).coerceAtLeast(0)
            Text(
                "Vence en ${left / 60}:${"%02d".format(left % 60)}. Si no está en esta red, usá una de las direcciones de arriba.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { sync.cancelCode() }) { Text("Cancelar") }
        } else if (entering) {
            var code by rememberSaveable { mutableStateOf("") }
            var address by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(14) },
                label = { Text("Código") },
                placeholder = { Text("ABCDE-FGH23") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.take(120) },
                label = { Text("Dirección (opcional)") },
                placeholder = { Text("100.x.y.z o nombre de Tailscale") },
                supportingText = { Text("Vacía: se busca en la red de casa.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sync.link(code, address) }, enabled = !state.linking && PairingCode.normalize(code) != null) {
                    Text(if (state.linking) "Vinculando…" else "Vincular")
                }
                TextButton(onClick = { entering = false }) { Text("Cancelar") }
            }
            if (state.config.bluetooth && state.bluetoothDevices.isNotEmpty()) {
                Text(
                    "O por Bluetooth, con uno emparejado (el otro tiene que tener «Usar también Bluetooth»):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.bluetoothDevices.take(6).forEach { d ->
                    OutlinedButton(
                        onClick = { sync.linkBluetooth(code, d.address) },
                        enabled = !state.linking && PairingCode.normalize(code) != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Bluetooth: ${d.name}") }
                }
            }
        } else {
            Text(
                "En uno elegí «Mostrar código» y en el otro «Escanear QR» (o «Ingresar código»). Se hace una sola vez. Sin red en común, activá antes «Cerca, sin red» en los dos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sync.showCode() }) { Text("Mostrar código") }
                OutlinedButton(onClick = { entering = true }) { Text("Ingresar código") }
            }
            Button(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Apuntá al QR que muestra el otro dispositivo")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                enabled = !state.linking,
            ) {
                Text(if (state.linking) "Vinculando…" else "Escanear QR")
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    PrefCard("Dispositivos vinculados") {
        var forgetting by remember { mutableStateOf<String?>(null) }
        var adding by remember { mutableStateOf<String?>(null) }
        if (state.peers.isEmpty()) {
            Text("Todavía ninguno.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.peers.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.peer.name, style = MaterialTheme.typography.titleSmall)
                val last = if (p.peer.lastSync == 0L) "Nunca" else
                    DateUtils.getRelativeTimeSpanString(p.peer.lastSync, now, DateUtils.MINUTE_IN_MILLIS).toString()
                val where = listOfNotNull(
                    if (p.nearby) "en esta red" else p.peer.addresses.firstOrNull()?.let { "última dirección $it" },
                    if (p.peer.bluetooth != null && state.config.bluetooth) "Bluetooth" else null,
                ).joinToString(" · ")
                Text(
                    listOf(if (p.running) "Sincronizando…" else "Última vez: $last", where).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                p.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { sync.syncNow() }, enabled = state.config.enabled && !p.running) { Text("Sincronizar ahora") }
                    TextButton(onClick = { adding = p.peer.deviceId }) { Text("Dirección") }
                    TextButton(onClick = { forgetting = p.peer.deviceId }) { Text("Olvidar") }
                }
            }
        }
        Text(
            "Mientras Trazo está abierto se sincroniza cada minuto y al cerrar un cuaderno. Si una hoja cambió en los " +
                "dos, quedan las dos versiones, una al lado de la otra. Un cuaderno abierto espera a que lo cierres.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        adding?.let { id ->
            val peerName = state.peers.firstOrNull { it.peer.deviceId == id }?.peer?.name.orEmpty()
            var text by remember(id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { adding = null },
                title = { Text("Dirección de $peerName") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Para sincronizar por Tailscale fuera de casa: escribí la IP 100.x del otro (la ves en la app de " +
                                "Tailscale o en estos mismos ajustes del otro, bajo su nombre) o su nombre, por ejemplo tablet.tu-red.ts.net. " +
                                "Igual se aprende sola en cada sincronización.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("100.x.y.z o nombre") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = { sync.addAddress(id, text); adding = null }, enabled = text.isNotBlank()) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancelar") } },
            )
        }
        forgetting?.let { id ->
            val peerName = state.peers.firstOrNull { it.peer.deviceId == id }?.peer?.name.orEmpty()
            AlertDialog(
                onDismissRequest = { forgetting = null },
                title = { Text("¿Olvidar $peerName?") },
                text = { Text("Deja de sincronizarse. Los cuadernos quedan en los dos; para volver hay que vincular de nuevo.") },
                confirmButton = { TextButton(onClick = { sync.forget(id); forgetting = null }) { Text("Olvidar") } },
                dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancelar") } },
            )
        }
    }
}

/** QR negro sobre blanco aunque el tema sea oscuro, para que cualquier cámara lo lea. */
@Composable
private fun QrImage(m: BitMatrix) {
    Canvas(Modifier.size(220.dp).background(Color.White).padding(4.dp)) {
        val cell = minOf(size.width / m.width, size.height / m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
