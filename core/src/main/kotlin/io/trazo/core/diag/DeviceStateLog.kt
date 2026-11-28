package io.trazo.core.diag

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Registro local del estado del dispositivo mientras se usa Trazo, para optimizar la app después.
 * Sin tipos de Android, sin red y sin contenido del usuario: solo números técnicos y el nombre de la
 * pantalla. Cada muestra es una línea JSON ([encode]/[decode]); [summarize] arma el informe.
 */
data class DeviceSample(
    /** Momento de la muestra (ms desde 1970). */
    val t: Long,
    /** Qué pasó: "tick" (periódica), "start", "stop", "trim:<nivel>", "thermal:<estado>". */
    val event: String = "tick",
    /** Pantalla visible (Editor, Notebooks…). */
    val screen: String = "",
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Temperatura de la batería en °C; NaN si no se sabe. */
    val batteryTempC: Float = Float.NaN,
    /** Estado térmico de Android (0 = normal … 6 = apagado); -1 si no se sabe. */
    val thermal: Int = -1,
    val powerSave: Boolean = false,
    val heapUsedMb: Int = -1,
    val heapMaxMb: Int = -1,
    val ramAvailMb: Int = -1,
    val lowMemory: Boolean = false,
    /** Frecuencia de la pantalla (Hz). */
    val refreshHz: Float = 0f,
    /** Cuadros dibujados en el intervalo, los atrasados (más de 1,5 cuadros) y el peor (ms). */
    val frames: Int = 0,
    val jank: Int = 0,
    val worstFrameMs: Float = 0f,
    val p95FrameMs: Float = 0f,
    /** Dibujo de la hoja (onDraw del lienzo): veces, promedio y peor en ms. */
    val inkDraws: Int = 0,
    val inkDrawAvgMs: Float = 0f,
    val inkDrawWorstMs: Float = 0f,
    /** Trazos de la hoja abierta; -1 fuera del editor. */
    val strokes: Int = -1,
    /** Fracción del intervalo con el lápiz o el dedo apoyado (0..1). */
    val inking: Float = 0f,
)

/** Histograma de duraciones en ms (0..200, de a 1 ms) para promedios y percentiles sin guardar cada valor. */
class DurationStats {
    private val buckets = IntArray(BUCKETS)
    var count = 0
        private set
    private var sum = 0.0
    var worst = 0f
        private set

    fun add(ms: Float) {
        if (ms.isNaN() || ms < 0f) return
        count++
        sum += ms
        worst = max(worst, ms)
        buckets[ms.toInt().coerceIn(0, BUCKETS - 1)]++
    }

    val average: Float get() = if (count == 0) 0f else (sum / count).toFloat()

    /** Percentil [p] (0..1), con resolución de 1 ms. */
    fun percentile(p: Float): Float {
        if (count == 0) return 0f
        val target = (count * p).toInt().coerceIn(1, count)
        var seen = 0
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= target) return minOf(i + 1f, worst)
        }
        return worst
    }

    fun reset() { buckets.fill(0); count = 0; sum = 0.0; worst = 0f }

    private companion object { const val BUCKETS = 200 }
}

/** Cuenta cuadros a partir de los tiempos de vsync; un cuadro es atrasado si tardó más de 1,5 períodos. */
class FrameStats {
    private val durations = DurationStats()
    private var last = 0L
    var jank = 0
        private set

    /** [frameNanos] del Choreographer; [periodNanos] período de la pantalla. Una pausa larga (app quieta) no cuenta. */
    fun onFrame(frameNanos: Long, periodNanos: Long) {
        val prev = last
        last = frameNanos
        if (prev == 0L || frameNanos <= prev) return
        val dt = frameNanos - prev
        if (dt > IDLE_NANOS) return
        durations.add(dt / 1_000_000f)
        if (dt > periodNanos * 3 / 2) jank++
    }

    /**
     * Un cuadro ya medido (por ejemplo FrameMetrics.TOTAL_DURATION de Android): [durationNanos] lo que
     * tardó; es atrasado si se pasó de 1,5 períodos de la pantalla. Solo llegan cuadros dibujados, así que
     * la app quieta no cuesta nada.
     */
    fun onFrameDuration(durationNanos: Long, periodNanos: Long) {
        if (durationNanos <= 0L) return
        durations.add(durationNanos / 1_000_000f)
        if (durationNanos > periodNanos * 3 / 2) jank++
    }

    val frames: Int get() = durations.count
    val worstMs: Float get() = durations.worst
    fun p95Ms(): Float = durations.percentile(0.95f)

    /** Empieza un intervalo nuevo; [keepClock] = seguir midiendo desde el último cuadro. */
    fun reset(keepClock: Boolean = true) { durations.reset(); jank = 0; if (!keepClock) last = 0L }

    private companion object { const val IDLE_NANOS = 250_000_000L }
}

object DeviceStateLog {
    fun encode(s: DeviceSample): String = buildString {
        append('{')
        append("\"t\":").append(s.t)
        str("ev", s.event); str("scr", s.screen)
        num("bat", s.battery); bool("chg", s.charging); dec("tmp", s.batteryTempC)
        num("thr", s.thermal); bool("ps", s.powerSave)
        num("heap", s.heapUsedMb); num("heapMax", s.heapMaxMb); num("ram", s.ramAvailMb); bool("low", s.lowMemory)
        dec("hz", s.refreshHz); num("fr", s.frames); num("jank", s.jank); dec("worst", s.worstFrameMs); dec("p95", s.p95FrameMs)
        num("draws", s.inkDraws); dec("drawAvg", s.inkDrawAvgMs); dec("drawWorst", s.inkDrawWorstMs)
        num("strokes", s.strokes); dec("ink", s.inking)
        append('}')
    }

    private fun StringBuilder.str(k: String, v: String) {
        append(",\"").append(k).append("\":\"")
        for (c in v.take(64)) if (c == '"' || c == '\\' || c.isISOControl()) append('_') else append(c)
        append('"')
    }
    private fun StringBuilder.num(k: String, v: Int) { append(",\"").append(k).append("\":").append(v) }
    private fun StringBuilder.bool(k: String, v: Boolean) { append(",\"").append(k).append("\":").append(v) }
    private fun StringBuilder.dec(k: String, v: Float) {
        append(",\"").append(k).append("\":")
        if (v.isNaN() || v.isInfinite()) append("null") else append(String.format(Locale.ROOT, "%.2f", v))
    }

    /** Lee una línea de [encode]; null si está rota. Tolera campos de más o de menos. */
    fun decode(line: String): DeviceSample? {
        val l = line.trim()
        if (!l.startsWith("{") || !l.endsWith("}")) return null
        val map = HashMap<String, String>()
        val re = Regex("\"([A-Za-z]+)\":(\"[^\"]*\"|[^,}]+)")
        for (m in re.findAll(l)) map[m.groupValues[1]] = m.groupValues[2].trim('"')
        val t = map["t"]?.toLongOrNull() ?: return null
        fun i(k: String, d: Int) = map[k]?.toIntOrNull() ?: d
        fun f(k: String, d: Float) = map[k]?.toFloatOrNull() ?: d
        fun b(k: String) = map[k] == "true"
        return DeviceSample(
            t = t, event = map["ev"] ?: "tick", screen = map["scr"] ?: "",
            battery = i("bat", -1), charging = b("chg"), batteryTempC = f("tmp", Float.NaN),
            thermal = i("thr", -1), powerSave = b("ps"),
            heapUsedMb = i("heap", -1), heapMaxMb = i("heapMax", -1), ramAvailMb = i("ram", -1), lowMemory = b("low"),
            refreshHz = f("hz", 0f), frames = i("fr", 0), jank = i("jank", 0), worstFrameMs = f("worst", 0f), p95FrameMs = f("p95", 0f),
            inkDraws = i("draws", 0), inkDrawAvgMs = f("drawAvg", 0f), inkDrawWorstMs = f("drawWorst", 0f),
            strokes = i("strokes", -1), inking = f("ink", 0f),
        )
    }

    /** Informe legible: por pantalla, cuadros atrasados, dibujo de la hoja, memoria, temperatura y batería. */
    fun summarize(samples: List<DeviceSample>): String {
        if (samples.isEmpty()) return "Sin muestras todavía."
        val sorted = samples.sortedBy { it.t }
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }
        fun pct(a: Int, b: Int) = if (b == 0) "0 %" else "${(a * 1000f / b).roundToInt() / 10f} %"
        fun f1(v: Float) = String.format(Locale.ROOT, "%.1f", v)

        val minutes = (sorted.last().t - sorted.first().t) / 60_000f
        line("Trazo · estado del dispositivo")
        line("Muestras: ${sorted.size} en ${f1(minutes)} min")
        sorted.lastOrNull { it.refreshHz > 0f }?.let { line("Pantalla: ${f1(it.refreshHz)} Hz") }

        line("")
        line("Por pantalla (cuadros atrasados / dibujados, peor cuadro, dibujo de la hoja):")
        sorted.filter { it.frames > 0 || it.inkDraws > 0 }.groupBy { it.screen.ifEmpty { "?" } }
            .entries.sortedByDescending { e -> e.value.sumOf { it.frames } }
            .forEach { (screen, list) ->
                val fr = list.sumOf { it.frames }
                val jank = list.sumOf { it.jank }
                val worst = list.maxOf { it.worstFrameMs }
                val p95 = list.maxOf { it.p95FrameMs }
                val draws = list.sumOf { it.inkDraws }
                val drawAvg = if (draws == 0) 0f else list.sumOf { (it.inkDrawAvgMs * it.inkDraws).toDouble() }.toFloat() / draws
                val drawWorst = list.maxOf { it.inkDrawWorstMs }
                val strokes = list.maxOf { it.strokes }
                line(
                    "- $screen: ${pct(jank, fr)} ($jank/$fr), peor ${f1(worst)} ms, p95 máx ${f1(p95)} ms" +
                        (if (draws > 0) "; hoja ${f1(drawAvg)} ms prom, peor ${f1(drawWorst)} ms en $draws dibujos" else "") +
                        (if (strokes >= 0) "; hasta $strokes trazos" else ""),
                )
            }

        val inking = sorted.filter { it.inking > 0.05f }
        if (inking.isNotEmpty()) {
            val fr = inking.sumOf { it.frames }
            line("Escribiendo (lápiz apoyado): ${pct(inking.sumOf { it.jank }, fr)} de cuadros atrasados en $fr cuadros")
        }

        line("")
        val heap = sorted.filter { it.heapUsedMb >= 0 }
        if (heap.isNotEmpty()) {
            val peak = heap.maxBy { it.heapUsedMb }
            line("Memoria: pico ${peak.heapUsedMb}/${peak.heapMaxMb} MB de heap (${peak.screen}); RAM libre mín ${heap.filter { it.ramAvailMb >= 0 }.minOfOrNull { it.ramAvailMb } ?: -1} MB")
        }
        val lows = sorted.count { it.lowMemory || it.event.startsWith("trim:") }
        if (lows > 0) line("Avisos de poca memoria: $lows")

        val thermal = sorted.filter { it.thermal >= 0 }
        if (thermal.isNotEmpty()) {
            val hot = thermal.count { it.thermal >= 2 }
            line("Temperatura: estado térmico máx ${thermal.maxOf { it.thermal }}; con calor (≥2) ${pct(hot, thermal.size)} de las muestras")
        }
        sorted.filter { !it.batteryTempC.isNaN() }.maxOfOrNull { it.batteryTempC }?.let { line("Batería: máx ${f1(it)} °C") }

        // Consumo: solo tramos sin cargar, en %/hora.
        var drop = 0
        var ms = 0L
        sorted.zipWithNext().forEach { (a, b) ->
            if (!a.charging && !b.charging && a.battery >= 0 && b.battery >= 0 && b.t > a.t && b.t - a.t < 10 * 60_000L && b.event != "start") {
                drop += (a.battery - b.battery).coerceAtLeast(0)
                ms += b.t - a.t
            }
        }
        if (ms > 5 * 60_000L) line("Consumo sin cargar: ${f1(drop / (ms / 3_600_000f))} %/hora")
        if (sorted.any { it.powerSave }) line("Ahorro de energía activo en ${pct(sorted.count { it.powerSave }, sorted.size)} de las muestras")
        return out.toString().trimEnd()
    }
}
