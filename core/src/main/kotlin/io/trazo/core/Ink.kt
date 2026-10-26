package io.trazo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Tinta por defecto: verde-grafito del prototipo v2. ARGB. */
const val DEFAULT_INK_COLOR: Int = 0xFF263B34.toInt()
const val DEFAULT_INK_WIDTH: Float = 3f

/**
 * Muestra del lápiz en coordenadas de documento (independientes de píxeles, densidad y zoom).
 * [timeMs] es relativo al inicio del trazo.
 */
@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    @SerialName("p") val pressure: Float = 1f,
    @SerialName("t") val timeMs: Long = 0L,
)

/**
 * Trazo vectorial editable. Toda marca sobre la hoja es un [InkStroke]: no existen
 * glifos tipográficos ni imágenes en el modelo del documento.
 *
 * @param startTime reloj monotónico de la sesión (o epoch al cargar); solo se usa para agrupar gestos.
 * @param groupId une los trazos insertados por un patrón para moverlos juntos.
 * @param patternId patrón del que proviene la tinta (null = escrita a mano en la hoja).
 */
@Serializable
data class InkStroke(
    val id: String,
    val points: List<InkPoint>,
    val color: Int = DEFAULT_INK_COLOR,
    val width: Float = DEFAULT_INK_WIDTH,
    val startTime: Long = 0L,
    val groupId: String? = null,
    val patternId: String? = null,
) {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
    }

    val endTime: Long get() = startTime + points.last().timeMs

    val bounds: Bounds get() = Bounds.of(points)

    val length: Float
        get() {
            var total = 0f
            for (i in 1 until points.size) total += dist(points[i - 1], points[i])
            return total
        }
}

@Serializable
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val maxSide: Float get() = max(width, height)

    fun union(o: Bounds) = Bounds(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun inflate(d: Float) = Bounds(left - d, top - d, right + d, bottom + d)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** Distancia entre los bordes de dos rectángulos (0 si se tocan o solapan). */
    fun gapTo(o: Bounds): Float {
        val dx = max(0f, max(o.left - right, left - o.right))
        val dy = max(0f, max(o.top - bottom, top - o.bottom))
        return hypot(dx, dy)
    }

    companion object {
        fun of(points: Iterable<InkPoint>): Bounds {
            var l = Float.POSITIVE_INFINITY
            var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY
            var b = Float.NEGATIVE_INFINITY
            for (p in points) {
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
            require(l.isFinite()) { "No points" }
            return Bounds(l, t, r, b)
        }
    }
}

fun List<InkStroke>.bounds(): Bounds = map { it.bounds }.reduce(Bounds::union)

fun dist(a: InkPoint, b: InkPoint): Float = hypot(a.x - b.x, a.y - b.y)

/** Transformación afín 2D (a c tx / b d ty). */
data class Affine(
    val a: Float = 1f, val b: Float = 0f,
    val c: Float = 0f, val d: Float = 1f,
    val tx: Float = 0f, val ty: Float = 0f,
) {
    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** Factor de escala medio (para ajustar grosores). */
    val scale: Float get() = kotlin.math.sqrt(kotlin.math.abs(a * d - b * c))

    /** Aplica primero `this` y después [next]. */
    fun then(next: Affine) = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        tx = next.a * tx + next.c * ty + next.tx,
        ty = next.b * tx + next.d * ty + next.ty,
    )

    companion object {
        val IDENTITY = Affine()
        fun translate(dx: Float, dy: Float) = Affine(tx = dx, ty = dy)
        fun scale(s: Float, cx: Float, cy: Float) = Affine(a = s, d = s, tx = cx - s * cx, ty = cy - s * cy)
        fun rotate(degrees: Float, cx: Float, cy: Float): Affine {
            val r = Math.toRadians(degrees.toDouble())
            val cos = kotlin.math.cos(r).toFloat()
            val sin = kotlin.math.sin(r).toFloat()
            return Affine(a = cos, b = sin, c = -sin, d = cos,
                tx = cx - cos * cx + sin * cy, ty = cy - sin * cx - cos * cy)
        }
    }
}

/** Copia el trazo transformando sus puntos; conserva presión, tiempos e id. */
fun InkStroke.transformed(t: Affine, scaleWidth: Boolean = true): InkStroke = copy(
    points = points.map { p -> p.copy(x = t.mapX(p.x, p.y), y = t.mapY(p.x, p.y)) },
    width = if (scaleWidth) (width * t.scale).coerceIn(0.5f, 60f) else width,
)

/** Generador de identificadores; inyectable para pruebas deterministas. */
fun interface IdGenerator {
    fun next(): String

    companion object {
        val RANDOM = IdGenerator { java.util.UUID.randomUUID().toString() }
        fun sequential(prefix: String = "s"): IdGenerator {
            var n = 0
            return IdGenerator { "$prefix${++n}" }
        }
    }
}
