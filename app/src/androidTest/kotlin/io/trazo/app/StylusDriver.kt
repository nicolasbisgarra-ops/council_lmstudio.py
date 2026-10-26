package io.trazo.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.fixtures.SyntheticPen
import kotlin.random.Random

/**
 * Inyecta eventos de lápiz SINTETIZADOS (`TOOL_TYPE_STYLUS`, `SOURCE_STYLUS`) a través del
 * pipeline de entrada de Android con `UiAutomation`. Es automatización en emulador/dispositivo,
 * NO un S Pen físico. Los tiempos son reales (la pausa de 1 s la mide la app).
 */
class StylusDriver(private val view: () -> InkCanvasView) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val random = Random(11)
    private var downTime = 0L

    /** Convierte coordenadas de documento a pantalla usando la transformación actual de la vista. */
    fun toScreen(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(points.size)
        val v = view() // fuera del hilo principal: puede usar ActivityScenario.onActivity
        instrumentation.runOnMainSync {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            points.forEach { (x, y) ->
                val (sx, sy) = v.documentToScreen(x, y)
                out += (sx + loc[0]) to (sy + loc[1])
            }
        }
        return out
    }

    /**
     * Dibuja [docPath] (coordenadas de documento) a ~3 unidades por muestra cada [sampleMs].
     * @param holdMs pausa final con la punta apoyada (con temblor de [tremorPx] píxeles).
     * @param lift si es false, deja el lápiz apoyado (para probar rotación/cancelación).
     */
    fun stroke(
        docPath: List<Pair<Float, Float>>,
        holdMs: Long = 0L,
        tremorPx: Float = 0f,
        sampleMs: Long = 8L,
        tool: Int = MotionEvent.TOOL_TYPE_STYLUS,
        lift: Boolean = true,
    ) {
        val pts = toScreen(SyntheticPen.resample(docPath, 3f))
        inject(MotionEvent.ACTION_DOWN, pts[0].first, pts[0].second, 0.6f, tool)
        for (i in 1 until pts.size) {
            SystemClock.sleep(sampleMs)
            val p = (0.65f + 0.2f * kotlin.math.sin(i / 7f))
            inject(MotionEvent.ACTION_MOVE, pts[i].first, pts[i].second, p, tool)
        }
        val (ex, ey) = pts.last()
        val end = SystemClock.uptimeMillis() + holdMs
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(16)
            val jx = if (tremorPx > 0f) (random.nextFloat() * 2 - 1) * tremorPx else 0f
            val jy = if (tremorPx > 0f) (random.nextFloat() * 2 - 1) * tremorPx else 0f
            inject(MotionEvent.ACTION_MOVE, ex + jx, ey + jy, 0.7f, tool)
        }
        if (lift) {
            SystemClock.sleep(sampleMs)
            inject(MotionEvent.ACTION_UP, ex, ey, 0.7f, tool)
        }
        instrumentation.waitForIdleSync()
    }

    fun liftAt(docX: Float, docY: Float) {
        val (x, y) = toScreen(listOf(docX to docY)).single()
        // Tras recrear la ventana, el sistema puede descartar un UP sin DOWN: no es un error.
        inject(MotionEvent.ACTION_UP, x, y, 0.5f, MotionEvent.TOOL_TYPE_STYLUS, strict = false)
    }

    private fun inject(action: Int, x: Float, y: Float, pressure: Float, tool: Int, strict: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 0.01f }
        val source = if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
        val ev = MotionEvent.obtain(downTime, now, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
        val ok = ui.injectInputEvent(ev, true)
        ev.recycle()
        check(ok || !strict) { "No se pudo inyectar el evento $action" }
    }

    /** Captura de pantalla con `screencap` (usuario shell) en /data/local/tmp. */
    fun screenshot(name: String) {
        ui.executeShellCommand("screencap -p /data/local/tmp/$name.png").use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }
}
