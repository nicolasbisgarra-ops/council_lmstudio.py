package io.trazo.app.data

import io.trazo.app.ui.EditorTool
import io.trazo.core.settings.ToolPreferences

enum class ToolbarButtonSize(val label: String, val dp: Int) {
    SMALL("Chico", 40), MEDIUM("Mediano", 48), LARGE("Grande", 56),
}

/** Entradas del aro de la ruleta. Los colores del centro se configuran aparte. */
enum class WheelEntry(val tool: EditorTool? = null) {
    PEN(EditorTool.PEN),
    HIGHLIGHTER(EditorTool.HIGHLIGHTER),
    ERASER(EditorTool.ERASER),
    LASSO(EditorTool.LASSO),
    SHAPE(EditorTool.SHAPE),
    PAN(EditorTool.PAN),
    UNDO,
    REDO,
}

val DEFAULT_TOOLBAR_TOOLS: List<EditorTool> = ToolPreferences.defaultToolbar.map { EditorTool.valueOf(it) }
val DEFAULT_WHEEL_ITEMS: List<WheelEntry> = ToolPreferences.defaultWheel.map { WheelEntry.valueOf(it) }

internal fun readToolbarTools(raw: String?): List<EditorTool> =
    ToolPreferences.readToolbar(raw).map { EditorTool.valueOf(it) }

internal fun readWheelItems(raw: String?): List<WheelEntry> =
    ToolPreferences.readWheel(raw).map { WheelEntry.valueOf(it) }

internal fun normalizeToolbarTools(tools: List<EditorTool>): List<EditorTool> =
    ToolPreferences.normalizeToolbar(tools.map { it.name }).map { EditorTool.valueOf(it) }

internal fun normalizeWheelItems(items: List<WheelEntry>): List<WheelEntry> =
    ToolPreferences.normalizeWheel(items.map { it.name }).map { WheelEntry.valueOf(it) }
