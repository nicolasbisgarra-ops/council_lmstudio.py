package io.trazo.app.data

import android.content.Context
import android.content.SharedPreferences
import io.trazo.core.Brush
import io.trazo.core.DEFAULT_INK_COLOR
import io.trazo.core.Paper
import io.trazo.core.ShapeKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tema de la interfaz. OLED usa negro puro. */
enum class ThemeMode(val label: String) { SYSTEM("Sistema"), DARK("Oscuro"), LIGHT("Claro"), OLED("OLED") }

/** Color de la hoja: el que corresponde al tema de la interfaz, o siempre clara u oscura. */
enum class PaperTone(val label: String) { THEME("Tema"), LIGHT("Clara"), DARK("Oscura") }

/** Goma de área (borra lo que toca y parte los trazos) o de trazo (borra trazos enteros). */
enum class EraserMode(val label: String) { AREA("Área"), STROKE("Trazo") }

/** Qué hace el botón lateral del lápiz mientras se mantiene apretado. */
enum class SideButtonAction(val label: String) { ERASER("Goma"), LASSO("Lazo"), NONE("Nada") }

/** Acento "Mañana": ámbar con un segundo tono azul. */
const val DEFAULT_ACCENT: Int = 0xFF9A7C42.toInt()
const val DEFAULT_ACCENT_2: Int = 0xFF3D75E6.toInt()
const val DEFAULT_HIGHLIGHTER: Int = 0xFFFFE066.toInt()

/** Colores rápidos de la barra: tinta, azul, rojo, verde y violeta. */
val DEFAULT_QUICK_COLORS: List<Int> = listOf(
    DEFAULT_INK_COLOR, 0xFF1E4FA0.toInt(), 0xFFB3261E.toInt(), 0xFF2E7D32.toInt(), 0xFF6A3FA0.toInt(),
)

/** Ajustes globales. Valores iniciales de diseño, no medidos con S Pen real (ver DECISIONES.md). */
data class TrazoSettings(
    val holdMillis: Long = 1000L,
    val tremorRadiusDp: Float = 6f,
    val eraserRadiusDp: Float = 12f,
    val fingerDraws: Boolean = false,
    val gesturesEnabled: Boolean = true,
    val paper: Paper = Paper.LINES,
    val penColor: Int = DEFAULT_INK_COLOR,
    val penWidth: Float = 3f,
    val showHoldIndicator: Boolean = true,
    val haptics: Boolean = true,
    /** Cuando el usuario corrige o enseña un gesto en la hoja, se guarda como ejemplo del patrón. */
    val learnFromCorrections: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Int = DEFAULT_ACCENT,
    val accent2: Int = DEFAULT_ACCENT_2,
    /** Punta de la pluma. */
    val brush: Brush = Brush.PEN,
    val highlighterColor: Int = DEFAULT_HIGHLIGHTER,
    val highlighterWidth: Float = 14f,
    val shape: ShapeKind = ShapeKind.LINE,
    val eraserMode: EraserMode = EraserMode.AREA,
    val sideButton: SideButtonAction = SideButtonAction.ERASER,
    val quickColors: List<Int> = DEFAULT_QUICK_COLORS,
    val paperTone: PaperTone = PaperTone.THEME,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("trazo_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<TrazoSettings> = _settings.asStateFlow()

    private fun read() = TrazoSettings(
        holdMillis = prefs.getLong("holdMillis", 1000L).coerceIn(100L, 1000L), // rango de Preferencias
        tremorRadiusDp = prefs.getFloat("tremorRadiusDp", 6f),
        eraserRadiusDp = prefs.getFloat("eraserRadiusDp", 12f),
        fingerDraws = prefs.getBoolean("fingerDraws", false),
        gesturesEnabled = prefs.getBoolean("gesturesEnabled", true),
        paper = prefs.enum("paper", Paper.LINES),
        penColor = prefs.getInt("penColor", DEFAULT_INK_COLOR),
        penWidth = prefs.getFloat("penWidth", 3f),
        showHoldIndicator = prefs.getBoolean("showHoldIndicator", true),
        haptics = prefs.getBoolean("haptics", true),
        learnFromCorrections = prefs.getBoolean("learnFromCorrections", true),
        themeMode = prefs.enum("themeMode", ThemeMode.SYSTEM),
        accent = prefs.getInt("accent", DEFAULT_ACCENT),
        accent2 = prefs.getInt("accent2", DEFAULT_ACCENT_2),
        brush = prefs.enum("brush", Brush.PEN).takeIf { it != Brush.HIGHLIGHTER } ?: Brush.PEN,
        highlighterColor = prefs.getInt("highlighterColor", DEFAULT_HIGHLIGHTER),
        highlighterWidth = prefs.getFloat("highlighterWidth", 14f),
        shape = prefs.enum("shape", ShapeKind.LINE),
        eraserMode = prefs.enum("eraserMode", EraserMode.AREA),
        sideButton = prefs.enum("sideButton", SideButtonAction.ERASER),
        quickColors = prefs.getString("quickColors", null)?.let(::parseColors)?.takeIf { it.size == DEFAULT_QUICK_COLORS.size }
            ?: DEFAULT_QUICK_COLORS,
        paperTone = prefs.enum("paperTone", PaperTone.THEME),
    )

    fun update(transform: (TrazoSettings) -> TrazoSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putLong("holdMillis", s.holdMillis)
            .putFloat("tremorRadiusDp", s.tremorRadiusDp)
            .putFloat("eraserRadiusDp", s.eraserRadiusDp)
            .putBoolean("fingerDraws", s.fingerDraws)
            .putBoolean("gesturesEnabled", s.gesturesEnabled)
            .putString("paper", s.paper.name)
            .putInt("penColor", s.penColor)
            .putFloat("penWidth", s.penWidth)
            .putBoolean("showHoldIndicator", s.showHoldIndicator)
            .putBoolean("haptics", s.haptics)
            .putBoolean("learnFromCorrections", s.learnFromCorrections)
            .putString("themeMode", s.themeMode.name)
            .putInt("accent", s.accent)
            .putInt("accent2", s.accent2)
            .putString("brush", s.brush.name)
            .putInt("highlighterColor", s.highlighterColor)
            .putFloat("highlighterWidth", s.highlighterWidth)
            .putString("shape", s.shape.name)
            .putString("eraserMode", s.eraserMode.name)
            .putString("sideButton", s.sideButton.name)
            .putString("quickColors", s.quickColors.joinToString(",") { Integer.toHexString(it) })
            .putString("paperTone", s.paperTone.name)
            .apply()
        _settings.value = s
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
        getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun parseColors(csv: String): List<Int>? =
        runCatching { csv.split(",").map { it.trim().toLong(16).toInt() } }.getOrNull()
}
