package io.trazo.app.ui

/** Layout de la barra elegido por ancho disponible, no por orientación ni por tipo de dispositivo. */
internal enum class ToolbarLayoutMode {
    /** Teléfono o ventana estrecha: navegación arriba y herramientas en una fila desplazable abajo. */
    PHONE_COMPACT,
    /** Tableta: mantiene dos filas para que la navegación no compita con los controles de dibujo. */
    TABLET_COMPACT,
    /** Apaisado amplio: todos los controles caben en una sola fila. */
    WIDE_SINGLE_ROW,
}

/** Breakpoints de la barra del editor. Funciones puras para hacer verificable el comportamiento adaptativo. */
internal object ToolbarPreferences {
    const val SIDE_WIDTH_DP = 100
    const val TABLET_MIN_WIDTH_DP = 600f
    const val SINGLE_ROW_MIN_WIDTH_DP = 1100f

    fun layoutForWidth(widthDp: Float, fontScale: Float = 1f): ToolbarLayoutMode = when {
        !widthDp.isFinite() || widthDp < TABLET_MIN_WIDTH_DP -> ToolbarLayoutMode.PHONE_COMPACT
        // En 1.5x/2x el texto de los menús y el título ocupa más ancho real.
        widthDp < SINGLE_ROW_MIN_WIDTH_DP * (if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f) -> ToolbarLayoutMode.TABLET_COMPACT
        else -> ToolbarLayoutMode.WIDE_SINGLE_ROW
    }

    /** Las ventanas de ancho tablet usan blancos táctiles de 48 dp; el teléfono conserva 40 dp. */
    fun chromeButtonSizeDp(mode: ToolbarLayoutMode): Int =
        if (mode == ToolbarLayoutMode.PHONE_COMPACT) 40 else 48
}
