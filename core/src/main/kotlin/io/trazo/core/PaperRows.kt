package io.trazo.core

import kotlin.math.roundToInt

/**
 * Renglones del papel: con 1, 2 o 3 renglones elegidos, las formas que salen del renglón (paréntesis,
 * corchetes, ∫, Σ, √, fracciones…) se convierten ocupando justo esa cantidad, con las puntas en las líneas.
 * Las medidas son las del dibujo del papel (unidades de documento).
 */
object PaperRows {
    const val MAX_ROWS = 3

    /** Alto de un renglón del papel; null si no tiene líneas. */
    fun step(paper: Paper): Float? = when (paper) {
        Paper.LINES -> 64f
        Paper.GRID, Paper.DOTS -> 32f
        Paper.PLAIN -> null
    }

    /** Altura de la primera línea (las demás cada [step]). */
    fun offset(paper: Paper): Float = if (paper == Paper.LINES) 120f else 0f

    /** De fábrica: las formas que abarcan a otras salen del renglón. */
    fun exceedsRow(p: InkPattern): Boolean = p.exceedsRow ?: AutoAlign.isContainer(p)

    /**
     * Rectángulo para [InkReplacement.place] que deja [p] ocupando [rows] renglones, los más cercanos al
     * gesto; null si no corresponde (sin renglones, papel liso, tamaño fijo o por ancho, o el patrón no sale del renglón).
     */
    fun snap(p: InkPattern, gesture: Bounds, paper: Paper, rows: Int): Bounds? {
        if (rows !in 1..MAX_ROWS || !exceedsRow(p)) return null
        if (p.sizing == OutputSizing.FIXED || p.sizing == OutputSizing.MATCH_WIDTH) return null
        val step = step(paper) ?: return null
        val span = rows * step
        val off = offset(paper)
        val k = ((gesture.centerY - off - span / 2f) / step).roundToInt()
        val top = off + k * step
        val scale = p.outputScale.takeIf { it > 0f } ?: 1f
        val h = span / scale
        val cy = top + span / 2f
        // Sin zona elástica, el ancho acompaña al alto (así «ajustar al gesto» no lo achica); con zona, el largo es el del gesto.
        val w = if (p.stretch != null) gesture.width else {
            val core = PatternCore.bounds(p)
            if (core.height > 0.5f) core.width * h / core.height else gesture.width
        }
        return Bounds(gesture.centerX - w / 2f, cy - h / 2f, gesture.centerX + w / 2f, cy + h / 2f)
    }
}
