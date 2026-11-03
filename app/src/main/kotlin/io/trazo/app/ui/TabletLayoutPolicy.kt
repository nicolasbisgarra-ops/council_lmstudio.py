package io.trazo.app.ui

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Política determinista de distribución del editor. Los valores de entrada son dp del área
 * realmente medida por el editor (después de insets del sistema), no ancho físico ni orientación.
 *
 * No depende de Compose ni de Android. Se evalúa por cambio de restricciones o preferencias,
 * nunca dentro del callback de MotionEvent del S Pen.
 */
internal object TabletLayoutPolicy {
    const val TABLET_BREAKPOINT_DP = 600
    const val DEFAULT_PAGES_PANEL_DP = 200
    const val BASE_MIN_CANVAS_DP = 320

    data class Input(
        val availableWidthDp: Int,
        val fontScale: Float = 1f,
        val sideToolbarWidthDp: Int = 0,
        val pagesPanelWidthDp: Int = DEFAULT_PAGES_PANEL_DP,
        val patternRailWidthDp: Int = 0,
        val pagesRequested: Boolean = true,
        val patternRailRequested: Boolean = false,
    )

    data class Result(
        val tabletWindow: Boolean,
        val showFixedPages: Boolean,
        val showPatternRail: Boolean,
        val usableWidthDp: Int,
        val remainingCanvasWidthDp: Int,
        val requiredCanvasWidthDp: Int,
    )

    fun decide(input: Input): Result {
        val width = input.availableWidthDp.coerceAtLeast(0)
        val usable = (width - input.sideToolbarWidthDp.coerceAtLeast(0)).coerceAtLeast(0)
        val scale = if (input.fontScale.isFinite()) max(1f, input.fontScale) else 1f
        val minimum = (BASE_MIN_CANVAS_DP * scale).roundToInt()
        val tablet = width >= TABLET_BREAKPOINT_DP
        val pagesWidth = input.pagesPanelWidthDp.coerceAtLeast(0)
        val railWidth = input.patternRailWidthDp.coerceAtLeast(0)

        // Dar prioridad al panel de páginas. Cuando no entra sin estrangular el lienzo,
        // mantenerlo accesible como overlay/modal en lugar de consumir ancho de documento.
        val pages = tablet && input.pagesRequested &&
            pagesWidth > 0 && usable - pagesWidth >= minimum
        val afterPages = usable - if (pages) pagesWidth else 0
        val rail = tablet && input.patternRailRequested && railWidth > 0 &&
            afterPages - railWidth >= minimum
        val canvas = (afterPages - if (rail) railWidth else 0).coerceAtLeast(0)

        return Result(
            tabletWindow = tablet,
            showFixedPages = pages,
            showPatternRail = rail,
            usableWidthDp = usable,
            remainingCanvasWidthDp = canvas,
            requiredCanvasWidthDp = minimum,
        )
    }
}
