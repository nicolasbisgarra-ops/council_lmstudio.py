package io.trazo.core.settings

/**
 * Colores predeterminados por símbolo/operador (id de patrón → ARGB), sin tipos de Android.
 * Un patrón con color aquí escribe siempre con él al reconocerse; sin entrada usa el color de la pluma.
 * Formato de texto: `id=AARRGGBB;id=AARRGGBB` (hexadecimal). Texto vacío = sin colores; null = valores por defecto.
 */
object SymbolColors {
    const val GREEN = 0xFF2E9E44.toInt()
    const val RED = 0xFFD32F2F.toInt()
    const val VIOLET = 0xFF7B3FC4.toInt()

    /** Ids de los patrones de suma, resta e integral. */
    const val PLUS_ID = "mas"
    const val MINUS_ID = "menos"
    const val INTEGRAL_ID = "integral"

    val defaults: Map<String, Int> = linkedMapOf(
        PLUS_ID to GREEN,
        MINUS_ID to RED,
        INTEGRAL_ID to VIOLET,
    )

    /**
     * Color fijo del patrón [patternId], o null. Acepta la clave exacta (lo que guarda el editor de patrones)
     * y, para los signos de la biblioteca (`preset-mas`), la clave corta de [defaults] (`mas`).
     */
    fun colorFor(colors: Map<String, Int>, patternId: String): Int? =
        colors[patternId] ?: colors[patternId.removePrefix(PRESET_PREFIX)].takeIf { patternId.startsWith(PRESET_PREFIX) }

    /** Igual que [io.trazo.core.PresetPatterns.PREFIX]. */
    private const val PRESET_PREFIX = "preset-"

    fun read(raw: String?): Map<String, Int> {
        if (raw == null) return defaults
        val out = linkedMapOf<String, Int>()
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val id = part.substring(0, i).trim()
            val argb = part.substring(i + 1).trim().toLongOrNull(16) ?: continue
            if (id.isEmpty() || argb < 0 || argb > 0xFFFFFFFFL) continue
            out[id] = argb.toInt()
        }
        return out
    }

    fun serialize(colors: Map<String, Int>): String =
        colors.entries
            .filter { it.key.isNotBlank() && ';' !in it.key && '=' !in it.key }
            .joinToString(";") { (id, argb) -> "${id.trim()}=${(argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()}" }
}
