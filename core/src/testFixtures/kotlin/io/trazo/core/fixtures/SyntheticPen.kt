package io.trazo.core.fixtures

import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.PointerKind
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Lápiz sintético para pruebas: genera muestras con tiempo, presión y temblor y las entrega
 * al [InkController] como lo haría el adaptador de `MotionEvent`, ejecutando los ticks del
 * temporizador cada [tickMs]. NO es un S Pen físico: sirve para pruebas JVM reproducibles.
 */
class SyntheticPen(
    val controller: InkController,
    var nowMs: Long = 10_000L,
    private val sampleMs: Long = 8L,
    private val tickMs: Long = 16L,
    private val stepUnits: Float = 3f,
    private val pxPerUnit: Float = 1f,
    seed: Int = 7,
    /** Observa cada muestra entregada (para grabar fotogramas de evidencia). */
    var onSample: (() -> Unit)? = null,
) {
    private val random = Random(seed)
    val events = mutableListOf<InkEvent>()
    private var nextTickAt = nowMs

    init { controller.addListener { events += it } }

    /**
     * Dibuja un trazo siguiendo [path] (coordenadas de documento).
     * @param holdMs tiempo que la punta queda apoyada y quieta al final (0 = levantar enseguida).
     * @param tremor amplitud del temblor durante la pausa, en unidades.
     * @param liftEarly si no es null, levanta el lápiz tras ese tiempo de pausa.
     */
    fun stroke(
        path: List<Pair<Float, Float>>,
        holdMs: Long = 0L,
        tremor: Float = 0f,
        kind: PointerKind = PointerKind.STYLUS,
        pressure: Float = 0.75f,
    ) {
        val pts = resample(path, stepUnits)
        val (x0, y0) = pts.first()
        controller.penDown(x0, y0, pressure, nowMs, kind, pxPerUnit)
        onSample?.invoke()
        for (i in 1 until pts.size) {
            advance(sampleMs)
            val (x, y) = pts[i]
            val p = (pressure + 0.15f * kotlin.math.sin(i / 6f)).coerceIn(0.1f, 1f)
            controller.penMove(x, y, p, nowMs)
            onSample?.invoke()
        }
        val (xe, ye) = pts.last()
        val end = nowMs + holdMs
        while (nowMs < end) {
            advance(sampleMs)
            val jx = if (tremor > 0f) (random.nextFloat() * 2 - 1) * tremor else 0f
            val jy = if (tremor > 0f) (random.nextFloat() * 2 - 1) * tremor else 0f
            controller.penMove(xe + jx, ye + jy, pressure, nowMs)
            onSample?.invoke()
        }
        advance(sampleMs)
        controller.penUp(xe, ye, pressure, nowMs)
        onSample?.invoke()
    }

    /** Apoya y sostiene sin dibujar. */
    fun pressAndHold(x: Float, y: Float, holdMs: Long) {
        controller.penDown(x, y, 0.6f, nowMs, PointerKind.STYLUS, pxPerUnit)
        val end = nowMs + holdMs
        while (nowMs < end) { advance(sampleMs); controller.penMove(x, y, 0.6f, nowMs) }
        controller.penUp(x, y, 0.6f, nowMs)
    }

    /** Empieza un trazo y lo deja abierto (para probar CANCEL o pausas parciales). */
    fun begin(path: List<Pair<Float, Float>>, stillMs: Long = 0L) {
        val pts = resample(path, stepUnits)
        controller.penDown(pts[0].first, pts[0].second, 0.7f, nowMs, PointerKind.STYLUS, pxPerUnit)
        for (i in 1 until pts.size) { advance(sampleMs); controller.penMove(pts[i].first, pts[i].second, 0.7f, nowMs) }
        val end = nowMs + stillMs
        while (nowMs < end) { advance(sampleMs); controller.penMove(pts.last().first, pts.last().second, 0.7f, nowMs) }
    }

    fun pause(ms: Long) = advance(ms)

    /** Avanza el reloj ejecutando los ticks pendientes, como el `Handler` de la vista Android. */
    fun advance(ms: Long) {
        val target = nowMs + ms
        while (nextTickAt <= target) {
            if (nextTickAt > nowMs) nowMs = nextTickAt
            controller.tick(nowMs)
            nextTickAt += tickMs
        }
        nowMs = target
    }

    companion object {
        fun resample(path: List<Pair<Float, Float>>, step: Float): List<Pair<Float, Float>> {
            if (path.size < 2) return path
            val out = mutableListOf(path[0])
            for (i in 1 until path.size) {
                val (ax, ay) = path[i - 1]
                val (bx, by) = path[i]
                val n = (hypot(bx - ax, by - ay) / step).toInt().coerceAtLeast(1)
                for (k in 1..n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
            }
            return out
        }
    }
}
