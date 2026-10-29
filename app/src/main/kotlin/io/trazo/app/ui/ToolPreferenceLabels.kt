package io.trazo.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import io.trazo.app.data.WheelEntry

internal fun EditorTool.preferenceLabel(): String = when (this) {
    EditorTool.PEN -> "Pluma"
    EditorTool.HIGHLIGHTER -> "Resaltador"
    EditorTool.ERASER -> "Goma"
    EditorTool.LASSO -> "Lazo"
    EditorTool.SHAPE -> "Formas"
    EditorTool.PAN -> "Mano: desplazar la hoja"
}

internal fun EditorTool.preferenceIcon(): ImageVector = when (this) {
    EditorTool.PEN -> TrazoIcons.Pen
    EditorTool.HIGHLIGHTER -> TrazoIcons.Highlighter
    EditorTool.ERASER -> TrazoIcons.Eraser
    EditorTool.LASSO -> TrazoIcons.Lasso
    EditorTool.SHAPE -> TrazoIcons.Shapes
    EditorTool.PAN -> TrazoIcons.Pan
}

internal fun WheelEntry.preferenceLabel(): String = tool?.preferenceLabel() ?: when (this) {
    WheelEntry.UNDO -> "Deshacer"
    else -> "Rehacer"
}

internal fun WheelEntry.preferenceIcon(): ImageVector = tool?.preferenceIcon() ?: when (this) {
    WheelEntry.UNDO -> TrazoIcons.Undo
    else -> TrazoIcons.Redo
}
