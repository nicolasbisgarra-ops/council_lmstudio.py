@file:OptIn(ExperimentalMaterial3Api::class)

package io.trazo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import io.trazo.core.Paper

/** Qué color libre se está eligiendo en las preferencias. */
private sealed interface PrefColor {
    data object Accent : PrefColor
    data class Quick(val slot: Int) : PrefColor
}

/** Preferencias agrupadas como en las apps de notas: apariencia, lápiz y gestos, S Pen y cuadernos. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = LocalContext.current.trazo.settings
    val s by store.settings.collectAsState()
    val stylus = remember { hasStylusDevice() }
    var picking by remember { mutableStateOf<PrefColor?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val update = store::update
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferencias") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PrefCard("Apariencia") {
                    Text("Tema", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(ThemeMode.entries, s.themeMode, { it.label }, { m -> update { it.copy(themeMode = m) } })
                    Text("Color de acento", style = MaterialTheme.typography.bodyMedium)
                    AccentGrid(s, onPick = { p -> update { it.copy(accent = p.accent, accent2 = p.second) } }, onCustom = { picking = PrefColor.Accent })
                    Text("Hoja", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(PaperTone.entries, s.paperTone, { it.label }, { t -> update { it.copy(paperTone = t) } })
                    Hint("En hoja oscura las tintas oscuras se ven claras; el color guardado de cada trazo no cambia.")
                }

                PrefCard("Lápiz y gestos") {
                    SwitchRow(
                        "Gestos con pausa del lápiz", s.gesturesEnabled, { on -> update { it.copy(gesturesEnabled = on) } },
                        subtitle = "Sostené la punta quieta al terminar un gesto y se reemplaza por tu tinta.",
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
                    SwitchRow("Vibrar al reemplazar un gesto", s.haptics, { on -> update { it.copy(haptics = on) } })
                    SwitchRow(
                        "Aprender de mis correcciones", s.learnFromCorrections, { on -> update { it.copy(learnFromCorrections = on) } },
                        subtitle = "Al enseñar o corregir un gesto en la hoja, se guarda como ejemplo del patrón.",
                    )
                }

                PrefCard("S Pen") {
                    Text(
                        if (stylus) "Lápiz detectado en este dispositivo." else "No se detectó lápiz: podés habilitar el dedo para escribir.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Botón lateral (mantener apretado)", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(SideButtonAction.entries, s.sideButton, { it.label }, { a -> update { it.copy(sideButton = a) } })
                    Hint("Con «Goma», mientras tengas apretado el botón del S Pen borrás; al soltarlo seguís con la herramienta de antes.")
                    Text("Goma", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(EraserMode.entries, s.eraserMode, { it.label }, { m -> update { it.copy(eraserMode = m) } })
                    LabeledSlider(
                        "Tamaño de la goma", "${s.eraserRadiusDp.toInt()} dp", s.eraserRadiusDp, 4f..40f,
                        onChange = { v -> update { it.copy(eraserRadiusDp = v.toInt().toFloat()) } },
                    )
                    SwitchRow(
                        "Escribir también con el dedo", s.fingerDraws, { on -> update { it.copy(fingerDraws = on) } },
                        subtitle = "Si está activo, el dedo deja de desplazar la hoja (usá dos dedos o la mano de la barra).",
                    )
                }

                PrefCard("Colores rápidos") {
                    Hint("Los cinco colores de la barra del editor. Tocá uno para cambiarlo.")
                    Row5(s.quickColors, onPick = { i -> picking = PrefColor.Quick(i) })
                    TextButton(onClick = { update { it.copy(quickColors = DEFAULT_QUICK_COLORS) } }) { Text("Volver a los colores de fábrica") }
                }

                PrefCard("Cuadernos nuevos") {
                    Text("Papel", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(Paper.entries, s.paper, ::paperName, { p -> update { it.copy(paper = p) } })
                    Hint("Cada cuaderno puede cambiar su papel desde la barra del editor.")
                }

                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restablecer preferencias")
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

    picking?.let { target ->
        ColorPickerDialog(
            initial = when (target) {
                PrefColor.Accent -> s.accent
                is PrefColor.Quick -> s.quickColors.getOrElse(target.slot) { s.penColor }
            },
            title = if (target == PrefColor.Accent) "Color de acento" else "Color rápido",
            onDismiss = { picking = null },
            onPick = { c ->
                picking = null
                when (target) {
                    PrefColor.Accent -> update { it.copy(accent = c) }
                    is PrefColor.Quick -> update { st -> st.copy(quickColors = st.quickColors.mapIndexed { i, q -> if (i == target.slot) c else q }) }
                }
            },
            suggestions = if (target == PrefColor.Accent) AccentPresets.map { it.accent } else PenPalette,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("¿Restablecer las preferencias?") },
            text = { Text("Vuelven los valores de fábrica (tema, colores, lápiz y goma). Tus cuadernos y patrones no cambian.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; update { TrazoSettings() } }) { Text("Restablecer") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
        )
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
private fun Row5(colors: List<Int>, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEachIndexed { i, c ->
            ColorDot(c, selected = false, onClick = { onPick(i) }, size = 32.dp, description = "Cambiar el color rápido ${i + 1}")
        }
    }
    Spacer(Modifier.height(2.dp))
}
