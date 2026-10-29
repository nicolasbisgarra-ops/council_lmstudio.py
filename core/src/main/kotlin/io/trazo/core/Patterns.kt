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

/** Eje de una [Stretch]. */
@Serializable
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Zona elástica de la salida: entre [from] y [to] (fracciones del ancho —o del alto, según [axis]— de la
 * tinta de salida) la tinta se estira o se encoge para llegar hasta donde terminó el gesto; el resto
 * conserva su forma. Así la barra de la raíz cubre todo el radicando sin deformar el ✓.
 * [minFactor] limita cuánto puede encogerse la zona (fracción de su largo natural).
 */
@Serializable
data class Stretch(
    val axis: Axis = Axis.HORIZONTAL,
    val from: Float,
    val to: Float = 1f,
    val minFactor: Float = 0.2f,
) {
    companion object {
        /** Barra horizontal al final (derecha) de [ink], como la de la raíz; null si no hay una barra clara (larga y fina). */
        fun detectBar(ink: List<InkStroke>): Stretch? {
            if (ink.isEmpty()) return null
            val b = ink.bounds()
            if (b.width <= 0.5f || b.height <= 0.5f) return null
            val maxThickness = 0.12f * b.height
            var start = b.right
            var thickness = 0f
            for (i in 1..200) {
                val x = b.right - b.width * i / 200f
                val t = extentRightOf(ink, x)
                if (t > maxThickness) break
                start = x
                thickness = t
            }
            val length = b.right - start
            if (length < 0.3f * b.width || length < 4f * thickness) return null
            return Stretch(Axis.HORIZONTAL, from = (start - b.left) / b.width, to = 1f)
        }

        /** Alto de la tinta a la derecha de [x] (los segmentos que cruzan [x] se recortan). */
        private fun extentRightOf(ink: List<InkStroke>, x: Float): Float {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            fun add(y: Float) {
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            for (stroke in ink) {
                val pts = stroke.points
                for (i in pts.indices) {
                    val p = pts[i]
                    if (p.x >= x) add(p.y)
                    if (i > 0) {
                        val q = pts[i - 1]
                        if ((q.x < x) != (p.x < x)) add(q.y + (p.y - q.y) * (x - q.x) / (p.x - q.x))
                    }
                }
            }
            return if (hi >= lo) hi - lo else 0f
        }
    }
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
    /** Sección de la biblioteca ([PatternCategory]); null = patrones propios del usuario. */
    val category: String? = null,
    /** Barra elástica de la salida ([Stretch]); null = la salida se escala sin deformarse. */
    val stretch: Stretch? = null,
    /**
     * Uso en las cuentas: null = automático (por el nombre), "" = no se usa en cuentas,
     * una letra = incógnita con esa letra (por ejemplo «x»), sin importar el nombre.
     */
    val unknown: String? = null,
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

/** Secciones de la biblioteca de patrones, en el orden en que se muestran. */
object PatternCategory {
    const val NUMBERS = "Números"
    const val OPERATORS = "Operadores"
    const val GROUPING = "Paréntesis"
    const val CALCULUS = "Cálculo"
    const val GREEK = "Letras griegas"
    const val SETS = "Conjuntos y lógica"
    const val ARROWS = "Flechas y geometría"
    const val LETTERS = "Letras y funciones"
    const val SHAPES = "Formas"
    const val MINE = "Mis patrones"

    val ORDER = listOf(MINE, NUMBERS, OPERATORS, GROUPING, CALCULUS, GREEK, SETS, ARROWS, SHAPES, LETTERS)

    fun of(p: InkPattern): String = p.category ?: MINE
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
     * @param anchor rectángulo del gesto; la salida se centra en él. Si el patrón tiene una [InkPattern.stretch]
     *  y [elastic] es true, la salida se alinea a la izquierda (o arriba) del gesto y su zona elástica se
     *  estira o se encoge para terminar justo donde terminó el gesto.
     * @param style estilo con que se escribió el gesto: los trazos de salida con el color por defecto
     *  adoptan su color, su grosor (proporcional) y su punta; los que se enseñaron con otro color lo
     *  conservan. Un estilo de resaltador no se adopta.
     * @param elastic false = escala uniforme aunque haya zona elástica (inserción desde la biblioteca).
     * @return trazos nuevos (ids nuevos, mismo [InkStroke.groupId], [InkStroke.patternId] = patrón).
     */
    fun place(
        pattern: InkPattern,
        anchor: Bounds,
        ids: IdGenerator,
        startTime: Long = 0L,
        groupId: String = ids.next(),
        style: InkStyle? = null,
        elastic: Boolean = true,
    ): List<InkStroke> {
        val src = pattern.outputInk.bounds()
        val eps = 0.5f
        val zone = pattern.stretch?.takeIf { elastic }?.sanitized()
            ?.takeIf { (if (it.axis == Axis.HORIZONTAL) src.width else src.height) > eps }
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
                // Con zona elástica no hace falta: el largo lo pone la zona, no la escala.
                zone == null && pattern.sizing != OutputSizing.FIXED && raw * src.maxSide < 0.25f * anchor.maxSide ->
                    anchor.maxSide / src.maxSide.coerceAtLeast(eps)
                else -> raw
            }
        } * pattern.outputScale
        val placed = if (zone != null) {
            stretched(pattern.outputInk, src, anchor, s, zone)
        } else {
            val t = Affine.translate(-src.centerX, -src.centerY)
                .then(Affine(a = s, d = s))
                .then(Affine.translate(anchor.centerX, anchor.centerY))
            pattern.outputInk.map { it.transformed(t, scaleWidth = false) }
        }
        return placed.map { stroke ->
            stroke.adopting(style).copy(
                id = ids.next(),
                startTime = startTime,
                groupId = groupId,
                patternId = pattern.id,
            )
        }
    }

    /**
     * Escala uniforme [s] salvo en la zona elástica, que absorbe la diferencia hasta el borde del gesto:
     * la salida empieza en el borde izquierdo (o superior) de [anchor] y termina en el opuesto, salvo que la
     * zona ya no pueda encogerse más ([Stretch.minFactor]). En el otro eje se centra como siempre.
     */
    private fun stretched(ink: List<InkStroke>, src: Bounds, anchor: Bounds, s: Float, zone: Stretch): List<InkStroke> {
        val horizontal = zone.axis == Axis.HORIZONTAL
        val start = if (horizontal) src.left else src.top
        val natural = (if (horizontal) src.width else src.height) * s
        val target = if (horizontal) anchor.width else anchor.height
        val za = zone.from * natural
        val zb = zone.to * natural
        val len = zb - za
        val newLen = maxOf(len + target - natural, zone.minFactor * len)
        val k = if (len > 0f) newLen / len else 1f
        val delta = newLen - len
        fun along(v: Float): Float {
            val u = (v - start) * s
            return when {
                u <= za -> u
                u >= zb -> u + delta
                else -> za + (u - za) * k
            }
        }
        val origin = if (horizontal) anchor.left else anchor.top
        val crossFrom = if (horizontal) src.centerY else src.centerX
        val crossTo = if (horizontal) anchor.centerY else anchor.centerX
        return ink.map { stroke ->
            stroke.copy(points = stroke.points.map { p ->
                if (horizontal) p.copy(x = origin + along(p.x), y = (p.y - crossFrom) * s + crossTo)
                else p.copy(x = (p.x - crossFrom) * s + crossTo, y = origin + along(p.y))
            })
        }
    }

    /** Zona saneada (finita, dentro de [0, 1] y con largo); null si no sirve. */
    private fun Stretch.sanitized(): Stretch? {
        if (!from.isFinite() || !to.isFinite() || !minFactor.isFinite()) return null
        val a = from.coerceIn(0f, 1f)
        val b = to.coerceIn(0f, 1f)
        if (b - a < 0.01f) return null
        return copy(from = a, to = b, minFactor = minFactor.coerceIn(0f, 1f))
    }

    private fun InkStroke.adopting(style: InkStyle?): InkStroke {
        // El resaltador no se hereda: la salida es tinta.
        if (style == null || style.brush == Brush.HIGHLIGHTER || color != DEFAULT_INK_COLOR) return this
        return copy(
            color = style.color,
            width = (width * style.width / DEFAULT_INK_WIDTH).coerceIn(0.5f, 60f),
            brush = if (brush == Brush.PEN) style.brush else brush,
        )
    }

    private fun Float.orElse(other: Float) = if (isNaN()) other else this
}
