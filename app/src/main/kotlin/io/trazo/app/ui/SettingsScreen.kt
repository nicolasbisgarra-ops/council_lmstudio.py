@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.trazo.app.data.DEFAULT_QUICK_COLORS
import io.trazo.app.data.EraserMode
import io.trazo.app.data.PaperTone
import io.trazo.app.data.SideButtonAction
import io.trazo.app.data.ThemeMode
import io.trazo.app.data.TrazoSettings
import io.trazo.app.trazo
import io.trazo.core.MathNotes
import io.trazo.core.Paper

/** Qué color libre se está eligiendo en las preferencias. */
private sealed interface PrefColor {
    data object Accent : PrefColor
    data class Quick(val slot: Int) : PrefColor
    /** Uno de los dos colores que alternan dos clics del botón del lápiz. */
    data class Swap(val slot: Int) : PrefColor
    /** El color de los resultados que completa la hoja al escribir «=». */
    data object MathResult : PrefColor
}

/** Lista de secciones con páginas internas; volver desde una página conserva la búsqueda. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = LocalContext.current.trazo.settings
    val s by store.settings.collectAsState()
    val stylus = remember { hasStylusDevice() }
    var picking by remember { mutableStateOf<PrefColor?>(null) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var resetTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val section = PreferenceSection.entries.firstOrNull { it.name == page }
    val focusManager = LocalFocusManager.current
    val update = store::update
    val openSection: (PreferenceSection) -> Unit = { target ->
        focusManager.clearFocus()
        page = target.name
    }
    val back: () -> Unit = {
        if (section == null) onBack() else { page = null; picking = null }
    }
    BackHandler(enabled = section != null) { back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section?.title ?: "Preferencias") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            key(section) {
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (section == null) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, label = { Text("Buscar ajustes") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        if (query.isBlank()) {
                            PreferenceSection.entries.forEach { item ->
                                PreferenceLink(item.icon(), item.title, item.summary) { openSection(item) }
                            }
                        } else {
                            val search = query.preferenceSearchKey()
                            val matches = PreferenceSection.entries.flatMap { item ->
                                item.settingNames.filter { name ->
                                    name.preferenceSearchKey().contains(search) || item.title.preferenceSearchKey().contains(search)
                                }.map { name -> item to name }
                            }
                            if (matches.isEmpty()) Hint("No encontramos ajustes con ese nombre.")
                            matches.forEach { (item, name) ->
                                PreferenceLink(item.icon(), name, item.title) { openSection(item) }
                            }
                        }
                    } else {
                        SettingsPage(section, s, stylus, update) { picking = it }
                    }

                    OutlinedButton(onClick = { resetTarget = section?.name ?: "ALL" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (section == null) "Restablecer preferencias" else "Volver a fábrica")
                    }
                    Text(
                        "Todo funciona sin conexión. Los cuadernos se guardan como trazos editables en este dispositivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    )
                }
            }
        }
    }

    picking?.let { target ->
        ColorPickerDialog(
            initial = when (target) {
                PrefColor.Accent -> s.accent
                is PrefColor.Quick -> s.quickColors.getOrElse(target.slot) { s.penColor }
                is PrefColor.Swap -> s.swapColors.getOrElse(target.slot) { s.penColor }
                PrefColor.MathResult -> s.mathResultColor
            },
            title = when (target) {
                PrefColor.Accent -> "Color de acento"
                is PrefColor.Quick -> "Color rápido"
                is PrefColor.Swap -> "Color del doble clic ${target.slot + 1}"
                PrefColor.MathResult -> "Color de lo autocompletado"
            },
            onDismiss = { picking = null },
            onPick = { c ->
                picking = null
                when (target) {
                    PrefColor.Accent -> update { it.copy(accent = c) }
                    is PrefColor.Quick -> update { st -> st.copy(quickColors = st.quickColors.mapIndexed { i, q -> if (i == target.slot) c else q }) }
                    is PrefColor.Swap -> update { st -> st.copy(swapColors = st.swapColors.mapIndexed { i, q -> if (i == target.slot) c else q }) }
                    PrefColor.MathResult -> update { it.copy(mathResultColor = c) }
                }
            },
            suggestions = if (target == PrefColor.Accent) AccentPresets.map { it.accent } else PenPalette,
        )
    }

    resetTarget?.let { target ->
        val targetSection = PreferenceSection.entries.firstOrNull { it.name == target }
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(targetSection?.let { "¿Restablecer ${it.title}?" } ?: "¿Restablecer las preferencias?") },
            text = { Text(
                if (targetSection == null) "Vuelven todas las preferencias a sus valores de fábrica. Tus cuadernos y patrones no cambian."
                else "Vuelven los valores de fábrica de ${targetSection.title}. Las otras secciones, tus cuadernos y tus patrones no cambian.",
            ) },
            confirmButton = { TextButton(onClick = {
                resetTarget = null
                update { if (targetSection == null) TrazoSettings() else it.resetSection(targetSection) }
            }) { Text("Restablecer") } },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("Cancelar") } },
        )
    }
}

private fun PreferenceSection.icon(): ImageVector = when (this) {
    PreferenceSection.APPEARANCE -> TrazoIcons.Palette
    PreferenceSection.PEN -> TrazoIcons.Pen
    PreferenceSection.PAPER -> TrazoIcons.Paper
    PreferenceSection.TOOLS -> TrazoIcons.Eraser
    PreferenceSection.RECOGNITION -> TrazoIcons.Wand
    PreferenceSection.MATH -> TrazoIcons.Calculate
    PreferenceSection.GENERAL -> Icons.Filled.Settings
}

@Composable
private fun PreferenceLink(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Hint(summary)
            }
        }
    }
}

@Composable
private fun SettingsPage(
    section: PreferenceSection,
    s: TrazoSettings,
    stylus: Boolean,
    update: ((TrazoSettings) -> TrazoSettings) -> Unit,
    pick: (PrefColor) -> Unit,
) {
    when (section) {
        PreferenceSection.APPEARANCE -> PrefCard("Apariencia") {
            Text("Tema", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(ThemeMode.entries, s.themeMode, { it.label }, { m -> update { it.copy(themeMode = m) } })
            Text("Color de acento", style = MaterialTheme.typography.bodyMedium)
            AccentGrid(s, onPick = { p -> update { it.copy(accent = p.accent, accent2 = p.second) } }, onCustom = { pick(PrefColor.Accent) })
            Text("Tono de hoja", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(PaperTone.entries, s.paperTone, { it.label }, { t -> update { it.copy(paperTone = t) } })
            Hint("OLED usa negro puro. En hoja oscura las tintas oscuras se ven claras; el color guardado de cada trazo no cambia.")
        }
        PreferenceSection.PEN -> {
            PrefCard("Botones del lápiz") {
                Text(
                    if (stylus) "Lápiz detectado en este dispositivo." else "No se detectó lápiz: podés habilitar el dedo para escribir en General.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Botón lateral (mantener apretado)", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(SideButtonAction.entries, s.sideButton, { it.label }, { a -> update { it.copy(sideButton = a) } })
                Hint("Con «Goma», mientras tengas apretado el botón del S Pen borrás; al soltarlo seguís con la herramienta de antes.")
                Text("Botón en el aire (sin tocar la pantalla)", style = MaterialTheme.typography.bodyMedium)
                Hint(
                    "1 clic abre la ruleta de herramientas y colores; 2 clics alternan entre estos dos colores; " +
                        "3 clics prenden o apagan el modo formas. En Galaxy, si se abre Air command, desactivá " +
                        "«Abrir Air command con el botón del lápiz» en los ajustes del S Pen.",
                )
                Text("Colores del doble clic", style = MaterialTheme.typography.bodyMedium)
                Row5(s.swapColors, onPick = { pick(PrefColor.Swap(it)) }, label = "Cambiar el color del doble clic")
            }
            PrefCard("Colores rápidos") {
                Hint("Los cinco colores de la barra del editor. Tocá uno para cambiarlo.")
                Row5(s.quickColors, onPick = { pick(PrefColor.Quick(it)) })
                TextButton(onClick = { update { it.copy(quickColors = DEFAULT_QUICK_COLORS) } }) { Text("Volver a los colores de fábrica") }
            }
        }
        PreferenceSection.PAPER -> PrefCard("Cuadernos nuevos") {
            Text("Papel", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(Paper.entries, s.paper, ::paperName, { p -> update { it.copy(paper = p) } })
            Hint("Cada cuaderno puede cambiar su papel desde la barra del editor.")
        }
        PreferenceSection.TOOLS -> {
            PrefCard("Goma") {
                Text("Modo de la goma", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(EraserMode.entries, s.eraserMode, { it.label }, { m -> update { it.copy(eraserMode = m) } })
                LabeledSlider(
                    "Tamaño de la goma", "${s.eraserRadiusDp.toInt()} dp", s.eraserRadiusDp, 4f..40f,
                    onChange = { v -> update { it.copy(eraserRadiusDp = v.toInt().toFloat()) } },
                )
            }
            PrefCard("Herramientas de la barra") { ToolbarPreferences(s, update) }
            PrefCard("Entradas de la ruleta") { WheelPreferences(s, update) }
        }
        PreferenceSection.RECOGNITION -> PrefCard("Reconocimiento") {
            SwitchRow(
                "Auto-alinear lo reconocido", s.autoAlign, { on -> update { it.copy(autoAlign = on) } },
                subtitle = "Lo nuevo toma la altura y la línea de la forma más cercana del renglón; un ), ] o } se iguala con su apertura. " +
                    "También se cambia con 2 dedos apoyados y un tercero tocando 2 veces.",
            )
            LabeledSlider(
                "Pausa para convertir", "${s.holdMillis} ms", s.holdMillis.toFloat(), 100f..1000f,
                onChange = { v -> update { it.copy(holdMillis = Math.round(v / 100) * 100L) } }, steps = 8,
            )
            LabeledSlider(
                "Temblor tolerado", "${"%.0f".format(s.tremorRadiusDp)} dp", s.tremorRadiusDp, 2f..16f,
                onChange = { v -> update { it.copy(tremorRadiusDp = v.toInt().toFloat()) } },
            )
            SwitchRow("Mostrar el anillo de pausa", s.showHoldIndicator, { on -> update { it.copy(showHoldIndicator = on) } })
            SwitchRow(
                "Aprender de mis correcciones", s.learnFromCorrections, { on -> update { it.copy(learnFromCorrections = on) } },
                subtitle = "Al enseñar o corregir un gesto en la hoja, se guarda como ejemplo del patrón.",
            )
        }
        PreferenceSection.MATH -> PrefCard("Cuentas en la hoja") {
            SwitchRow(
                "Resolver las cuentas", s.mathNotes, { on -> update { it.copy(mathNotes = on) } },
                subtitle = "Al terminar un renglón con = aparece el resultado con tus patrones, usando los valores " +
                    "escritos en otros renglones de la misma hoja (x = 3); cada hoja es independiente. " +
                    "Con el lazo también podés elegir «Calcular».",
            )
            Text("Color de lo autocompletado", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(s.mathResultColor, selected = false, onClick = { pick(PrefColor.MathResult) }, size = 32.dp, description = "Cambiar el color de lo autocompletado")
                TextButton(onClick = { update { it.copy(mathResultColor = MathNotes.DEFAULT_RESULT_COLOR) } }) { Text("Volver al verde") }
            }
            Hint("Lo autocompletado no se guarda como tinta: se recalcula al cambiar la cuenta. «Calcular» con el lazo sí lo escribe en la hoja.")
        }
        PreferenceSection.GENERAL -> PrefCard("General") {
            SwitchRow("Vibrar al reemplazar un gesto", s.haptics, { on -> update { it.copy(haptics = on) } })
            SwitchRow(
                "Escribir también con el dedo", s.fingerDraws, { on -> update { it.copy(fingerDraws = on) } },
                subtitle = "Si está activo, el dedo deja de desplazar la hoja (usá dos dedos o la mano de la barra).",
            )
            SwitchRow(
                "Gestos con pausa del lápiz", s.gesturesEnabled, { on -> update { it.copy(gesturesEnabled = on) } },
                subtitle = "Sostené la punta quieta al terminar un gesto y se reemplaza por tu tinta. También se cambia con 3 dedos, o con 1 dedo apoyado y otro tocando 2 veces.",
            )
        }
    }
}

@Composable
private fun PrefCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Preajustes de acento (dos tonos por círculo) y el arcoíris para un acento libre. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentGrid(s: TrazoSettings, onPick: (AccentPreset) -> Unit, onCustom: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentPresets.forEach { p ->
            val selected = p.accent == s.accent && p.second == s.accent2
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.RadioButton, onClick = { onPick(p) })
                    .semantics {
                        contentDescription = "Acento ${p.name}"
                        this.selected = selected
                    }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(Color(p.accent), size = Size(size.width / 2f, size.height))
                        drawRect(Color(p.second), topLeft = Offset(size.width / 2f, 0f), size = Size(size.width / 2f, size.height))
                    }
                    if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text(p.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RainbowDot(onCustom, size = 40.dp, description = "Acento personalizado")
            Text("Otro", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Los colores rápidos, cada uno se cambia con un toque. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row5(colors: List<Int>, onPick: (Int) -> Unit, label: String = "Cambiar el color rápido") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEachIndexed { i, c ->
            ColorDot(c, selected = false, onClick = { onPick(i) }, size = 32.dp, description = "$label ${i + 1}")
        }
    }
    Spacer(Modifier.height(2.dp))
}
