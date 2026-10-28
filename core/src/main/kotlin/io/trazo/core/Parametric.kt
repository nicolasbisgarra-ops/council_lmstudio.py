package io.trazo.core

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * Formas dinámicas (paramétricas): la fuente de verdad es [ParametricShape], guardada en
 * [Page.parametricShapes]. Su tinta ([ParametricShape.render]) es derivada: se regenera tras cada
 * edición y nunca se guarda en [Page.strokes]. Coordenadas de documento, y positivo hacia abajo.
 * Geometría basada en el paquete de ChatGPT (Trazo_Parametricos_Editables).
 */

@Serializable
data class GeoPoint(val x: Float, val y: Float) {
    operator fun plus(v: GeoPoint) = GeoPoint(x + v.x, y + v.y)
    operator fun minus(v: GeoPoint) = GeoPoint(x - v.x, y - v.y)
    operator fun times(k: Float) = GeoPoint(x * k, y * k)
    fun length(): Float = hypot(x, y)
    fun normalized(fallback: GeoPoint = GeoPoint(1f, 0f)): GeoPoint =
        if (length() > 0.001f) this * (1f / length()) else fallback
    fun dot(v: GeoPoint) = x * v.x + y * v.y
}

@Serializable
enum class GeoKind {
    CIRCLE, ELLIPSE, RECTANGLE, SQUARE, ROUNDED_RECTANGLE, CAPSULE,
    TRIANGLE, DIAMOND, HEXAGON, PENTAGON, PARALLELOGRAM,
    AXES_R2, AXES_R3, CUBOID, CYLINDER, CONE, SPHERE, VECTOR, FREEFORM,
    // Se agregan al final para conservar la representación de todos los cuadernos anteriores.
    OCTAGON, TRAPEZOID, STAR_5, SEMICIRCLE, ARC, DOUBLE_ARROW;

    /** Sus tiradores x/y/z son ejes proyectados (longitud y dirección), no ancho/alto. */
    val isProjected: Boolean get() = this == AXES_R3 || this == CUBOID || this == CYLINDER || this == CONE || this == VECTOR
}

enum class AxisEditMode { LENGTH_ONLY, ANGLE_ONLY, LENGTH_AND_ANGLE }

@Serializable
data class ParametricShape(
    val id: String,
    val kind: GeoKind,
    val origin: GeoPoint,
    val width: Float = 150f,
    val height: Float = 105f,
    val depth: Float = 95f,
    val cornerRadius: Float = 18f,
    val rotationDegrees: Float = 0f,
    /** Vectores de proyección de norma uno, editables por separado. */
    val xAxis: GeoPoint = GeoPoint(1f, 0.30f).normalized(),
    val yAxis: GeoPoint = GeoPoint(-0.7f, 0.45f).normalized(),
    val zAxis: GeoPoint = GeoPoint(0f, -1f),
    val negativeAxisRatio: Float = 0.35f,
    val showAxisLabels: Boolean = true,
    /** Trazos de un preset anterior (FREEFORM): se escalan en X e Y por separado y siguen siendo vectoriales. */
    val sourcePaths: List<List<GeoPoint>> = emptyList(),
    val sourcePatternId: String? = null,
    val displayName: String? = null,
    val color: Int = DEFAULT_INK_COLOR,
    val inkWidth: Float = DEFAULT_INK_WIDTH,
    val brush: Brush = Brush.PEN,
    // Añadidos al final, sin alterar parámetros posicionales existentes. Defaults para cuadernos v3.
    val trapezoidTopRatio: Float = 0.60f,
    val starInnerRatio: Float = 0.43f,
    val arcSweepDegrees: Float = 180f,
) {
    init {
        require(id.isNotBlank())
        require(width.isFinite() && width > 0f)
        require(height.isFinite() && height > 0f)
        require(depth.isFinite() && depth > 0f)
        require(origin.x.isFinite() && origin.y.isFinite())
    }

    val isCube: Boolean
        get() = kind == GeoKind.CUBOID && abs(width - height) <= 0.0001f && abs(width - depth) <= 0.0001f

    val style: InkStyle get() = InkStyle(color, inkWidth, brush)

    /** Tinta derivada, con ids estables `parametric:<id>:render:N`. */
    fun render(time: Long = 0L): List<InkStroke> = ParametricInk.render(this, time)

    val bounds: Bounds get() = render().bounds()
}

data class GeoPath(val points: List<GeoPoint>, val closed: Boolean = false, val dashed: Boolean = false)
data class GeoLabel(val value: String, val at: GeoPoint)
data class GeoHandle(val id: String, val at: GeoPoint)
data class GeoDrawing(val paths: List<GeoPath>, val handles: List<GeoHandle>, val labels: List<GeoLabel> = emptyList())

object ParametricGeometry {
    const val MIN_SIZE = 12f
    const val MAX_SIZE = 3000f
    private fun clamp(v: Float) = v.coerceIn(MIN_SIZE, MAX_SIZE)
    private fun deg(d: Float) = d * PI.toFloat() / 180f
    private fun addPath(paths: MutableList<GeoPath>, vararg points: GeoPoint) { paths.add(GeoPath(points.toList())) }
    private fun rotate(v: GeoPoint, degrees: Float): GeoPoint {
        val a = deg(degrees)
        return GeoPoint(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }
    private fun local(s: ParametricShape, x: Float, y: Float) = s.origin + rotate(GeoPoint(x, y), s.rotationDegrees)
    private fun arrow(paths: MutableList<GeoPath>, start: GeoPoint, end: GeoPoint, size: Float = 9f) {
        addPath(paths, start, end)
        val unit = (end - start).normalized()
        val normal = GeoPoint(-unit.y, unit.x)
        addPath(paths, end - unit * size + normal * (size * 0.45f), end, end - unit * size - normal * (size * 0.45f))
    }
    private fun loop(points: List<GeoPoint>) = GeoPath(points, closed = true)
    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, steps: Int = 96): List<GeoPoint> =
        (0 until steps).map { i ->
            val a = -PI.toFloat() / 2f + i * 2f * PI.toFloat() / steps
            GeoPoint(cx + rx * cos(a), cy + ry * sin(a))
        }
    private fun corner(points: MutableList<GeoPoint>, cx: Float, cy: Float, r: Float, from: Float, to: Float) {
        for (i in 0..10) {
            val a = deg(from + (to - from) * i / 10f)
            points += GeoPoint(cx + r * cos(a), cy + r * sin(a))
        }
    }
    private fun polygon(n: Int, w: Float, h: Float): List<GeoPoint> =
        (0 until n).map { i ->
            val a = -PI.toFloat() / 2 + 2 * PI.toFloat() * i / n
            GeoPoint(w / 2f * cos(a), h / 2f * sin(a))
        }
    private fun arc(rx: Float, ry: Float, sweep: Float): List<GeoPoint> =
        (0..48).map { i ->
            val a = deg(180f + sweep * i / 48f)
            GeoPoint(rx * cos(a), ry * sin(a))
        }

    /** Lleva un ángulo a (-180, 180]. */
    private fun normalizeDegrees(d: Float): Float {
        var a = d % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }

    fun draw(s: ParametricShape): GeoDrawing {
        val p = mutableListOf<GeoPath>()
        val h = mutableListOf<GeoHandle>()
        val labels = mutableListOf<GeoLabel>()
        h += GeoHandle("move", s.origin)
        val x = s.xAxis.normalized()
        val y = s.yAxis.normalized(GeoPoint(-0.7f, 0.45f).normalized())
        val z = s.zAxis.normalized(GeoPoint(0f, -1f))
        fun world(a: Float, b: Float, c: Float) = s.origin + x * a + y * b + z * c
        fun axis(id: String, vector: GeoPoint, size: Float, letter: String) {
            val pos = s.origin + vector * size
            val neg = s.origin - vector * size * s.negativeAxisRatio.coerceIn(0f, 1f)
            arrow(p, neg, pos)
            h += GeoHandle(id, pos)
            if (s.showAxisLabels) labels += GeoLabel(letter, pos + vector * 13f)
        }
        when (s.kind) {
            GeoKind.AXES_R2 -> {
                axis("x", rotate(GeoPoint(1f, 0f), s.rotationDegrees), s.width, "x")
                axis("y", rotate(GeoPoint(0f, -1f), s.rotationDegrees), s.height, "y")
                if (s.showAxisLabels) labels += GeoLabel("o", s.origin + GeoPoint(-16f, 13f))
            }
            GeoKind.AXES_R3 -> {
                axis("x", x, s.width, "x")
                axis("y", y, s.depth, "y")
                axis("z", z, s.height, "z")
                if (s.showAxisLabels) labels += GeoLabel("o", s.origin + GeoPoint(10f, -5f))
            }
            GeoKind.CUBOID -> {
                val wd = s.width; val dp = s.depth; val ht = s.height
                // 12 aristas del prisma, agrupadas por familia de ejes.
                for (b in listOf(0f, dp)) for (c in listOf(0f, ht)) addPath(p, world(0f, b, c), world(wd, b, c))
                for (a in listOf(0f, wd)) for (c in listOf(0f, ht)) addPath(p, world(a, 0f, c), world(a, dp, c))
                for (a in listOf(0f, wd)) for (b in listOf(0f, dp)) addPath(p, world(a, b, 0f), world(a, b, ht))
                h += GeoHandle("x", world(wd, 0f, 0f))
                h += GeoHandle("y", world(0f, dp, 0f))
                h += GeoHandle("z", world(0f, 0f, ht))
                if (s.showAxisLabels) {
                    labels += GeoLabel("x", world(wd * .55f, 0f, 0f) + GeoPoint(4f, 16f))
                    labels += GeoLabel("y", world(0f, dp * .55f, 0f) + GeoPoint(-15f, 4f))
                    labels += GeoLabel("z", world(0f, 0f, ht * .55f) + GeoPoint(-17f, 0f))
                }
            }
            GeoKind.CYLINDER, GeoKind.CONE -> {
                val rx = s.width / 2f; val ry = s.depth * .25f; val ht = s.height
                fun ring(atZ: Float) = ellipse(0f, 0f, rx, ry).map { s.origin + x * it.x + GeoPoint(0f, it.y) + z * atZ }
                p += loop(ring(0f))
                if (s.kind == GeoKind.CYLINDER) {
                    p += loop(ring(ht))
                    addPath(p, world(-rx, 0f, 0f), world(-rx, 0f, ht))
                    addPath(p, world(rx, 0f, 0f), world(rx, 0f, ht))
                } else {
                    addPath(p, world(-rx, 0f, 0f), world(0f, 0f, ht), world(rx, 0f, 0f))
                }
                h += GeoHandle("x", world(rx, 0f, 0f))
                h += GeoHandle("z", world(0f, 0f, ht))
            }
            GeoKind.VECTOR -> {
                val end = s.origin + x * s.width
                arrow(p, s.origin, end)
                h += GeoHandle("x", end)
            }
            else -> {
                val w = s.width; val ht = s.height
                val localPoints: List<GeoPoint> = when (s.kind) {
                    GeoKind.CIRCLE -> ellipse(0f, 0f, min(w, ht) / 2f, min(w, ht) / 2f)
                    GeoKind.ELLIPSE, GeoKind.SPHERE -> ellipse(0f, 0f, w / 2f, ht / 2f)
                    GeoKind.RECTANGLE -> listOf(GeoPoint(-w / 2f, -ht / 2f), GeoPoint(w / 2f, -ht / 2f), GeoPoint(w / 2f, ht / 2f), GeoPoint(-w / 2f, ht / 2f))
                    GeoKind.SQUARE -> listOf(GeoPoint(-w / 2f, -w / 2f), GeoPoint(w / 2f, -w / 2f), GeoPoint(w / 2f, w / 2f), GeoPoint(-w / 2f, w / 2f))
                    GeoKind.TRIANGLE -> polygon(3, w, ht)
                    GeoKind.PENTAGON -> polygon(5, w, ht)
                    GeoKind.HEXAGON -> polygon(6, w, ht)
                    GeoKind.OCTAGON -> (0 until 8).map { i ->
                        val a = -PI.toFloat() * 5f / 8f + i * PI.toFloat() / 4f
                        GeoPoint(w / 2f * cos(a), ht / 2f * sin(a))
                    }
                    GeoKind.TRAPEZOID -> {
                        val top = w * s.trapezoidTopRatio.coerceIn(.15f, .95f) / 2f
                        listOf(GeoPoint(-top, -ht / 2f), GeoPoint(top, -ht / 2f),
                            GeoPoint(w / 2f, ht / 2f), GeoPoint(-w / 2f, ht / 2f))
                    }
                    GeoKind.STAR_5 -> (0 until 10).map { i ->
                        val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
                        val ratio = if (i % 2 == 0) 1f else s.starInnerRatio.coerceIn(.15f, .85f)
                        GeoPoint(w / 2f * ratio * cos(a), ht / 2f * ratio * sin(a))
                    }
                    GeoKind.SEMICIRCLE -> arc(w / 2f, ht / 2f, 180f)
                    GeoKind.ARC -> arc(w / 2f, ht / 2f, s.arcSweepDegrees.coerceIn(15f, 345f))
                    GeoKind.DIAMOND -> listOf(GeoPoint(0f, -ht / 2f), GeoPoint(w / 2f, 0f), GeoPoint(0f, ht / 2f), GeoPoint(-w / 2f, 0f))
                    GeoKind.PARALLELOGRAM -> listOf(GeoPoint(-w * .32f, -ht / 2f), GeoPoint(w * .5f, -ht / 2f), GeoPoint(w * .32f, ht / 2f), GeoPoint(-w * .5f, ht / 2f))
                    GeoKind.ROUNDED_RECTANGLE, GeoKind.CAPSULE -> {
                        val rad = if (s.kind == GeoKind.CAPSULE) min(w, ht) / 2f else s.cornerRadius.coerceIn(0f, min(w, ht) / 2f)
                        mutableListOf<GeoPoint>().apply {
                            corner(this, w / 2f - rad, -ht / 2f + rad, rad, -90f, 0f)
                            corner(this, w / 2f - rad, ht / 2f - rad, rad, 0f, 90f)
                            corner(this, -w / 2f + rad, ht / 2f - rad, rad, 90f, 180f)
                            corner(this, -w / 2f + rad, -ht / 2f + rad, rad, 180f, 270f)
                        }
                    }
                    else -> emptyList() // FREEFORM
                }
                if (s.kind == GeoKind.FREEFORM) {
                    val all = s.sourcePaths.flatten()
                    if (all.isNotEmpty()) {
                        val left = all.minOf { it.x }; val right = all.maxOf { it.x }
                        val top = all.minOf { it.y }; val bottom = all.maxOf { it.y }
                        val cx = (left + right) * .5f; val cy = (top + bottom) * .5f
                        val srcW = (right - left).coerceAtLeast(.001f)
                        val srcH = (bottom - top).coerceAtLeast(.001f)
                        // Un trazo fuente plano (línea) no se estira a lo alto.
                        val sx = if (right - left < 1f) 0f else w / srcW
                        val sy = if (bottom - top < 1f) 0f else ht / srcH
                        s.sourcePaths.filter { it.isNotEmpty() }.forEach { path ->
                            p += GeoPath(path.map { local(s, (it.x - cx) * sx, (it.y - cy) * sy) })
                        }
                    } else {
                        p += loop(listOf(local(s, -w / 2f, -ht / 2f), local(s, w / 2f, -ht / 2f), local(s, w / 2f, ht / 2f), local(s, -w / 2f, ht / 2f)))
                    }
                } else if (s.kind == GeoKind.ARC) {
                    // Un arco es abierto. El semicírculo, en cambio, cierra con su diámetro.
                    p += GeoPath(localPoints.map { local(s, it.x, it.y) })
                } else if (s.kind == GeoKind.DOUBLE_ARROW) {
                    val wing = min(w * .22f, ht * .8f)
                    // Un recorrido manuscrito continuo: no compite con preset-flecha-doble (tres trazos).
                    addPath(p, local(s, -w / 2f + wing, -ht / 2f), local(s, -w / 2f, 0f),
                        local(s, -w / 2f + wing, ht / 2f), local(s, -w / 2f, 0f),
                        local(s, w / 2f, 0f), local(s, w / 2f - wing, -ht / 2f),
                        local(s, w / 2f, 0f), local(s, w / 2f - wing, ht / 2f))
                } else {
                    p += loop(localPoints.map { local(s, it.x, it.y) })
                }
                if (s.kind == GeoKind.SPHERE) {
                    // Ecuador y meridiano punteados: ayudan a leer una esfera.
                    p += GeoPath(ellipse(0f, 0f, w / 2f, ht * .16f).map { local(s, it.x, it.y) }, closed = true, dashed = true)
                    p += GeoPath(ellipse(0f, 0f, w * .15f, ht / 2f).map { local(s, it.x, it.y) }, closed = true, dashed = true)
                }
                val square = s.kind == GeoKind.CIRCLE || s.kind == GeoKind.SQUARE
                val hw = (if (square) min(w, ht) else w) / 2f
                val hh = (if (square) min(w, ht) else ht) / 2f
                h += GeoHandle("w", local(s, hw, 0f))
                if (!square) h += GeoHandle("h", local(s, 0f, -hh))
                h += GeoHandle("rotate", local(s, 0f, -hh - 28f))
                if (s.kind == GeoKind.ROUNDED_RECTANGLE) h += GeoHandle("r", local(s, w / 2f - s.cornerRadius.coerceIn(0f, min(w, ht) / 2f), -ht / 2f))
                if (s.kind == GeoKind.TRAPEZOID) h += GeoHandle("top", local(s, w * s.trapezoidTopRatio / 2f, -ht / 2f))
                if (s.kind == GeoKind.STAR_5) {
                    // Primer vértice interior: el tirador sigue el dibujo aun con X/Y independientes.
                    val a = -PI.toFloat() * 3f / 10f
                    h += GeoHandle("r", local(s, w * s.starInnerRatio * cos(a) / 2f, ht * s.starInnerRatio * sin(a) / 2f))
                }
                if (s.kind == GeoKind.ARC) {
                    val a = deg(180f + s.arcSweepDegrees.coerceIn(15f, 345f))
                    h += GeoHandle("angle", local(s, w / 2f * cos(a), ht / 2f * sin(a)))
                }
            }
        }
        return GeoDrawing(p, h, labels)
    }

    /** Edita UNA dimensión sin cambiar las otras (salvo círculo y cuadrado, que mantienen lados iguales). */
    fun setDimension(s: ParametricShape, axis: String, value: Float): ParametricShape {
        if (!value.isFinite()) return s
        val v = clamp(value)
        val square = s.kind == GeoKind.CIRCLE || s.kind == GeoKind.SQUARE
        return when (axis.lowercase()) {
            "x", "w" -> if (square) s.copy(width = v, height = v) else s.copy(width = v)
            "y", "d" -> s.copy(depth = v)
            "z", "h" -> if (square) s.copy(width = v, height = v) else s.copy(height = v)
            "r" -> if (s.kind == GeoKind.STAR_5)
                s.copy(starInnerRatio = (value / (min(s.width, s.height) / 2f)).coerceIn(.15f, .85f))
                else s.copy(cornerRadius = value.coerceIn(0f, min(s.width, s.height) / 2f))
            "top" -> if (s.kind == GeoKind.TRAPEZOID) s.copy(trapezoidTopRatio = (value / s.width).coerceIn(.15f, .95f)) else s
            "angle" -> if (s.kind == GeoKind.ARC) s.copy(arcSweepDegrees = value.coerceIn(15f, 345f)) else s
            else -> s
        }
    }

    fun setRotation(s: ParametricShape, degrees: Float): ParametricShape =
        if (degrees.isFinite()) s.copy(rotationDegrees = normalizeDegrees(degrees)) else s

    fun translate(s: ParametricShape, to: GeoPoint): ParametricShape = s.copy(origin = to)

    /** El extremo de un eje proyectado controla su longitud y/o su dirección. */
    fun editAxis(s: ParametricShape, axis: String, to: GeoPoint, mode: AxisEditMode = AxisEditMode.LENGTH_AND_ANGLE): ParametricShape {
        val offset = to - s.origin
        fun one(dir: GeoPoint, size: Float): Pair<GeoPoint, Float> {
            val n = dir.normalized()
            return when (mode) {
                AxisEditMode.LENGTH_ONLY -> n to clamp(offset.dot(n))
                AxisEditMode.ANGLE_ONLY -> offset.normalized(n) to size
                AxisEditMode.LENGTH_AND_ANGLE -> offset.normalized(n) to clamp(offset.length())
            }
        }
        return when (axis.lowercase()) {
            "x" -> one(s.xAxis, s.width).let { (v, d) -> s.copy(xAxis = v, width = d) }
            "y" -> one(s.yAxis, s.depth).let { (v, d) -> s.copy(yAxis = v, depth = d) }
            "z" -> one(s.zAxis, s.height).let { (v, d) -> s.copy(zAxis = v, height = d) }
            else -> s
        }
    }

    /** Arrastra el tirador [handle] hasta [to] (coordenadas de documento). */
    fun editHandle(s: ParametricShape, handle: String, to: GeoPoint): ParametricShape {
        if (!to.x.isFinite() || !to.y.isFinite()) return s
        if (handle == "move") return translate(s, to)
        if (s.kind.isProjected) {
            // Cilindro y cono exponen el radio en X, no el diámetro.
            if ((s.kind == GeoKind.CYLINDER || s.kind == GeoKind.CONE) && handle == "x") {
                val edited = editAxis(s, "x", to)
                return edited.copy(width = clamp(edited.width * 2f))
            }
            return editAxis(s, handle, to)
        }
        val relative = rotate(to - s.origin, -s.rotationDegrees)
        return when (handle) {
            "w" -> setDimension(s, "w", abs(relative.x) * 2f)
            "h" -> setDimension(s, "h", abs(relative.y) * 2f)
            "r" -> if (s.kind == GeoKind.STAR_5) {
                // Cociente radial respecto de la elipse envolvente: estable con ancho != alto.
                val rx = relative.x / (s.width / 2f)
                val ry = relative.y / (s.height / 2f)
                s.copy(starInnerRatio = hypot(rx, ry).coerceIn(.15f, .85f))
            } else setDimension(s, "r", s.width / 2f - relative.x)
            "top" -> setDimension(s, "top", abs(relative.x) * 2f)
            "angle" -> {
                val a = atan2(relative.y / (s.height / 2f), relative.x / (s.width / 2f)) * 180f / PI.toFloat()
                var sweep = (a - 180f) % 360f
                if (sweep <= 0f) sweep += 360f
                setDimension(s, "angle", sweep)
            }
            // [relative] ya está desrotado: el giro se suma a la rotación actual.
            "rotate" -> if (relative.length() < 1f) s else setRotation(s, s.rotationDegrees + atan2(relative.x, -relative.y) * 180f / PI.toFloat())
            "x" -> setDimension(s, "w", abs(relative.x))
            "y" -> setDimension(s, "h", abs(relative.y))
            else -> s
        }
    }

    fun nearestHandle(s: ParametricShape, point: GeoPoint, hitRadius: Float = 17f): GeoHandle? =
        draw(s).handles.minByOrNull { (it.at - point).length() }?.takeIf { (it.at - point).length() <= hitRadius }

    /** Distancia mínima de [point] a la tinta de la forma (para tocarla). */
    fun distanceTo(s: ParametricShape, point: GeoPoint): Float {
        var best = Float.MAX_VALUE
        for (path in draw(s).paths) {
            val pts = if (path.closed && path.points.isNotEmpty()) path.points + path.points.first() else path.points
            if (pts.size == 1) best = min(best, (pts[0] - point).length())
            for (i in 1 until pts.size) best = min(best, segmentDistance(point, pts[i - 1], pts[i]))
        }
        return best
    }

    private fun segmentDistance(p: GeoPoint, a: GeoPoint, b: GeoPoint): Float {
        val ab = b - a
        val len2 = ab.dot(ab)
        val t = if (len2 < 1e-6f) 0f else ((p - a).dot(ab) / len2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).length()
    }
}

/** Tinta derivada de las formas dinámicas, para el mismo pintor, exportador y miniaturas que el resto. */
object ParametricInk {
    const val PREFIX = "parametric:"
    private const val DASH = 8f
    private const val GAP = 6f

    fun groupOf(shapeId: String) = PREFIX + shapeId

    /** Id de la forma de la que salió [stroke], o null si es tinta común. */
    fun shapeIdOf(stroke: InkStroke): String? =
        stroke.groupId?.takeIf { it.startsWith(PREFIX) && stroke.id.startsWith(it) }?.removePrefix(PREFIX)

    fun render(shape: ParametricShape, time: Long = 0L): List<InkStroke> {
        val drawing = ParametricGeometry.draw(shape)
        val raw = ArrayList<List<GeoPoint>>()
        for (path in drawing.paths) {
            val pts = if (path.closed && path.points.isNotEmpty()) path.points + path.points.first() else path.points
            if (path.dashed) raw += dashes(densify(pts, 1f)) else raw += pts
        }
        drawing.labels.forEach { raw += handwrittenLabel(it) }
        val parent = groupOf(shape.id)
        return raw.filter { it.isNotEmpty() }.mapIndexed { index, path ->
            val pts = densify(path, 3f)
            InkStroke(
                id = "$parent:render:${index + 1}",
                points = pts.mapIndexed { i, p -> InkPoint(p.x, p.y, 0.83f, i * 4L) },
                color = shape.color,
                width = shape.inkWidth,
                startTime = time,
                groupId = parent,
                patternId = parent,
                brush = shape.brush,
            )
        }
    }

    /** Parte un recorrido denso en guiones de [DASH] con huecos de [GAP]. */
    private fun dashes(points: List<GeoPoint>): List<List<GeoPoint>> {
        val out = ArrayList<List<GeoPoint>>()
        var current = ArrayList<GeoPoint>()
        var along = 0f
        var drawing = true
        for (i in points.indices) {
            if (i > 0) along += (points[i] - points[i - 1]).length()
            val limit = if (drawing) DASH else GAP
            if (along >= limit) {
                if (drawing) { current += points[i]; if (current.size > 1) out += current; current = ArrayList() }
                drawing = !drawing
                along = 0f
            }
            if (drawing) current += points[i]
        }
        if (current.size > 1) out += current
        return out
    }

    /** Letras x, y, z, o trazadas a mano (no tipografía). */
    private fun handwrittenLabel(label: GeoLabel): List<List<GeoPoint>> {
        val x = label.at.x
        val y = label.at.y
        fun pt(a: Float, b: Float) = GeoPoint(x + a, y + b)
        return when (label.value.lowercase()) {
            "x" -> listOf(listOf(pt(-4f, -7f), pt(5f, 3f)), listOf(pt(5f, -7f), pt(-4f, 3f)))
            "y" -> listOf(listOf(pt(-5f, -7f), pt(0f, 1f), pt(5f, -7f)), listOf(pt(0f, 1f), pt(-3f, 9f)))
            "z" -> listOf(listOf(pt(-5f, -7f), pt(5f, -7f), pt(-5f, 3f), pt(5f, 3f)))
            "o" -> listOf((0..30).map { i ->
                val a = 2 * PI * i / 30
                pt((5 * cos(a)).toFloat(), (6 * sin(a)).toFloat() - 3f)
            })
            else -> emptyList()
        }
    }

    private fun densify(points: List<GeoPoint>, step: Float): List<GeoPoint> {
        if (points.size < 2) return points
        val out = mutableListOf(points.first())
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val count = maxOf(1, ceil(hypot(b.x - a.x, b.y - a.y) / step).toInt())
            for (k in 1..count) out += GeoPoint(a.x + (b.x - a.x) * k / count, a.y + (b.y - a.y) * k / count)
        }
        return out
    }
}

/** Crea formas dinámicas: de cero por tipo, o a partir de cualquier patrón (los 46 de geometría incluidos). */
object ParametricTemplates {
    /** Tipo nativo de cada preset geométrico conocido; los demás se importan como FREEFORM. */
    fun recommendedKindFor(patternId: String): GeoKind? = when (patternId.removePrefix("trazo-geo-v1-")) {
        "circulo" -> GeoKind.CIRCLE
        "elipse-h", "elipse-v" -> GeoKind.ELLIPSE
        "cuadrado" -> GeoKind.SQUARE
        "rectangulo-h", "rectangulo-v" -> GeoKind.RECTANGLE
        "redondeado-h", "redondeado-v" -> GeoKind.ROUNDED_RECTANGLE
        "capsula-h", "capsula-v" -> GeoKind.CAPSULE
        "rombo" -> GeoKind.DIAMOND
        "triangulo" -> GeoKind.TRIANGLE
        "paralelogramo" -> GeoKind.PARALLELOGRAM
        "pentagono" -> GeoKind.PENTAGON
        "hexagono" -> GeoKind.HEXAGON
        "octagono" -> GeoKind.OCTAGON
        "trapecio" -> GeoKind.TRAPEZOID
        "estrella-5" -> GeoKind.STAR_5
        "semicirculo" -> GeoKind.SEMICIRCLE
        "arco" -> GeoKind.ARC
        "flecha-doble" -> GeoKind.DOUBLE_ARROW
        "ejes-r2" -> GeoKind.AXES_R2
        "ejes-r3" -> GeoKind.AXES_R3
        "cubo" -> GeoKind.CUBOID
        "cilindro" -> GeoKind.CYLINDER
        "cono" -> GeoKind.CONE
        "esfera" -> GeoKind.SPHERE
        "flecha-vector", "vector-2d" -> GeoKind.VECTOR
        else -> null
    }

    /** Forma nueva de [kind] centrada (o con origen, para ejes y sólidos) en [origin], de unas [size] unidades. */
    fun create(id: String, kind: GeoKind, origin: GeoPoint, size: Float = 160f, style: InkStyle = InkStyle()): ParametricShape {
        val sz = size.coerceIn(ParametricGeometry.MIN_SIZE, ParametricGeometry.MAX_SIZE)
        val (w, h, d) = when (kind) {
            GeoKind.CUBOID -> Triple(sz * .6f, sz * .6f, sz * .6f)
            GeoKind.CIRCLE, GeoKind.SQUARE -> Triple(sz, sz, sz * .7f)
            GeoKind.AXES_R2 -> Triple(sz, sz * .8f, sz * .7f)
            GeoKind.AXES_R3 -> Triple(sz * .8f, sz * .7f, sz * .6f)
            GeoKind.CYLINDER, GeoKind.CONE -> Triple(sz * .6f, sz * .8f, sz * .6f)
            GeoKind.VECTOR -> Triple(sz, sz * .1f, sz * .1f)
            GeoKind.ARC, GeoKind.SEMICIRCLE -> Triple(sz, sz * .55f, sz * .7f)
            GeoKind.DOUBLE_ARROW -> Triple(sz, sz * .38f, sz * .7f)
            GeoKind.TRIANGLE, GeoKind.PENTAGON, GeoKind.HEXAGON, GeoKind.OCTAGON,
            GeoKind.TRAPEZOID, GeoKind.STAR_5, GeoKind.DIAMOND, GeoKind.SPHERE -> Triple(sz, sz * .9f, sz * .7f)
            else -> Triple(sz, sz * .65f, sz * .7f)
        }
        return ParametricShape(
            id = id, kind = kind, origin = origin, width = w, height = h, depth = d,
            color = style.color, inkWidth = style.width, brush = style.brush,
        )
    }

    /** Convierte un patrón en forma dinámica; sin equivalente nativo queda como FREEFORM escalable en X/Y. */
    fun fromPattern(
        pattern: InkPattern,
        id: String,
        origin: GeoPoint,
        size: Float = 160f,
        kindOverride: GeoKind? = null,
        style: InkStyle = InkStyle(),
    ): ParametricShape {
        val kind = kindOverride ?: recommendedKindFor(pattern.id) ?: GeoKind.FREEFORM
        if (kind != GeoKind.FREEFORM) return create(id, kind, origin, size, style).copy(sourcePatternId = pattern.id, displayName = pattern.name)
        val source = pattern.outputInk.map { stroke -> stroke.points.map { GeoPoint(it.x, it.y) } }
        val b = pattern.outputInk.bounds()
        val w = b.width.coerceAtLeast(1f)
        val h = b.height.coerceAtLeast(1f)
        val k = size / maxOf(w, h)
        return ParametricShape(
            id = id, kind = GeoKind.FREEFORM, origin = origin,
            width = (w * k).coerceAtLeast(ParametricGeometry.MIN_SIZE),
            height = (h * k).coerceAtLeast(ParametricGeometry.MIN_SIZE),
            depth = size * .7f,
            sourcePaths = source, sourcePatternId = pattern.id, displayName = pattern.name,
            color = style.color, inkWidth = style.width, brush = style.brush,
        )
    }
}
