package io.trazo.core.settings

/**
 * Formato de las preferencias de barra y ruleta, sin tipos de Android ni de la interfaz.
 * Las claves son estables: no dependen del texto traducido ni de la posición de un enum.
 */
object ToolPreferences {
    const val MIN_WHEEL_ITEMS = 3
    const val MAX_WHEEL_ITEMS = 10

    val defaultToolbar: List<String> = listOf("PEN", "HIGHLIGHTER", "ERASER", "LASSO", "SHAPE", "PAN")
    val defaultWheel: List<String> = defaultToolbar + listOf("UNDO", "REDO")

    fun readToolbar(raw: String?): List<String> = normalizeToolbar(raw?.split(',').orEmpty())

    fun readWheel(raw: String?): List<String> = normalizeWheel(raw?.split(',').orEmpty())

    fun normalizeToolbar(keys: List<String>): List<String> {
        val tools = knownUnique(keys, defaultToolbar)
        if (tools.isEmpty()) return defaultToolbar
        return if ("PEN" in tools) tools else listOf("PEN") + tools
    }

    fun normalizeWheel(keys: List<String>): List<String> {
        val items = knownUnique(keys, defaultWheel).take(MAX_WHEEL_ITEMS)
        return items.takeIf { it.size >= MIN_WHEEL_ITEMS } ?: defaultWheel
    }

    fun serializeToolbar(keys: List<String>): String = normalizeToolbar(keys).joinToString(",")

    fun serializeWheel(keys: List<String>): String = normalizeWheel(keys).joinToString(",")

    private fun knownUnique(keys: List<String>, allowed: List<String>): List<String> =
        keys.map { it.trim() }.filter { it in allowed }.distinct()
}
