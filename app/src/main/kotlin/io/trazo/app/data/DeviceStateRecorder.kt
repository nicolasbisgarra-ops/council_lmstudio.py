package io.trazo.app.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import io.trazo.core.diag.DeviceSample
import io.trazo.core.diag.DeviceStateLog
import io.trazo.core.diag.DurationStats
import io.trazo.core.diag.FrameStats
import java.io.File

/**
 * Mide el estado del dispositivo mientras Trazo está en primer plano y lo guarda en
 * `files/diag/device-state.jsonl` (una línea por muestra, ver [DeviceStateLog]), para optimizar la app
 * con datos reales: cuadros atrasados por pantalla, cuánto tarda en dibujarse la hoja, memoria,
 * temperatura y batería.
 *
 * Es barato a propósito: los cuadros llegan por FrameMetrics (solo los que se dibujan, sin despertar la
 * pantalla), la muestra es cada [INTERVAL_MS] y todo corre en un hilo propio de baja prioridad. Nada sale
 * del dispositivo: el archivo solo se comparte si el usuario lo pide desde Ajustes. No guarda contenido.
 */
class DeviceStateRecorder(private val context: Context, filesDir: File) {
    private val dir = File(filesDir, "diag")
    private val file = File(dir, "device-state.jsonl")
    private val previous = File(dir, "device-state.1.jsonl")

    private val thread = HandlerThread("trazo-devlog", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    // Solo se tocan desde [handler].
    private val frames = FrameStats()
    private var periodNanos = 16_666_667L
    private var refreshHz = 0f
    private var window: Window? = null
    private var running = false
    private var lastThermal = -1

    /** Pantalla visible; la pone la navegación. */
    @Volatile var screen: String = ""

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (running && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
            frames.onFrameDuration(metrics.getMetric(FrameMetrics.TOTAL_DURATION), periodNanos)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            record("tick")
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Empieza a medir la ventana de la actividad visible. */
    fun start(window: Window) {
        val hz = runCatching { @Suppress("DEPRECATION") window.windowManager.defaultDisplay.refreshRate }.getOrDefault(60f)
        handler.post {
            if (running) return@post
            running = true
            this.window = window
            refreshHz = hz
            periodNanos = if (hz > 1f) (1_000_000_000L / hz).toLong() else 16_666_667L
            frames.reset(keepClock = false)
            InkProbe.reset()
            runCatching { window.addOnFrameMetricsAvailableListener(frameListener, handler) }
            record("start")
            handler.postDelayed(tick, INTERVAL_MS)
        }
    }

    fun stop() {
        handler.post {
            if (!running) return@post
            record("stop")
            running = false
            handler.removeCallbacks(tick)
            window?.let { w -> runCatching { w.removeOnFrameMetricsAvailableListener(frameListener) } }
            window = null
        }
    }

    /** Un evento suelto (por ejemplo "trim:15" cuando Android pide liberar memoria). */
    fun event(name: String) {
        handler.post { if (running) record(name) }
    }

    /** Todas las muestras guardadas, de la más vieja a la más nueva. */
    fun readAll(): List<DeviceSample> = runOnThread {
        listOf(previous, file).flatMap { f -> if (f.exists()) runCatching { f.readLines() }.getOrDefault(emptyList()) else emptyList() }
            .mapNotNull { DeviceStateLog.decode(it) }
    }

    /** El archivo crudo (JSON por línea), para compartir. */
    fun raw(): String = runOnThread {
        listOf(previous, file).filter { it.exists() }.joinToString("") { runCatching { it.readText() }.getOrDefault("") }
    }

    fun summary(): String = DeviceStateLog.summarize(readAll())

    fun clear() {
        runOnThread { previous.delete(); file.delete() }
    }

    private fun record(event: String) {
        val sample = snapshot(event)
        if (event == "tick" && sample.thermal >= 0 && lastThermal >= 0 && sample.thermal != lastThermal) {
            write(sample.copy(event = "thermal:${sample.thermal}"))
        } else {
            write(sample)
        }
        if (sample.thermal >= 0) lastThermal = sample.thermal
        frames.reset()
    }

    private fun snapshot(event: String): DeviceSample {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { power?.currentThermalStatus ?: -1 }.getOrDefault(-1) else -1

        val rt = Runtime.getRuntime()
        val mem = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val hasMem = runCatching { am?.getMemoryInfo(mem) != null }.getOrDefault(false)

        val ink = InkProbe.drain()
        return DeviceSample(
            t = System.currentTimeMillis(),
            event = event,
            screen = screen,
            battery = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = plugged,
            batteryTempC = if (temp == Int.MIN_VALUE) Float.NaN else temp / 10f,
            thermal = thermal,
            powerSave = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false),
            heapUsedMb = ((rt.totalMemory() - rt.freeMemory()) / MB).toInt(),
            heapMaxMb = (rt.maxMemory() / MB).toInt(),
            ramAvailMb = if (hasMem) (mem.availMem / MB).toInt() else -1,
            lowMemory = hasMem && mem.lowMemory,
            refreshHz = refreshHz,
            frames = frames.frames,
            jank = frames.jank,
            worstFrameMs = frames.worstMs,
            p95FrameMs = frames.p95Ms(),
            inkDraws = ink.draws,
            inkDrawAvgMs = ink.avgMs,
            inkDrawWorstMs = ink.worstMs,
            strokes = if (screen == "Editor") ink.strokes else -1,
            inking = ink.inking,
        )
    }

    private fun write(sample: DeviceSample) {
        runCatching {
            dir.mkdirs()
            if (file.length() > MAX_BYTES) {
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(DeviceStateLog.encode(sample) + "\n")
        }
    }

    /** Corre [block] en el hilo del registro y espera (lecturas y borrado no chocan con una escritura). */
    private fun <T> runOnThread(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val box = java.util.concurrent.FutureTask(java.util.concurrent.Callable { block() })
        handler.post(box)
        return runCatching { box.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { block() }
    }

    companion object {
        const val INTERVAL_MS = 15_000L
        /** Unos 512 KB por archivo, dos archivos: varios días de uso. */
        const val MAX_BYTES = 512L * 1024
        private const val MB = 1024L * 1024
    }
}

/**
 * Lo que mide el lienzo de la hoja: cuánto tarda cada onDraw, cuántos trazos tiene y cuánto tiempo el
 * lápiz o el dedo estuvo apoyado. Se llama desde el hilo principal; se vacía desde el hilo del registro.
 */
object InkProbe {
    class Snapshot(val draws: Int, val avgMs: Float, val worstMs: Float, val strokes: Int, val inking: Float)

    private val lock = Any()
    private val draws = DurationStats()
    private var strokes = -1
    private var downAt = 0L
    private var downMs = 0L
    private var since = SystemClock.uptimeMillis()

    /** Apagado = no medir nada (el ajuste «Registro de rendimiento»). */
    @Volatile var enabled = true

    fun drawn(nanos: Long, strokeCount: Int) {
        if (!enabled) return
        synchronized(lock) { draws.add(nanos / 1_000_000f); strokes = strokeCount }
    }

    /** [down] = el lápiz o el dedo tocó la hoja; false = se levantó o se canceló. */
    fun contact(down: Boolean) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            if (down) { if (downAt == 0L) downAt = now }
            else if (downAt != 0L) { downMs += now - downAt; downAt = 0L }
        }
    }

    fun reset() {
        synchronized(lock) { draws.reset(); strokes = -1; downAt = 0L; downMs = 0L; since = SystemClock.uptimeMillis() }
    }

    fun drain(): Snapshot = synchronized(lock) {
        val now = SystemClock.uptimeMillis()
        if (downAt != 0L) { downMs += now - downAt; downAt = now }
        val span = (now - since).coerceAtLeast(1L)
        val s = Snapshot(draws.count, draws.average, draws.worst, strokes, (downMs.toFloat() / span).coerceIn(0f, 1f))
        draws.reset(); downMs = 0L; since = now
        s
    }
}
