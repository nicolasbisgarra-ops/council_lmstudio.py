package io.trazo.app.data

import android.content.Context
import io.trazo.core.DEFAULT_INK_COLOR
import io.trazo.core.Paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("trazo_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<TrazoSettings> = _settings.asStateFlow()

    private fun read() = TrazoSettings(
        holdMillis = prefs.getLong("holdMillis", 1000L),
        tremorRadiusDp = prefs.getFloat("tremorRadiusDp", 6f),
        eraserRadiusDp = prefs.getFloat("eraserRadiusDp", 12f),
        fingerDraws = prefs.getBoolean("fingerDraws", false),
        gesturesEnabled = prefs.getBoolean("gesturesEnabled", true),
        paper = runCatching { Paper.valueOf(prefs.getString("paper", Paper.LINES.name)!!) }.getOrDefault(Paper.LINES),
        penColor = prefs.getInt("penColor", DEFAULT_INK_COLOR),
        penWidth = prefs.getFloat("penWidth", 3f),
        showHoldIndicator = prefs.getBoolean("showHoldIndicator", true),
        haptics = prefs.getBoolean("haptics", true),
        learnFromCorrections = prefs.getBoolean("learnFromCorrections", true),
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
            .apply()
        _settings.value = s
    }
}
