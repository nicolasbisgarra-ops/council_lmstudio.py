package io.trazo.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.runtime.LaunchedEffect
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
import io.trazo.ai.AiOAuth
import io.trazo.ai.AiProfile
import io.trazo.ai.AiProvider
import io.trazo.ai.AiReasoningEffortMode
import io.trazo.ai.DEFAULT_INSTRUCTIONS
import io.trazo.ai.ImageQuality
import io.trazo.ai.OAuthTokens
import io.trazo.ai.Rigor
import io.trazo.ai.signIn
import io.trazo.app.data.AiStore
import io.trazo.app.trazo
import io.trazo.core.applySavedOrder
import io.trazo.core.moveItem
import io.trazo.core.positionLabel
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
    var localModels by remember { mutableStateOf(false) }
    if (localModels) {
        LocalModelsScreen(onBack = { localModels = false }, onUse = { model ->
            val existing = store.settings.value.profiles.firstOrNull { it.provider == AiProvider.ON_DEVICE && it.model == model.id }
            val profile = existing ?: store.newProfile(AiProvider.ON_DEVICE).copy(
                name = model.modelFile.nameWithoutExtension.substringBeforeLast('-'), model = model.id,
                localTextOnly = model.modelFile.extension.equals("task", true),
            )
            store.save(profile)
            store.select(profile.id)
            localModels = false
        })
        return
    }

    var localOrder by remember { mutableStateOf<List<String>?>(null) }
    val profiles = remember(ai.profiles, localOrder) { applySavedOrder(ai.profiles, localOrder.orEmpty()) { it.id } }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableState(listState) { from, to ->
        // Puede haber varios cruces antes del próximo frame.
        localOrder = moveItem(applySavedOrder(store.settings.value.profiles, localOrder.orEmpty()) { it.id }, from, to).map { it.id }
    }
    PersistReorderOnEnd(reorder, { localOrder != null }) {
        localOrder?.let(store::reorderProfiles)
        localOrder = null
    }

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
        OutlinedButton(onClick = { localModels = true }) { Text("Modelos en la tableta · Importar y usar") }
        if (ai.profiles.isEmpty()) {
            AiHint("Todavía no hay ningún modelo. Agregá uno con tu clave de API, con tu cuenta (ChatGPT, Google) o un servidor de tu red (LM Studio, Ollama).")
        }
        AiHint("Mantené apretado el ícono de líneas para ordenar los modelos.")
        if (profiles.isNotEmpty()) LazyColumn(
            state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
        ) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { index, p ->
                ReorderableItem(reorder, p.id) { _ ->
                    val inUse = p.id == ai.current?.id
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Menu, "Mover ${p.name}", Modifier.dragHandle(reorder, p.id).padding(12.dp).size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            AiHint(positionLabel(index, profiles.size))
                            AiHint(listOf(p.provider.label.substringBefore(" ("), p.model.ifBlank { "sin modelo" }).joinToString(" · "))
                        }
                        ChoiceChip(if (inUse) "En uso" else "Usar", inUse, onClick = { store.select(p.id) })
                        IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, "Editar ${p.name}") }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Borrar ${p.name}") }
                    }
                }
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
                        onClick = {
                            choosing = false
                            if (provider == AiProvider.ON_DEVICE) localModels = true else editing = store.newProfile(provider)
                        },
                    )
                }
            }
        }
        AiHint("El modelo «En uso» es el que revisa; desde el botón de estrellas del editor se cambia rápido.")
    }

    AiSendLog()

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

/** Registro de envíos (36): qué salió del equipo, cuándo, a qué modelo y cómo terminó. */
@Composable
private fun AiSendLog() {
    val journal = LocalContext.current.trazo.aiJournal
    val log by journal.log.collectAsState()
    val legacyQueue by journal.queue.collectAsState()
    val app = LocalContext.current.trazo
    val durablePending by androidx.compose.runtime.produceState(0, app) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { app.reviewQueue.pendingCount() }.getOrDefault(0)
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val pending = durablePending + legacyQueue.size
    var all by remember { mutableStateOf(false) }
    PrefCard("Registro de envíos") {
        if (log.isEmpty()) AiHint("Todavía no se envió nada.")
        val format = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
        val shown = log.asReversed().let { if (all) it else it.take(8) }
        shown.forEach { s ->
            Column {
                Text(
                    "${format.format(java.util.Date(s.at))} · ${s.notebook}, hoja ${s.page}" + if (s.selection) " (selección)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiHint(
                    listOf(
                        s.profile + (s.model.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        "${s.lines} renglones",
                        "imagen ${s.imageBytes / 1024} KB",
                        "%.1f s".format(s.millis / 1000.0),
                        s.error?.let { "error: $it" } ?: if (s.marks == 1) "1 marca" else "${s.marks} marcas",
                    ).joinToString(" · "),
                )
                s.textChars?.let { AiHint("Texto inicial: $it caracteres") }
                if (s.imageWidth != null && s.imageHeight != null) AiHint("Imagen: ${s.imageWidth} × ${s.imageHeight} px")
                val usage = s.usage
                if (usage == null) AiHint("Consumo de tokens no disponible.") else {
                    usage.inputTokens?.let { AiHint("Entrada total (texto e imagen): $it tokens") }
                    usage.imageInputTokens?.let { AiHint("Imagen: $it tokens de la entrada") }
                    usage.cachedInputTokens?.let { AiHint("Caché: $it tokens de la entrada") }
                    usage.outputTokens?.let { AiHint("Salida total: $it tokens") }
                    usage.reasoningTokens?.let { AiHint("Razonamiento: $it tokens, incluidos en la salida") }
                }
            }
        }
        if (!all && log.size > shown.size) TextButton(onClick = { all = true }) { Text("Ver todo (${log.size})") }
        if (pending > 0) AiHint(if (pending == 1) "1 revisión pendiente, sin envío automático." else "$pending revisiones pendientes, sin envío automático.")
        if (log.isNotEmpty()) OutlinedButton(onClick = journal::clearLog) { Text("Borrar registro") }
        AiHint("Solo se guarda este resumen; la imagen y el texto enviados no quedan en el equipo.")
    }
}

/** Qué está pasando con una llamada a la red del editor de perfiles. */
private sealed interface Remote {
    data object Idle : Remote
    data object Busy : Remote
    data class Done(val message: String, val ok: Boolean) : Remote
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiProfileEditor(store: AiStore, initial: AiProfile, isNew: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localRuntime = remember(context) { io.trazo.app.ai.LocalCompletion(context) }
    androidx.compose.runtime.DisposableEffect(localRuntime) { onDispose { localRuntime.close() } }
    val client = remember(localRuntime) { AiClient(local = localRuntime) }
    val oauth = remember { AiOAuth() }
    var p by remember { mutableStateOf(initial) }
    // Si el perfil tenía una sesión guardada, no se muestra como si fuera una clave.
    var key by remember { mutableStateOf(store.key(initial.id)?.takeIf { OAuthTokens.decode(it) == null }.orEmpty()) }
    var session by remember { mutableStateOf(store.session(initial.id)) }
    var login by remember { mutableStateOf<Remote>(Remote.Idle) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<AiModelInfo>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var fetch by remember { mutableStateOf<Remote>(Remote.Idle) }
    var test by remember { mutableStateOf<Remote>(Remote.Idle) }
    var advanced by remember { mutableStateOf(false) }
    val lostKey = !isNew && key.isEmpty() && session == null && store.hasKey(initial.id)

    /** Con inicio de sesión, la «clave» es la sesión guardada (renovada si venció). */
    fun credentialFor(draft: AiProfile): String? = if (draft.provider.signIn) store.credential(draft, oauth) else key

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
        ChoiceRow(AiProvider.entries, p.provider, { it.label.substringBefore(" (") }, { provider ->
            // Cambiar de proveedor trae su URL, salvo que el usuario ya haya escrito otra.
            // Con inicio de sesión la URL es siempre la oficial.
            val url = if (provider.signIn || p.baseUrl == p.provider.defaultBaseUrl || p.baseUrl.isBlank()) provider.defaultBaseUrl else p.baseUrl
            if (provider != p.provider) {
                p = p.copy(
                    provider = provider,
                    baseUrl = url,
                ).resetReasoningEffortForTarget()
                models = emptyList()
                modelMenu = false
            }
        })
        LaunchedEffect(p.provider) {
            // Un perfil viejo o importado con otra URL vuelve a la oficial.
            if (p.provider.signIn && p.baseUrl != p.provider.defaultBaseUrl) {
                p = p.copy(baseUrl = p.provider.defaultBaseUrl).resetReasoningEffortForTarget()
                models = emptyList()
                modelMenu = false
            }
        }
        if (p.provider == AiProvider.ON_DEVICE) {
            AiHint("El identificador corresponde a un archivo importado desde Modelos en la tableta. No se usa internet.")
            SwitchRow("Solo texto reconocido (sin imagen)", p.localTextOnly, { p = p.copy(localTextOnly = it) })
            AiHint("Sin imagen el modelo solo comprueba los renglones que Trazo reconoció. No puede verificar símbolos desconocidos, gráficos ni figuras. MediaPipe requiere esta opción.")
        } else if (p.provider.signIn) {
            AiHint("Servidor: ${p.provider.defaultBaseUrl} (fijo: la sesión solo se envía ahí)")
        } else OutlinedTextField(
            p.baseUrl, { value ->
                val baseUrl = value.trim()
                if (baseUrl == p.baseUrl) p = p.copy(baseUrl = baseUrl)
                else {
                    p = p.copy(baseUrl = baseUrl).resetReasoningEffortForTarget()
                    models = emptyList()
                    modelMenu = false
                }
            }, label = { Text("URL de la API") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        if (p.provider == AiProvider.OPENAI_COMPATIBLE) {
            AiHint("LM Studio: http://IP-de-la-PC:1234/v1 · Ollama: http://IP-de-la-PC:11434/v1 · OpenRouter: https://openrouter.ai/api/v1")
        }
        if (p.provider.signIn) SignInSection(p, session, login, onSignIn = {
            val draft = p
            call({ login = it }) {
                val t = oauth.signIn(draft, open = { url ->
                    Handler(Looper.getMainLooper()).post {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
                store.setKey(draft.id, t.encode())
                session = t
                Remote.Done("Sesión iniciada" + (t.email?.let { " como $it" } ?: "") + ".", ok = true)
            }
        }, onSignOut = {
            store.setKey(p.id, "")
            session = null
            login = Remote.Idle
        }, onChange = { p = it }) else if (p.provider != AiProvider.ON_DEVICE) OutlinedTextField(
            key, { key = it.trim() },
            label = { Text(if (p.provider.needsKey) "Clave de API" else "Clave de API (opcional)") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Mostrar") } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!p.provider.signIn) AiHint(
            if (lostKey) "La clave guardada no se pudo leer (¿se restauró en otro equipo?). Escribila de nuevo."
            else "La clave se guarda cifrada con el Keystore de Android y solo se envía a esta URL.",
        )

        Box {
            OutlinedTextField(
                p.model, { value ->
                    val model = value.trim()
                    p = if (model == p.model) p.copy(model = model)
                    else p.copy(model = model).resetReasoningEffortForTarget()
                }, label = { Text("Modelo") }, singleLine = true,
                trailingIcon = {
                    if (models.isNotEmpty()) IconButton(onClick = { modelMenu = true }) { Icon(Icons.Filled.KeyboardArrowDown, "Elegir de la lista") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(if (m.displayName == m.id) m.id else "${m.displayName}  (${m.id})") },
                        onClick = { modelMenu = false; p = p.withCatalogModel(m, preserveSelection = true) },
                    )
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = fetch != Remote.Busy, onClick = {
                val draft = p
                fetch = Remote.Busy
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { client.listModels(draft, credentialFor(draft)) }
                    }
                    val current = p
                    if (!current.hasSameCatalogTarget(draft)) {
                        fetch = Remote.Done("La configuración cambió mientras se consultaba; se descartó la respuesta.", ok = false)
                    } else result.fold(
                        onSuccess = { list ->
                            models = list
                            val info = list.firstOrNull { it.id == draft.model }
                            p = if (info != null) current.withCatalogModel(info, preserveSelection = true)
                            else current.withoutCatalogEfforts()
                            if (draft.model.isBlank()) modelMenu = true
                            val detail = when {
                                draft.model.isBlank() -> "Elegí un modelo para consultar sus esfuerzos."
                                info != null -> "Se actualizaron los esfuerzos de ${info.displayName}."
                                else -> "El catálogo no incluyó «${draft.model}»; se descartaron sus niveles guardados."
                            }
                            fetch = Remote.Done("${list.size} modelos disponibles. $detail", ok = true)
                        },
                        onFailure = { error ->
                            fetch = if (error is AiException) Remote.Done(error.message.orEmpty(), ok = false)
                            else Remote.Done("Error inesperado: ${error.message ?: error.javaClass.simpleName}", ok = false)
                        },
                    )
                }
            }) { Text(if (fetch == Remote.Busy) "Buscando…" else if (p.model.isBlank()) "Obtener modelos" else "Consultar esfuerzos") }
            OutlinedButton(enabled = test != Remote.Busy && p.model.isNotBlank(), onClick = {
                val draft = p
                call({ test = it }) {
                    val r = client.testConnection(draft, credentialFor(draft))
                    Remote.Done(
                        if (r.readsImages) "Funciona y lee imágenes (${r.latencyMs} ms)."
                        else "Respondió en ${r.latencyMs} ms, pero no leyó la imagen de prueba («${r.reply.take(60)}»): elegí un modelo con visión.",
                        ok = r.readsImages,
                    )
                }
            }) { Text(if (test == Remote.Busy) "Probando…" else "Probar conexión") }
        }
        if (p.model.isBlank()) AiHint("Elegí un modelo de la lista o escribí su identificador para probar la conexión.")
        RemoteStatus(fetch)
        RemoteStatus(test)
    }

    ReasoningEffortSection(p) { p = it }

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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AiHint(if (p.instructions == DEFAULT_INSTRUCTIONS) "Son las instrucciones de fábrica." else "Instrucciones propias.")
                TextButton(enabled = p.instructions != DEFAULT_INSTRUCTIONS, onClick = { p = p.copy(instructions = DEFAULT_INSTRUCTIONS) }) {
                    Text("Restaurar")
                }
            }
            AiHint("Mantené la forma del JSON de respuesta: es la que Trazo usa para dibujar las marcas.")
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = {
            // Una sesión iniciada en un perfil que no se guardó no debe quedar suelta.
            if (isNew) store.setKey(p.id, "")
            onDone()
        }) { Text("Cancelar") }
        Button(enabled = p.name.isNotBlank() && p.canSaveReasoningEffort(), onClick = {
            store.save(p.copy(name = p.name.trim()))
            // La sesión ya quedó guardada al iniciarla; la clave escrita es solo para los demás proveedores.
            if (!p.provider.signIn) store.setKey(p.id, key)
            onDone()
        }) { Text("Guardar") }
    }
}

/**
 * Auto siempre omite el parámetro. Manual sólo usa valores publicados por el catálogo, o un valor
 * escrito por el usuario cuando el endpoint confirma el campo pero no enumera sus valores.
 */
@Composable
private fun ReasoningEffortSection(p: AiProfile, onChange: (AiProfile) -> Unit) {
    val manualAvailable = p.canSelectReasoningEffort
    val modeOptions = buildList {
        add(AiReasoningEffortMode.AUTO)
        if (manualAvailable || p.reasoningEffortMode == AiReasoningEffortMode.MANUAL) add(AiReasoningEffortMode.MANUAL)
    }
    PrefCard("Esfuerzo de razonamiento") {
        ChoiceRow(modeOptions, p.reasoningEffortMode, {
            if (it == AiReasoningEffortMode.AUTO) "Automático" else "Manual"
        }, { mode ->
            if (mode == AiReasoningEffortMode.AUTO) {
                onChange(p.copy(reasoningEffortMode = mode, reasoningEffort = null))
            } else {
                val knownForModel: (String) -> Boolean = { value ->
                    p.availableReasoningEfforts.isEmpty() || p.availableReasoningEfforts.any { it.value == value }
                }
                val initial = p.reasoningEffort?.takeIf(knownForModel)
                    ?: p.defaultReasoningEffort?.takeIf(knownForModel)
                onChange(p.copy(reasoningEffortMode = mode, reasoningEffort = initial))
            }
        })

        if (p.reasoningEffortMode == AiReasoningEffortMode.AUTO) {
            val note = when {
                p.defaultReasoningEffort != null -> "El catálogo indica ${p.defaultReasoningEffort} como valor predeterminado. Trazo omite el parámetro."
                p.reasoningEffortCatalogKnown && p.reasoningThinkingSupported && p.availableReasoningEfforts.isEmpty() ->
                    "El catálogo indica que el modelo admite razonamiento, pero no publica niveles. Trazo deja que el proveedor elija."
                p.provider == AiProvider.OPENAI -> "La API de OpenAI admite reasoning_effort en modelos de razonamiento, pero su lista no publica valores por modelo. Automático usa el valor del servidor."
                p.provider == AiProvider.ANTHROPIC -> "La API de Anthropic admite output_config.effort en modelos compatibles, pero el catálogo no publicó niveles para este modelo. Automático omite el campo."
                p.reasoningEffortCatalogKnown -> "Este catálogo no informó un nivel predeterminado ni opciones. Trazo omite el parámetro."
                else -> "No hay opciones de esfuerzo en este perfil. Automático omite el campo y usa la configuración del proveedor. Consultá el catálogo para ver niveles manuales."
            }
            AiHint(note)
        } else if (!manualAvailable) {
            AiHint("El perfil guardó un nivel manual, pero el catálogo actual no confirma que este modelo lo admita. Elegí Automático o consultá el catálogo.")
        } else if (p.availableReasoningEfforts.isNotEmpty()) {
            Text("Niveles informados por el catálogo", style = MaterialTheme.typography.bodyMedium)
            val selected = p.availableReasoningEfforts.firstOrNull { it.value == p.reasoningEffort }
                ?: p.availableReasoningEfforts.first().copy(value = "")
            ChoiceRow(
                p.availableReasoningEfforts,
                selected,
                { option -> option.description.takeIf { it.isNotBlank() }?.let { "${option.value} · $it" } ?: option.value },
                { option -> onChange(p.copy(reasoningEffort = option.value)) },
            )
            p.availableReasoningEfforts.firstOrNull { it.value == p.reasoningEffort }?.description
                ?.takeIf { it.isNotBlank() }?.let { AiHint(it) }
            if (p.reasoningEffort.isNullOrBlank()) AiHint("Elegí un nivel del catálogo para activar el modo manual.")
            if (p.defaultReasoningEffort != null) AiHint("Predeterminado del catálogo: ${p.defaultReasoningEffort}.")
        } else {
            OutlinedTextField(
                value = p.reasoningEffort.orEmpty(),
                onValueChange = { onChange(p.copy(reasoningEffort = it.trim().takeIf(String::isNotEmpty))) },
                label = { Text("Valor manual exacto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AiHint(when {
                p.provider == AiProvider.OPENAI -> "El catálogo no enumera valores para este modelo. Escribí un valor que la API admita; uno no válido puede ser rechazado."
                p.provider == AiProvider.ANTHROPIC -> "La API de Anthropic admite output_config.effort en modelos compatibles; el catálogo no enumera valores aquí. Escribí un valor exacto admitido; uno incompatible puede ser rechazado."
                p.reasoningEffortManualSupported -> "El catálogo confirma el parámetro, pero no lista sus valores. Escribí uno admitido por el servidor; no se supone ningún nivel."
                else -> "El catálogo no informó valores manuales."
            })
        }
    }
}

private fun AiProfile.resetReasoningEffortForTarget(): AiProfile = copy(
    reasoningEffortMode = AiReasoningEffortMode.AUTO,
    reasoningEffort = null,
    availableReasoningEfforts = emptyList(),
    defaultReasoningEffort = null,
    reasoningEffortCatalogKnown = false,
    reasoningEffortManualSupported = false,
    reasoningThinkingSupported = false,
)

private fun AiProfile.hasSameCatalogTarget(other: AiProfile): Boolean =
    provider == other.provider && model == other.model && endpoint == other.endpoint

private fun AiProfile.withCatalogModel(info: AiModelInfo, preserveSelection: Boolean = false): AiProfile {
    val sameModel = model == info.id
    val keepEffort = preserveSelection && sameModel && reasoningEffortMode == AiReasoningEffortMode.MANUAL &&
        (if (info.reasoningEfforts.isNotEmpty()) info.reasoningEfforts.any { it.value == reasoningEffort }
        else provider == AiProvider.OPENAI || provider == AiProvider.ANTHROPIC || info.reasoningEffortManualSupported)
    return copy(
        model = info.id,
        reasoningEffortMode = if (keepEffort) AiReasoningEffortMode.MANUAL else AiReasoningEffortMode.AUTO,
        reasoningEffort = if (keepEffort) reasoningEffort else null,
        availableReasoningEfforts = info.reasoningEfforts,
        defaultReasoningEffort = info.defaultReasoningEffort,
        reasoningEffortCatalogKnown = info.reasoningEffortCatalogKnown,
        reasoningEffortManualSupported = info.reasoningEffortManualSupported,
        reasoningThinkingSupported = info.reasoningThinkingSupported,
    )
}

private fun AiProfile.withoutCatalogEfforts(): AiProfile {
    val keepFreeformManual = provider in setOf(AiProvider.OPENAI, AiProvider.ANTHROPIC) && reasoningEffortMode == AiReasoningEffortMode.MANUAL
    return copy(
        reasoningEffortMode = if (keepFreeformManual) AiReasoningEffortMode.MANUAL else AiReasoningEffortMode.AUTO,
        reasoningEffort = reasoningEffort.takeIf { keepFreeformManual },
        availableReasoningEfforts = emptyList(),
        defaultReasoningEffort = null,
        reasoningEffortCatalogKnown = false,
        reasoningEffortManualSupported = false,
        reasoningThinkingSupported = false,
    )
}

private fun AiProfile.canSaveReasoningEffort(): Boolean {
    if (reasoningEffortMode == AiReasoningEffortMode.AUTO) return true
    if (reasoningEffort.isNullOrBlank()) return false
    if (!canSelectReasoningEffort) return false
    return availableReasoningEfforts.isEmpty() || availableReasoningEfforts.any { it.value == reasoningEffort }
}

/** Iniciar o cerrar sesión con la cuenta, en lugar de la clave de API. */
@Composable
private fun SignInSection(
    p: AiProfile,
    session: OAuthTokens?,
    login: Remote,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onChange: (AiProfile) -> Unit,
) {
    if (p.provider == AiProvider.GEMINI_GOOGLE) {
        AiHint(
            "Necesitás un cliente OAuth propio: en console.cloud.google.com creá un proyecto, activá «Generative Language API», " +
                "configurá la pantalla de consentimiento (Externo, agregate como usuario de prueba) y en Credenciales creá un " +
                "«ID de cliente OAuth» de tipo «App de escritorio». El uso lo paga ese proyecto (su cuota gratis o su facturación), " +
                "no la suscripción de Gemini.",
        )
        OutlinedTextField(
            p.oauthClientId, { onChange(p.copy(oauthClientId = it.trim())) }, label = { Text("ID de cliente") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            p.oauthClientSecret, { onChange(p.copy(oauthClientSecret = it.trim())) }, label = { Text("Secreto del cliente") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            p.googleProject, { onChange(p.copy(googleProject = it.trim())) }, label = { Text("ID del proyecto (opcional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        AiHint(
            "Usa tu ChatGPT Plus o Pro con el mismo inicio de sesión que Codex, sin clave de API: el uso sale de los límites de tu plan. " +
                "No es una API oficial para otras apps: OpenAI puede cambiarla o limitarla en cualquier momento.",
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session == null) {
            Button(enabled = login != Remote.Busy && (p.provider != AiProvider.GEMINI_GOOGLE || p.oauthClientId.isNotBlank()), onClick = onSignIn) {
                Text(if (login == Remote.Busy) "Esperando al navegador…" else "Iniciar sesión")
            }
        } else {
            AiHint("Sesión iniciada" + (session.email?.let { " como $it" } ?: "") + ".")
            OutlinedButton(onClick = onSignOut) { Text("Cerrar sesión") }
        }
    }
    if (login == Remote.Busy) AiHint("Se abrió el navegador: iniciá sesión ahí y volvé a Trazo.")
    RemoteStatus(login)
    AiHint("La sesión se guarda cifrada con el Keystore de Android y se renueva sola.")
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
internal fun AiQuickMenu(
    onDismiss: () -> Unit,
    onConfigure: () -> Unit,
    onReview: () -> Unit,
    reviewing: Boolean = false,
    pending: Int = 0,
) {
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
                    ai.current?.let { c ->
                        (c.problem ?: "Falta iniciar sesión en «${c.name}».".takeIf { c.provider.signIn && store.session(c.id) == null })?.let { AiHint(it) }
                    }
                }
                Button(enabled = ai.current != null && !reviewing, onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                    Text(if (reviewing) "Revisando…" else "Revisar página")
                }
                AiHint("Para revisar solo una parte, elegila con el lazo y tocá «Revisar». Tocá una marca para verla, discutirla o quitarla.")
                if (pending > 0) AiHint(if (pending == 1) "1 revisión pendiente. Abrí esa página y tocá «Revisar» para enviarla." else "$pending revisiones pendientes. Se envían solo cuando revisás cada página manualmente.")
            }
        },
        confirmButton = { TextButton(onClick = onConfigure) { Text("Configurar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
