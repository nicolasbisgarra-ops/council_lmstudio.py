package io.trazo.app.ui

import io.trazo.app.data.TrazoSettings
import java.text.Normalizer
import java.util.Locale

/** Nombres del buscador y campos que restablece cada página de preferencias. */
internal enum class PreferenceSection(val title: String, val summary: String, val settingNames: List<String>) {
    APPEARANCE(
        "Apariencia", "Tema, acento y tono de hoja",
        listOf("Tema", "Color de acento", "Tono de hoja", "OLED"),
    ),
    PEN(
        "Lápiz", "Botones del lápiz y colores rápidos",
        listOf("Botón lateral", "Botón en el aire", "Colores rápidos", "Colores del doble clic"),
    ),
    PAPER("Hoja", "Papel de los cuadernos nuevos e imágenes", listOf("Papel", "Imágenes", "Ajuste de imagen en hoja clara", "Ajuste de imagen en hoja oscura")),
    TOOLS(
        "Herramientas", "Goma, barra y ruleta",
        listOf("Modo de la goma", "Tamaño de la goma", "Herramientas de la barra", "Tamaño de los botones", "Entradas de la ruleta", "Ordenar herramientas por arrastre"),
    ),
    RECOGNITION(
        "Reconocimiento", "Alineado, pausa, temblor y aprendizaje",
        listOf(
            "Auto-alinear lo reconocido", "Pausa para convertir (tiempo de mantener)", "Temblor tolerado",
            "Aprender de mis correcciones", "Mostrar el anillo de pausa (indicador)",
        ),
    ),
    MATH(
        "Cuentas", "Resultados al escribir = y su color",
        listOf("Resolver las cuentas (math notes)", "Color de lo autocompletado"),
    ),
    GENERAL(
        "General", "Vibración, dedo y gestos",
        listOf("Vibrar al reemplazar un gesto (vibración)", "Escribir también con el dedo", "Gestos con pausa del lápiz"),
    ),
    AI(
        "Modo IA", "Modelos, claves de API y revisión de la hoja",
        listOf(
            "Perfiles de modelos", "Ordenar modelos por arrastre", "Clave de API", "Obtener modelos", "Probar conexión", "Rigor de la revisión",
            "Calidad de la captura", "Instrucciones del modelo (prompt)", "Temperatura", "LM Studio, Ollama, OpenRouter",
        ),
    ),
    SYNC(
        "Sincronización", "Teléfono y tableta en la red de casa o Tailscale",
        listOf("Sincronizar con mis dispositivos", "Vincular un dispositivo (código)", "Nombre de este dispositivo", "Sincronizar ahora", "Tailscale"),
    ),
}

/** Sin distinguir mayúsculas ni tildes: «lapiz» también encuentra «Lápiz». */
internal fun String.preferenceSearchKey(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFD).replace("\\p{M}+".toRegex(), "").lowercase(Locale.ROOT)

internal fun TrazoSettings.resetSection(section: PreferenceSection): TrazoSettings {
    val defaults = TrazoSettings()
    return when (section) {
        PreferenceSection.APPEARANCE -> copy(
            themeMode = defaults.themeMode, accent = defaults.accent, accent2 = defaults.accent2,
            paperTone = defaults.paperTone,
        )
        PreferenceSection.PEN -> copy(
            sideButton = defaults.sideButton, quickColors = defaults.quickColors, swapColors = defaults.swapColors,
            penColor = defaults.penColor, penWidth = defaults.penWidth, brush = defaults.brush,
        )
        PreferenceSection.PAPER -> copy(
            paper = defaults.paper, imagePresetLight = defaults.imagePresetLight, imagePresetDark = defaults.imagePresetDark,
        )
        PreferenceSection.TOOLS -> copy(
            eraserMode = defaults.eraserMode, eraserRadiusDp = defaults.eraserRadiusDp,
            toolbarTools = defaults.toolbarTools, toolbarButtonSize = defaults.toolbarButtonSize,
            wheelItems = defaults.wheelItems, shape = defaults.shape,
        )
        PreferenceSection.RECOGNITION -> copy(
            holdMillis = defaults.holdMillis, tremorRadiusDp = defaults.tremorRadiusDp,
            learnFromCorrections = defaults.learnFromCorrections, showHoldIndicator = defaults.showHoldIndicator,
            autoAlign = defaults.autoAlign,
        )
        PreferenceSection.MATH -> copy(mathNotes = defaults.mathNotes, mathResultColor = defaults.mathResultColor)
        PreferenceSection.GENERAL -> copy(
            haptics = defaults.haptics, fingerDraws = defaults.fingerDraws, gesturesEnabled = defaults.gesturesEnabled,
        )
        // Los perfiles del Modo IA viven en su propio almacén (AiStore).
        PreferenceSection.AI -> this
        // Los dispositivos vinculados se quitan uno por uno («Olvidar»).
        PreferenceSection.SYNC -> this
    }
}
