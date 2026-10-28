package io.trazo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.trazo.app.data.EraserMode
import io.trazo.app.data.PaperTone
import io.trazo.app.data.SideButtonAction
import io.trazo.app.data.TrazoSettings
import io.trazo.core.Paper
import io.trazo.core.ShapeKind
import io.trazo.core.Brush as InkBrush

/** A qué color se aplica el selector libre. */
sealed interface ColorTarget {
    data object Pen : ColorTarget
    data object Highlighter : ColorTarget
    /** Recolorea los trazos elegidos con el lazo. */
    data object Selection : ColorTarget
    /** Reemplaza un color rápido de la barra (toque largo sobre él). */
    data class Quick(val slot: Int) : ColorTarget
}

/** Paneles de la barra: uno por herramienta con ajustes, más el de la hoja. */
private enum class BarPanel { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, PAPER }

private fun EditorTool.panel(): BarPanel? = when (this) {
    EditorTool.PEN -> BarPanel.PEN
    EditorTool.HIGHLIGHTER -> BarPanel.HIGHLIGHTER
    EditorTool.ERASER -> BarPanel.ERASER
    EditorTool.LASSO -> BarPanel.LASSO
    EditorTool.SHAPE -> BarPanel.SHAPE
    EditorTool.PAN -> null
}

/** Acciones del editor que la barra dispara; el estado vive en [EditorViewModel] y en los ajustes. */
class EditorActions(
    val onBack: () -> Unit,
    val onRename: () -> Unit,
    val onInsert: () -> Unit,
    val onZoom: (Float) -> Unit,
    val onPickColor: (ColorTarget) -> Unit,
    val onQuickColor: (Int) -> Unit,
    val onClearPage: () -> Unit,
    val onDeletePage: () -> Unit,
    val onPdf: () -> Unit,
    val onPng: () -> Unit,
    val onPatterns: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * Barra superior del editor, al estilo de las apps de notas: volver, título y páginas, deshacer y
 * rehacer siempre a mano, herramientas con ícono, colores rápidos, gestos, patrones y zoom.
 * En una sola fila necesita unos 1100 dp (tablet apaisada); por debajo va en dos filas, con las
 * herramientas centradas debajo, en tantos renglones como hagan falta (teléfono).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorTopBar(
    vm: EditorViewModel,
    title: String,
    settings: TrazoSettings,
    update: ((TrazoSettings) -> TrazoSettings) -> Unit,
    actions: EditorActions,
) {
    var panel by remember { mutableStateOf<BarPanel?>(null) }
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer, shadowElevation = 3.dp) {
        BoxWithConstraints(Modifier.statusBarsPadding()) {
            val compact = maxWidth < 1100.dp
            val nav: @Composable () -> Unit = { NavGroup(vm, title, actions, Modifier.widthIn(max = if (compact) 400.dp else 240.dp)) }
            val history: @Composable () -> Unit = {
                ToolButton(TrazoIcons.Undo, "Deshacer", onClick = vm::undo, enabled = vm.canUndo)
                ToolButton(TrazoIcons.Redo, "Rehacer", onClick = vm::redo, enabled = vm.canRedo)
            }
            // Grupos de la barra: en el teléfono cada grupo baja de renglón entero, sin partirse.
            val drawGroup: @Composable () -> Unit = {
                ToolsGroup(vm, settings, update, actions, panel) { panel = it }
                ToolButton(
                    TrazoIcons.Wand, if (settings.gesturesEnabled) "Gestos activados" else "Gestos desactivados",
                    onClick = { update { it.copy(gesturesEnabled = !it.gesturesEnabled) } },
                    selected = settings.gesturesEnabled,
                )
                ToolButton(TrazoIcons.Patterns, "Insertar patrón", onClick = actions.onInsert)
            }
            val colorGroup: @Composable () -> Unit = { ColorStrip(vm, settings, actions) }
            val viewGroup: @Composable () -> Unit = {
                ZoomGroup(vm, actions)
                Box {
                    ToolButton(TrazoIcons.Paper, "Hoja", onClick = { panel = BarPanel.PAPER }, selected = panel == BarPanel.PAPER)
                    PanelMenu(panel == BarPanel.PAPER, { panel = null }) { PaperPanel(vm, settings, update) }
                }
            }
            val groups = listOf(drawGroup, colorGroup, viewGroup)
            if (compact) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { nav() }
                        history()
                        EditorMenu(vm, actions)
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        groups.forEach { group ->
                            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) { group() }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    nav()
                    history()
                    BarDivider()
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        groups.forEachIndexed { i, group ->
                            if (i > 0) BarDivider()
                            group()
                        }
                    }
                    EditorMenu(vm, actions)
                }
            }
        }
    }
}

@Composable
private fun NavGroup(vm: EditorViewModel, title: String, actions: EditorActions, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Volver", onClick = actions.onBack)
        Column(
            Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 6.dp)
                .clickable(role = Role.Button, onClickLabel = "Renombrar", onClick = actions.onRename),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Página ${vm.pageIndex + 1} de ${vm.pageCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        ToolButton(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior",
            onClick = { vm.goToPage(vm.pageIndex - 1) }, enabled = vm.pageIndex > 0, size = 36.dp,
        )
        if (vm.pageIndex < vm.pageCount - 1) {
            ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Página siguiente", onClick = { vm.goToPage(vm.pageIndex + 1) }, size = 36.dp)
        } else {
            ToolButton(Icons.Filled.Add, "Nueva página", onClick = vm::addPage, size = 36.dp)
        }
    }
}

/** Pluma, resaltador, goma, lazo, formas y mano. Tocar la herramienta elegida abre sus ajustes. */
@Composable
private fun ToolsGroup(
    vm: EditorViewModel,
    settings: TrazoSettings,
    update: ((TrazoSettings) -> TrazoSettings) -> Unit,
    actions: EditorActions,
    panel: BarPanel?,
    setPanel: (BarPanel?) -> Unit,
) {
    val tools = listOf(
        Triple(EditorTool.PEN, TrazoIcons.Pen, "Pluma"),
        Triple(EditorTool.HIGHLIGHTER, TrazoIcons.Highlighter, "Resaltador"),
        Triple(EditorTool.ERASER, TrazoIcons.Eraser, "Goma"),
        Triple(EditorTool.LASSO, TrazoIcons.Lasso, "Lazo"),
        Triple(EditorTool.SHAPE, TrazoIcons.Shapes, "Formas"),
        Triple(EditorTool.PAN, TrazoIcons.Pan, "Mano: desplazar la hoja"),
    )
    tools.forEach { (tool, icon, label) ->
        val own = tool.panel()
        Box {
            ToolButton(
                icon, label,
                onClick = { if (vm.editorTool == tool && own != null) setPanel(own) else vm.selectTool(tool) },
                onLongClick = { vm.selectTool(tool); if (own != null) setPanel(own) },
                selected = vm.editorTool == tool,
            )
            if (own != null) {
                PanelMenu(panel == own, { setPanel(null) }) {
                    when (own) {
                        BarPanel.PEN -> PenPanel(settings, update, actions)
                        BarPanel.HIGHLIGHTER -> HighlighterPanel(settings, update, actions)
                        BarPanel.ERASER -> EraserPanel(settings, update, onClear = { setPanel(null); actions.onClearPage() })
                        BarPanel.LASSO -> LassoPanel(onSelectAll = { setPanel(null); vm.selectAll() })
                        BarPanel.SHAPE -> ShapePanel(settings, update)
                        BarPanel.PAPER -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) { content() }
}

/** Colores rápidos: tocar elige (o recolorea la selección); mantener apretado cambia ese color. */
@Composable
private fun ColorStrip(vm: EditorViewModel, settings: TrazoSettings, actions: EditorActions) {
    val highlighter = vm.editorTool == EditorTool.HIGHLIGHTER
    val colors = if (highlighter) HighlighterPalette.take(settings.quickColors.size) else settings.quickColors
    val current = if (highlighter) settings.highlighterColor else settings.penColor
    colors.forEachIndexed { i, c ->
        ColorDot(
            c,
            selected = c == current,
            onClick = { actions.onQuickColor(c) },
            size = 22.dp,
            onLongClick = if (highlighter) null else ({ actions.onPickColor(ColorTarget.Quick(i)) }),
        )
    }
    RainbowDot(
        onClick = { actions.onPickColor(if (highlighter) ColorTarget.Highlighter else ColorTarget.Pen) },
        size = 22.dp,
        description = "Otro color",
    )
}

@Composable
private fun ZoomGroup(vm: EditorViewModel, actions: EditorActions) {
    ToolButton(TrazoIcons.ZoomOut, "Alejar", onClick = { actions.onZoom(1f / 1.25f) }, size = 36.dp)
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = vm::fitToPage)
            .semantics { contentDescription = "Zoom ${vm.zoomPercent} %. Ajustar a la página" },
        contentAlignment = Alignment.Center,
    ) {
        Text("${vm.zoomPercent} %", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
    ToolButton(TrazoIcons.ZoomIn, "Acercar", onClick = { actions.onZoom(1.25f) }, size = 36.dp)
}

// ------------------------------------------------------------------ paneles

private val PenBrushes = listOf(
    InkBrush.PEN to "Pluma",
    InkBrush.BALLPOINT to "Bolígrafo",
    InkBrush.CALLIGRAPHY to "Caligrafía",
    InkBrush.PENCIL to "Lápiz",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenPanel(settings: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit, actions: EditorActions) {
    ToolPanel("Pluma") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PenBrushes.forEach { (b, label) ->
                ChoiceChip(label, settings.brush == b, onClick = { update { it.copy(brush = b) } })
            }
        }
        BrushSample(settings.brush, settings.penColor, settings.penWidth, Modifier.fillMaxWidth().height(40.dp))
        LabeledSlider(
            "Grosor", "%.1f".format(settings.penWidth), settings.penWidth, 1f..12f,
            onChange = { w -> update { it.copy(penWidth = (w * 2f).toInt() / 2f) } },
        )
        SectionLabel("Color")
        ColorGrid(PenPalette, settings.penColor, onPick = { c -> update { it.copy(penColor = c) } }, onCustom = { actions.onPickColor(ColorTarget.Pen) })
    }
}

@Composable
private fun HighlighterPanel(settings: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit, actions: EditorActions) {
    ToolPanel("Resaltador") {
        BrushSample(InkBrush.HIGHLIGHTER, settings.highlighterColor, settings.highlighterWidth, Modifier.fillMaxWidth().height(40.dp))
        LabeledSlider(
            "Grosor", "%.0f".format(settings.highlighterWidth), settings.highlighterWidth, 6f..30f,
            onChange = { w -> update { it.copy(highlighterWidth = w.toInt().toFloat()) } },
        )
        SectionLabel("Color")
        ColorGrid(
            HighlighterPalette, settings.highlighterColor,
            onPick = { c -> update { it.copy(highlighterColor = c) } },
            onCustom = { actions.onPickColor(ColorTarget.Highlighter) },
        )
        Hint("El resaltador queda debajo de la tinta y nunca se toma como gesto.")
    }
}

@Composable
private fun EraserPanel(settings: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit, onClear: () -> Unit) {
    ToolPanel("Goma") {
        ChoiceRow(EraserMode.entries, settings.eraserMode, { it.label }, { m -> update { it.copy(eraserMode = m) } })
        Hint(
            if (settings.eraserMode == EraserMode.AREA) "Área: borra solo lo que toca, y parte los trazos."
            else "Trazo: cada trazo que tocás se borra entero.",
        )
        LabeledSlider(
            "Tamaño", "${settings.eraserRadiusDp.toInt()} dp", settings.eraserRadiusDp, 4f..40f,
            onChange = { r -> update { it.copy(eraserRadiusDp = r.toInt().toFloat()) } },
        )
        SectionLabel("Botón lateral del lápiz")
        ChoiceRow(SideButtonAction.entries, settings.sideButton, { it.label }, { a -> update { it.copy(sideButton = a) } })
        Hint("Mantené apretado el botón del S Pen para borrar sin cambiar de herramienta; al soltarlo seguís escribiendo. El extremo goma del lápiz también borra.")
        TextButton(onClick = onClear) {
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Borrar toda la página")
        }
    }
}

@Composable
private fun LassoPanel(onSelectAll: () -> Unit) {
    ToolPanel("Lazo") {
        Hint("Rodeá trazos para elegirlos. Después arrastralos, cambiales el tamaño desde la esquina o usá la barra de selección.")
        TextButton(onClick = onSelectAll) {
            Icon(TrazoIcons.SelectAll, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Seleccionar todo")
        }
    }
}

private val ShapeOptions = listOf(
    Triple(ShapeKind.LINE, TrazoIcons.ShapeLine, "Línea"),
    Triple(ShapeKind.ARROW, TrazoIcons.ShapeArrow, "Flecha"),
    Triple(ShapeKind.RECTANGLE, TrazoIcons.ShapeRect, "Rectángulo"),
    Triple(ShapeKind.ELLIPSE, TrazoIcons.ShapeEllipse, "Elipse"),
    Triple(ShapeKind.TRIANGLE, TrazoIcons.ShapeTriangle, "Triángulo"),
    Triple(ShapeKind.AXES, TrazoIcons.ShapeAxes, "Ejes"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShapePanel(settings: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit) {
    ToolPanel("Formas") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShapeOptions.forEach { (kind, icon, label) ->
                ChoiceChip(
                    label, settings.shape == kind, onClick = { update { it.copy(shape = kind) } },
                    leading = { Icon(icon, null, Modifier.size(18.dp)) },
                )
            }
        }
        Hint("Arrastrá desde un extremo hasta el otro. Usan el color y el grosor de la pluma.")
    }
}

@Composable
private fun PaperPanel(vm: EditorViewModel, settings: TrazoSettings, update: ((TrazoSettings) -> TrazoSettings) -> Unit) {
    ToolPanel("Hoja") {
        SectionLabel("Fondo de este cuaderno")
        ChoiceRow(Paper.entries, vm.paper, ::paperName, vm::setPaper)
        SectionLabel("Tono")
        ChoiceRow(PaperTone.entries, settings.paperTone, { it.label }, { t -> update { it.copy(paperTone = t) } })
        Hint("En hoja oscura las tintas oscuras se ven claras; el color guardado no cambia.")
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ------------------------------------------------------------------ menú

@Composable
private fun EditorMenu(vm: EditorViewModel, actions: EditorActions) {
    var open by remember { mutableStateOf(false) }
    fun item(text: String, icon: ImageVector?, enabled: Boolean = true, action: () -> Unit): @Composable () -> Unit = {
        DropdownMenuItem(
            text = { Text(text) },
            leadingIcon = icon?.let { { Icon(it, null, Modifier.size(20.dp)) } },
            enabled = enabled,
            onClick = { open = false; action() },
        )
    }
    Box {
        ToolButton(Icons.Filled.MoreVert, "Más opciones", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
            item("Nueva página", TrazoIcons.Pages, action = vm::addPage)()
            item("Duplicar página", TrazoIcons.Duplicate, action = vm::duplicatePage)()
            item("Mover página antes", Icons.AutoMirrored.Filled.KeyboardArrowLeft, vm.pageIndex > 0) { vm.movePage(-1) }()
            item("Mover página después", Icons.AutoMirrored.Filled.KeyboardArrowRight, vm.pageIndex < vm.pageCount - 1) { vm.movePage(1) }()
            item("Eliminar página…", Icons.Filled.Delete, vm.pageCount > 1, actions.onDeletePage)()
            HorizontalDivider()
            item("Seleccionar todo", TrazoIcons.SelectAll, action = vm::selectAll)()
            item("Borrar toda la página", TrazoIcons.Eraser, !vm.pageIsEmpty, actions.onClearPage)()
            item("Ajustar a la página", TrazoIcons.Fit, action = vm::fitToPage)()
            HorizontalDivider()
            item("Exportar cuaderno como PDF…", TrazoIcons.Export, action = actions.onPdf)()
            item("Exportar página como PNG…", TrazoIcons.Export, action = actions.onPng)()
            HorizontalDivider()
            item("Biblioteca de patrones", TrazoIcons.Patterns, action = actions.onPatterns)()
            item("Preferencias", Icons.Filled.Settings, action = actions.onSettings)()
        }
    }
}

// ------------------------------------------------------------------ selección

/** Barra flotante de la selección: girar, tamaño, duplicar, color y borrar. */
@Composable
fun SelectionPill(vm: EditorViewModel, onCustomColor: () -> Unit, modifier: Modifier = Modifier) {
    var colors by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Filled.Close, "Quitar selección", onClick = { vm.controller.clearSelection() })
            BarDivider()
            ToolButton(
                TrazoIcons.Rotate, "Girar −15°", onClick = { vm.controller.rotateSelection(-15f) },
                modifier = Modifier.graphicsLayer(scaleX = -1f),
            )
            ToolButton(TrazoIcons.Rotate, "Girar +15°", onClick = { vm.controller.rotateSelection(15f) })
            ToolButton(TrazoIcons.Shrink, "Achicar", onClick = { vm.controller.scaleSelection(0.85f) })
            ToolButton(TrazoIcons.Grow, "Agrandar", onClick = { vm.controller.scaleSelection(1.15f) })
            ToolButton(TrazoIcons.Duplicate, "Duplicar", onClick = vm::duplicateSelection)
            Box {
                ToolButton(TrazoIcons.Palette, "Color de la selección", onClick = { colors = true }, selected = colors)
                PanelMenu(colors, { colors = false }) {
                    ToolPanel("Color de la selección") {
                        ColorGrid(
                            PenPalette, selected = vm.selectionColor ?: 0,
                            onPick = { c -> vm.recolorSelection(c); colors = false },
                            onCustom = { colors = false; onCustomColor() },
                        )
                    }
                }
            }
            ToolButton(Icons.Filled.Delete, "Borrar selección", onClick = { vm.controller.deleteSelection() })
        }
    }
}
