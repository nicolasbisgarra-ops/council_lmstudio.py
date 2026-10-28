package io.trazo.app

import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.fixtures.SyntheticPen
import kotlin.concurrent.thread
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
    private var lastEventTime = 0L
    private var dismisser: Thread? = null

    /** Mayor hueco entre eventos del último trazo: la app lo ve como lápiz quieto (emulador cargado). */
    var maxGapMs = 0L
        private set

    /** Convierte coordenadas de documento a pantalla usando la transformación actual de la vista. */
    fun toScreen(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> =
        toScreen(view(), points) // fuera del hilo principal: puede usar ActivityScenario.onActivity

    private fun toScreen(v: InkCanvasView, points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(points.size)
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
        val pts = toScreen(focusedView(), SyntheticPen.resample(docPath, 3f))
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
            // Con el lápiz apoyado no se espera inactividad: el tiempo quieto contaría como pausa.
            instrumentation.waitForIdleSync()
        }
    }

    /**
     * Trazo libre (sin pausa final) que se rehace si el host frenó la inyección: un hueco de
     * [maxStallMs] o más es, para la app, el lápiz quieto en medio del trazo, y puede convertirlo
     * con razón en un patrón o descartarlo como gesto sin coincidencia. Se deshace lo que haya
     * quedado de ese intento (sin tocar lo anterior) y se vuelve a dibujar.
     * @return la cantidad de intentos con hueco que se descartaron.
     */
    fun freeStroke(docPath: List<Pair<Float, Float>>, sampleMs: Long = 8L, maxStallMs: Long = 700L, attempts: Int = 3): Int {
        var stalls = 0
        repeat(attempts) { attempt ->
            val before = pageIds()
            stroke(docPath, sampleMs = sampleMs)
            if (maxGapMs < maxStallMs || attempt == attempts - 1) return stalls
            stalls++
            // Conversión: una entrada de historial (a veces dos, con la tinta cruda): se deshace hasta
            // volver a la hoja de antes, sin pasarse.
            var undos = 0
            while (pageIds() != before && undos++ < 3) {
                val v = view()
                var undone = false
                instrumentation.runOnMainSync { undone = v.controller!!.undo() }
                if (!undone) break
            }
            check(pageIds() == before) { "No se pudo deshacer el intento frenado (hueco de $maxGapMs ms)" }
            SystemClock.sleep(300)
        }
        return stalls
    }

    private fun pageIds(): List<String> {
        var ids = emptyList<String>()
        val v = view()
        instrumentation.runOnMainSync { ids = v.controller!!.page.strokes.map { it.id } }
        return ids
    }

    fun liftAt(docX: Float, docY: Float) {
        val (x, y) = toScreen(listOf(docX to docY)).single()
        // Tras recrear la ventana, el sistema puede descartar un UP sin DOWN: no es un error.
        inject(MotionEvent.ACTION_UP, x, y, 0.5f, MotionEvent.TOOL_TYPE_STYLUS, strict = false)
    }

    private fun inject(action: Int, x: Float, y: Float, pressure: Float, tool: Int, strict: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { downTime = now; maxGapMs = 0L } else maxGapMs = maxOf(maxGapMs, now - lastEventTime)
        lastEventTime = now
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
        shell("screencap -p /data/local/tmp/$name.png")
    }

    /**
     * Devuelve el lienzo cuando su ventana tiene el foco con la pantalla encendida: en un emulador
     * de CI cargado puede quedar encima un diálogo del sistema («System UI no responde»), el panel
     * de notificaciones o el bloqueo, que se tragan los toques inyectados aunque `injectInputEvent`
     * devuelva true.
     */
    private fun focusedView(): InkCanvasView {
        val power = checkNotNull(instrumentation.targetContext.getSystemService(PowerManager::class.java))
        val start = SystemClock.uptimeMillis()
        var lastDismiss = 0L
        while (true) {
            val v = view() // se vuelve a pedir en cada vuelta por si se recreó la actividad
            var focused = false
            instrumentation.runOnMainSync { focused = v.hasWindowFocus() }
            if (focused && power.isInteractive) return v
            val now = SystemClock.uptimeMillis()
            check(now - start < 30_000) { "El lienzo no tiene el foco (interactivo=${power.isInteractive}): ${focusInfo()}" }
            // Una hoja de la app que se cierra devuelve el foco enseguida: solo se interviene pasado 1 s.
            if (now - start >= 1_000 && now - lastDismiss >= 3_000 && dismisser?.isAlive != true) {
                lastDismiss = now
                // Despierta, quita el bloqueo y cierra los diálogos del sistema y el panel (no los
                // de la app). El broadcast espera a SystemUI: va en otro hilo para no colgar la prueba.
                dismisser = thread(isDaemon = true) {
                    runCatching {
                        shell("input keyevent KEYCODE_WAKEUP")
                        shell("wm dismiss-keyguard")
                        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                    }
                }
            }
            SystemClock.sleep(100)
        }
    }

    /** Foco actual, sin la sección del último ANR de `dumpsys window` (guarda el foco de entonces). */
    private fun focusInfo(): String {
        var lastAnr = false
        return shell("dumpsys window").lines().filter { line ->
            if (line.startsWith("WINDOW MANAGER ")) lastAnr = "LAST ANR" in line
            !lastAnr && ("mCurrentFocus" in line || "mFocusedApp" in line)
        }.joinToString(" | ") { it.trim() }
    }

    private fun shell(cmd: String): String = ui.executeShellCommand(cmd).use { pfd ->
        java.io.FileInputStream(pfd.fileDescriptor).use { String(it.readBytes()) }
    }
}
