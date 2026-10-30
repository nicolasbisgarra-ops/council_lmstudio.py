package io.trazo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.trazo.ai.AiClient
import io.trazo.ai.AiException
import io.trazo.ai.AiModelInfo
import io.trazo.ai.AiProfile
import io.trazo.ai.AiProvider
import io.trazo.ai.DEFAULT_INSTRUCTIONS
import io.trazo.ai.ImageQuality
import io.trazo.ai.Rigor
import io.trazo.app.data.AiStore
import io.trazo.app.trazo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Página «Modo IA» de las preferencias: la lista de perfiles o el editor de uno. */
@Composable
internal fun AiSettingsPage() {
    val store = LocalContext.current.trazo.ai
    val ai by store.settings.collectAsState()
    var editing by remember { mutableStateOf<AiProfile?>(null) }
    var deleting by remember { mutableStateOf<AiProfile?>(null) }

    val draft = editing
    if (draft != null) {
        AiProfileEditor(store, draft, isNew = ai.profiles.none { it.id == draft.id }, onDone = { editing = null })
        return
    }

    PrefCard("Modo IA") {
        AiHint(
            "Revisa tu resolución con un modelo de lenguaje: le envía una captura de la hoja y el texto que reconoció Trazo, " +
                "y marca en la hoja dónde está cada error. Es lo único de Trazo que usa internet, y solo cuando lo pedís " +
                "con el botón de estrellas.",
        )
    }
    PrefCard("Modelos") {
        if (ai.profiles.isEmpty()) AiHint("Todavía no hay ningún modelo. Agregá uno con tu clave de API o un servidor de tu red (LM Studio, Ollama).")
        ai.profiles.forEach { p ->
            val inUse = p.id == ai.current?.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    AiHint(listOf(p.provider.label.substringBefore(" ("), p.model.ifBlank { "sin modelo" }).joinToString(" · "))
                }
                ChoiceChip(if (inUse) "En uso" else "Usar", inUse, onClick = { store.select(p.id) })
                IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, "Editar ${p.name}") }
                IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Borrar ${p.name}") }
            }
        }
        var choosing by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { choosing = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Agregar modelo")
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                AiProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        enabled = provider != AiProvider.ON_DEVICE,
                        onClick = { choosing = false; editing = store.newProfile(provider) },
                    )
                }
            }
        }
        AiHint("El modelo «En uso» es el que revisa; desde el botón de estrellas del editor se cambia rápido.")
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar «${p.name}»?") },
            text = { Text("Se borran su configuración y su clave guardada en este equipo.") },
            confirmButton = { TextButton(onClick = { deleting = null; store.delete(p.id) }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Qué está pasando con una llamada a la red del editor de perfiles. */
private sealed interface Remote {
    data object Idle : Remote
    data object Busy : Remote
    data class Done(val message: String, val ok: Boolean) : Remote
}

@Composable
private fun AiProfileEditor(store: AiStore, initial: AiProfile, isNew: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { AiClient() }
    var p by remember { mutableStateOf(initial) }
    var key by remember { mutableStateOf(store.key(initial.id).orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<AiModelInfo>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var fetch by remember { mutableStateOf<Remote>(Remote.Idle) }
    var test by remember { mutableStateOf<Remote>(Remote.Idle) }
    var advanced by remember { mutableStateOf(false) }
    val lostKey = !isNew && key.isEmpty() && store.hasKey(initial.id)

    fun call(set: (Remote) -> Unit, work: () -> Remote) {
        set(Remote.Busy)
        scope.launch {
            set(withContext(Dispatchers.IO) {
                try { work() } catch (e: AiException) { Remote.Done(e.message.orEmpty(), ok = false) } catch (e: Exception) {
                    Remote.Done("Error inesperado: ${e.message ?: e.javaClass.simpleName}", ok = false)
                }
            })
        }
    }

    PrefCard(if (isNew) "Nuevo modelo" else "Editar modelo") {
        OutlinedTextField(
            p.name, { p = p.copy(name = it) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Proveedor", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(AiProvider.entries.filter { it != AiProvider.ON_DEVICE }, p.provider, { it.label.substringBefore(" (") }, { provider ->
            // Cambiar de proveedor trae su URL, salvo que el usuario ya haya escrito otra.
            val url = if (p.baseUrl == p.provider.defaultBaseUrl || p.baseUrl.isBlank()) provider.defaultBaseUrl else p.baseUrl
            p = p.copy(provider = provider, baseUrl = url)
            models = emptyList()
        })
        OutlinedTextField(
            p.baseUrl, { p = p.copy(baseUrl = it.trim()) }, label = { Text("URL de la API") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        if (p.provider == AiProvider.OPENAI_COMPATIBLE) {
            AiHint("LM Studio: http://IP-de-la-PC:1234/v1 · Ollama: http://IP-de-la-PC:11434/v1 · OpenRouter: https://openrouter.ai/api/v1")
        }
        OutlinedTextField(
            key, { key = it.trim() },
            label = { Text(if (p.provider.needsKey) "Clave de API" else "Clave de API (opcional)") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Mostrar") } },
            modifier = Modifier.fillMaxWidth(),
        )
        AiHint(
            if (lostKey) "La clave guardada no se pudo leer (¿se restauró en otro equipo?). Escribila de nuevo."
            else "La clave se guarda cifrada con el Keystore de Android y solo se envía a esta URL.",
        )

        Box {
            OutlinedTextField(
                p.model, { p = p.copy(model = it.trim()) }, label = { Text("Modelo") }, singleLine = true,
                trailingIcon = {
                    if (models.isNotEmpty()) IconButton(onClick = { modelMenu = true }) { Icon(Icons.Filled.KeyboardArrowDown, "Elegir de la lista") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(if (m.displayName == m.id) m.id else "${m.displayName}  (${m.id})") },
                        onClick = { modelMenu = false; p = p.copy(model = m.id) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = fetch != Remote.Busy, onClick = {
                val draft = p
                val k = key
                call({ fetch = it }) {
                    val list = client.listModels(draft, k)
                    models = list
                    if (draft.model.isBlank() || list.none { it.id == draft.model }) modelMenu = true
                    Remote.Done("${list.size} modelos disponibles.", ok = true)
                }
            }) { Text(if (fetch == Remote.Busy) "Buscando…" else "Obtener modelos") }
            OutlinedButton(enabled = test != Remote.Busy, onClick = {
                val draft = p
                val k = key
                call({ test = it }) {
                    val r = client.testConnection(draft, k)
                    Remote.Done(
                        if (r.readsImages) "Funciona y lee imágenes (${r.latencyMs} ms)."
                        else "Respondió en ${r.latencyMs} ms, pero no leyó la imagen de prueba («${r.reply.take(60)}»): elegí un modelo con visión.",
                        ok = r.readsImages,
                    )
                }
            }) { Text(if (test == Remote.Busy) "Probando…" else "Probar conexión") }
        }
        RemoteStatus(fetch)
        RemoteStatus(test)
    }

    PrefCard("Revisión") {
        Text("Rigor", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(Rigor.entries, p.rigor, { it.label }, { p = p.copy(rigor = it) })
        AiHint(p.rigor.instruction)
        Text("Calidad de la captura", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(ImageQuality.entries, p.imageQuality, { "${it.label} (${it.longEdgePx} px)" }, { p = p.copy(imageQuality = it) })
        AiHint("Más calidad lee mejor la letra chica, pero tarda más y gasta más tokens.")
    }

    PrefCard("Avanzado") {
        SwitchRow("Mostrar opciones avanzadas", advanced, { advanced = it })
        if (advanced) {
            SwitchRow(
                "Fijar la temperatura", p.temperature != null, { on -> p = p.copy(temperature = if (on) 0.2 else null) },
                subtitle = "Apagado no se envía: algunos modelos la rechazan.",
            )
            p.temperature?.let { t ->
                LabeledSlider("Temperatura", "%.1f".format(t), t.toFloat(), 0f..1f, onChange = { v ->
                    p = p.copy(temperature = Math.round(v * 10) / 10.0)
                }, steps = 9)
            }
            LabeledSlider("Tokens máximos", "${p.maxTokens}", p.maxTokens.toFloat(), 512f..16384f, onChange = { v ->
                p = p.copy(maxTokens = (Math.round(v / 512f) * 512).coerceIn(512, 16384))
            })
            LabeledSlider("Tiempo de espera", "${p.timeoutSeconds} s", p.timeoutSeconds.toFloat(), 10f..300f, onChange = { v ->
                p = p.copy(timeoutSeconds = (Math.round(v / 10f) * 10).coerceIn(10, 300))
            })
            Text("Instrucciones del modelo", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                p.instructions, { p = p.copy(instructions = it) }, minLines = 6, maxLines = 18, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiHint(if (p.instructions == DEFAULT_INSTRUCTIONS) "Son las instrucciones de fábrica." else "Instrucciones propias.")
                Spacer(Modifier.weight(1f))
                TextButton(enabled = p.instructions != DEFAULT_INSTRUCTIONS, onClick = { p = p.copy(instructions = DEFAULT_INSTRUCTIONS) }) {
                    Text("Restaurar")
                }
            }
            AiHint("Mantené la forma del JSON de respuesta: es la que Trazo usa para dibujar las marcas.")
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onDone) { Text("Cancelar") }
        Button(enabled = p.name.isNotBlank(), onClick = {
            store.save(p.copy(name = p.name.trim()))
            store.setKey(p.id, key)
            onDone()
        }) { Text("Guardar") }
    }
}

@Composable
private fun RemoteStatus(state: Remote) {
    if (state !is Remote.Done) return
    Text(
        state.message,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun AiHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Lo que abre el botón de estrellas del editor: el modelo en uso, cambio rápido y la revisión. */
@Composable
internal fun AiQuickMenu(onDismiss: () -> Unit, onConfigure: () -> Unit) {
    val store = LocalContext.current.trazo.ai
    val ai by store.settings.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AiStarsIcon, null, tint = Color.Unspecified) },
        title = { Text("Modo IA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ai.profiles.isEmpty()) {
                    Text("Todavía no configuraste ningún modelo.")
                } else {
                    Text("Modelo", style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(ai.profiles, ai.current ?: ai.profiles.first(), { it.name }, { store.select(it.id) })
                    ai.current?.problem?.let { AiHint(it) }
                }
                Button(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Revisar página") }
                AiHint("La revisión de la hoja con marcas llega en la próxima versión. Por ahora podés dejar listos tus modelos.")
            }
        },
        confirmButton = { TextButton(onClick = onConfigure) { Text("Configurar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
