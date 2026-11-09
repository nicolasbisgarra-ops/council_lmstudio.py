package io.trazo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Regla o transportador flotante sobre la hoja, en unidades del documento. La pluma que apoya cerca de
 * un borde queda pegada a él hasta levantarse: el borde largo de la regla, o el arco y la base del
 * transportador. Se mueve con un dedo y se mueve y gira con dos.
 *
 * Ejes: [angle] (radianes) da la dirección u del borde; n es u girado 90°. La regla se extiende
 * ±[length]/2 a lo largo de u y ±[thickness]/2 a lo ancho. El transportador es el semicírculo de radio
 * [radius] del lado −n (arriba en pantalla con ángulo 0), con la base sobre la recta que pasa por el centro.
 */
data class Ruler(
    val kind: Kind,
    val cx: Float,
    val cy: Float,
    val angle: Float = 0f,
    val length: Float = 600f,
    val thickness: Float = 80f,
    val radius: Float = 220f,
) {
    enum class Kind { RULER, PROTRACTOR }

    /** Dónde quedó pegada la pluma. */
    sealed interface Target {
        /** Borde recto: la recta paralela a u a distancia [offset] del centro, entre [from] y [to] a lo largo de u. */
        data class Edge(val offset: Float, val from: Float, val to: Float) : Target
        data object Arc : Target
    }

    private val ux get() = cos(angle)
    private val uy get() = sin(angle)

    /** Coordenadas locales: a lo largo de u y a lo ancho (n). */
    private fun along(x: Float, y: Float) = (x - cx) * ux + (y - cy) * uy
    private fun across(x: Float, y: Float) = -(x - cx) * uy + (y - cy) * ux
    private fun point(a: Float, b: Float) = (cx + a * ux - b * uy) to (cy + a * uy + b * ux)

    /** Si (x, y) toca el cuerpo (con [slop] de margen): ahí el dedo agarra la herramienta. */
    fun contains(x: Float, y: Float, slop: Float = 0f): Boolean {
        val a = along(x, y); val b = across(x, y)
        return when (kind) {
            Kind.RULER -> abs(a) <= length / 2 + slop && abs(b) <= thickness / 2 + slop
            Kind.PROTRACTOR -> b <= slop && hypot(a, b) <= radius + slop
        }
    }

    /** El borde al que se pega una pluma apoyada en (x, y), o null si está lejos de todos. */
    fun snapTarget(x: Float, y: Float, tolerance: Float): Target? {
        val a = along(x, y); val b = across(x, y)
        return when (kind) {
            Kind.RULER -> {
                val h = thickness / 2
                if (abs(a) > length / 2 + tolerance || abs(b) > h + tolerance) null
                else Target.Edge(if (b >= 0f) h else -h, -length / 2, length / 2)
            }
            Kind.PROTRACTOR -> {
                val r = hypot(a, b)
                val nearArc = b <= tolerance && abs(r - radius) <= tolerance
                val nearBase = abs(b) <= tolerance && abs(a) <= radius + tolerance
                when {
                    nearBase && (!nearArc || abs(b) < abs(r - radius)) -> Target.Edge(0f, -radius, radius)
                    nearArc -> Target.Arc
                    else -> null
                }
            }
        }
    }

    /** Lleva (x, y) al borde [target]; sobre una recta no pasa de sus extremos. */
    fun project(target: Target, x: Float, y: Float): Pair<Float, Float> = when (target) {
        is Target.Edge -> point(along(x, y).coerceIn(target.from, target.to), target.offset)
        Target.Arc -> {
            val t = localAngle(x, y)
            point(radius * cos(t), -radius * sin(t))
        }
    }

    /** Ángulo de (x, y) visto desde el centro, en grados de 0 a 180 medidos desde el borde u. */
    fun angleAt(x: Float, y: Float): Float = (localAngle(x, y) * 180.0 / PI).toFloat()

    private fun localAngle(x: Float, y: Float): Float {
        val a = along(x, y); val b = across(x, y)
        if (a == 0f && b == 0f) return 0f
        var t = atan2(-b, a)
        // Debajo de la base se va al extremo más cercano.
        if (t < 0f) t = if (t > -PI.toFloat() / 2) 0f else PI.toFloat()
        return t
    }

    /** Inclinación del borde en grados, de 0 a 180 (para mostrarla). */
    val degrees: Float get() {
        var d = (angle * 180.0 / PI).toFloat() % 180f
        if (d < 0f) d += 180f
        // Sin «-0°» ni «180°»: lo que redondea a 180 es 0 (la misma recta).
        if (d >= 179.5f) d = 0f
        return d + 0f
    }

    fun moved(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)

    /**
     * Dos dedos pasaron de (a0, b0) a (a1, b1): la herramienta sigue el movimiento de su punto medio
     * y gira lo que giró la recta entre ellos, como si estuviera pegada a los dedos.
     */
    fun transformed(
        a0x: Float, a0y: Float, b0x: Float, b0y: Float,
        a1x: Float, a1y: Float, b1x: Float, b1y: Float,
    ): Ruler {
        if (hypot(b0x - a0x, b0y - a0y) < 1e-3f || hypot(b1x - a1x, b1y - a1y) < 1e-3f) {
            return moved((a1x + b1x - a0x - b0x) / 2, (a1y + b1y - a0y - b0y) / 2)
        }
        val turn = atan2(b1y - a1y, b1x - a1x) - atan2(b0y - a0y, b0x - a0x)
        val m0x = (a0x + b0x) / 2; val m0y = (a0y + b0y) / 2
        val m1x = (a1x + b1x) / 2; val m1y = (a1y + b1y) / 2
        val c = cos(turn); val s = sin(turn)
        val rx = cx - m0x; val ry = cy - m0y
        return copy(cx = m1x + rx * c - ry * s, cy = m1y + rx * s + ry * c, angle = angle + turn)
    }

    /** Ajusta la inclinación al grado entero más cercano si está a menos de [withinDeg] (más fácil dejarla en 0°, 45°…). */
    fun settled(withinDeg: Float = 1.5f): Ruler {
        val step = PI.toFloat() / 180f
        val near = Math.round(angle / step) * step
        val wholeOf15 = Math.round(angle / (15 * step)) * 15 * step
        return when {
            abs(angle - wholeOf15) * 180f / PI.toFloat() <= withinDeg -> copy(angle = wholeOf15)
            else -> copy(angle = near)
        }
    }
}
