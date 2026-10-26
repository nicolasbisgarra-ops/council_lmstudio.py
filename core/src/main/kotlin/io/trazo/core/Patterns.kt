package io.trazo.core

import kotlinx.serialization.Serializable

/** Cómo se dimensiona la tinta de salida respecto del gesto que la invocó. */
@Serializable
enum class OutputSizing {
    /** Misma altura que el gesto (integral, raíz…). */
    MATCH_HEIGHT,
    /** Mismo ancho que el gesto (barra de fracción…). */
    MATCH_WIDTH,
    /** Cabe dentro del rectángulo del gesto. */
    FIT_GESTURE,
    /** Tamaño fijo en unidades de documento ([InkPattern.fixedWidth] × [InkPattern.fixedHeight]). */
    FIXED,
}

/**
 * Patrón enseñado por el usuario: dos dibujos independientes.
 *  - [gestureExamples]: uno o más ejemplos del gesto a reconocer (cada uno es una lista de trazos).
 *  - [outputInk]: la tinta manuscrita que reemplaza al gesto.
 * Ambos son trazos vectoriales; nunca texto ni imágenes.
 */
@Serializable
data class InkPattern(
    val id: String,
    val name: String,
    val gestureExamples: List<List<InkStroke>>,
    val outputInk: List<InkStroke>,
    val sizing: OutputSizing = OutputSizing.MATCH_HEIGHT,
    val outputScale: Float = 1f,
    val fixedWidth: Float = 120f,
    val fixedHeight: Float = 160f,
    val minScore: Float = DEFAULT_MIN_SCORE,
    /** Pausa propia del patrón; null = usar la global. */
    val holdMillis: Long? = null,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val updatedAt: Long = 0L,
) {
    init {
        require(gestureExamples.isNotEmpty() && gestureExamples.all { it.isNotEmpty() }) { "Pattern needs a gesture" }
        require(outputInk.isNotEmpty()) { "Pattern needs output ink" }
    }

    companion object {
        const val DEFAULT_MIN_SCORE = 0.80f
        const val MAX_EXAMPLES = 8
    }
}

/** Agrega el gesto (normalizado) como ejemplo. Si se supera [maxExamples], conserva siempre el primero (el enseñado originalmente) y los más recientes. */
fun InkPattern.withGestureExample(gesture: List<InkStroke>, maxExamples: Int = InkPattern.MAX_EXAMPLES, now: Long = 0L): InkPattern {
    require(maxExamples >= 1)
    val all = gestureExamples + listOf(GestureExamples.normalize(gesture))
    val kept = if (all.size <= maxExamples) all else listOf(all.first()) + all.takeLast(maxExamples - 1)
    return copy(gestureExamples = kept, updatedAt = now)
}

object GestureExamples {
    /** Copia normalizada de un gesto para guardarlo como ejemplo: ids "ex-1", "ex-2"…, startTime relativo al primer trazo (el primero = 0), groupId = null, patternId = null, color/width/presión/tiempos de puntos conservados. Se traslada para que el borde superior izquierdo quede en (margin, margin); si el lado mayor supera box - 2*margin se escala uniformemente para caber (sin cambiar width). */
    fun normalize(gesture: List<InkStroke>, box: Float = 400f, margin: Float = 20f): List<InkStroke> {
        require(gesture.isNotEmpty()) { "Empty gesture" }
        val b = gesture.bounds()
        val room = box - 2 * margin
        val s = if (b.maxSide > room && room > 0f) room / b.maxSide else 1f
        val t = Affine.translate(-b.left, -b.top)
            .then(Affine(a = s, d = s))
            .then(Affine.translate(margin, margin))
        val t0 = gesture.first().startTime
        return gesture.mapIndexed { i, stroke ->
            stroke.transformed(t, scaleWidth = false).copy(
                id = "ex-${i + 1}",
                startTime = stroke.startTime - t0,
                groupId = null,
                patternId = null,
            )
        }
    }
}

/** Inserta la salida de un patrón como copia profunda transformada de sus trazos. */
object InkReplacement {
    /**
     * @param anchor rectángulo del gesto; la salida se centra en él.
     * @return trazos nuevos (ids nuevos, mismo [InkStroke.groupId], [InkStroke.patternId] = patrón).
     */
    fun place(
        pattern: InkPattern,
        anchor: Bounds,
        ids: IdGenerator,
        startTime: Long = 0L,
        groupId: String = ids.next(),
    ): List<InkStroke> {
        val src = pattern.outputInk.bounds()
        val eps = 0.5f
        val scaleH = if (src.height > eps) anchor.height / src.height else Float.NaN
        val scaleW = if (src.width > eps) anchor.width / src.width else Float.NaN
        val s = when (pattern.sizing) {
            OutputSizing.MATCH_HEIGHT -> scaleH.orElse(scaleW)
            OutputSizing.MATCH_WIDTH -> scaleW.orElse(scaleH)
            OutputSizing.FIT_GESTURE -> minOf(scaleW.orElse(scaleH), scaleH.orElse(scaleW))
            OutputSizing.FIXED -> listOfNotNull(
                if (src.width > eps) pattern.fixedWidth / src.width else null,
                if (src.height > eps) pattern.fixedHeight / src.height else null,
            ).minOrNull() ?: 1f
        }.let { raw ->
            when {
                raw.isNaN() || raw <= 0f -> 1f
                // Un gesto casi plano (p. ej. una línea) no debe encoger la salida hasta desaparecer.
                pattern.sizing != OutputSizing.FIXED && raw * src.maxSide < 0.25f * anchor.maxSide ->
                    anchor.maxSide / src.maxSide.coerceAtLeast(eps)
                else -> raw
            }
        } * pattern.outputScale
        val t = Affine.translate(-src.centerX, -src.centerY)
            .then(Affine(a = s, d = s))
            .then(Affine.translate(anchor.centerX, anchor.centerY))
        return pattern.outputInk.map { stroke ->
            stroke.transformed(t, scaleWidth = false).copy(
                id = ids.next(),
                startTime = startTime,
                groupId = groupId,
                patternId = pattern.id,
            )
        }
    }

    private fun Float.orElse(other: Float) = if (isNaN()) other else this
}
