package io.trazo.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateLogTest {
    @Test fun `ida y vuelta de una muestra`() {
        val s = DeviceSample(
            t = 1_700_000_000_000L, event = "tick", screen = "Editor", battery = 81, charging = false, batteryTempC = 33.5f,
            thermal = 1, powerSave = true, heapUsedMb = 120, heapMaxMb = 512, ramAvailMb = 2048, lowMemory = false,
            refreshHz = 120f, frames = 1700, jank = 12, worstFrameMs = 48.25f, p95FrameMs = 9f,
            inkDraws = 300, inkDrawAvgMs = 2.5f, inkDrawWorstMs = 14f, strokes = 950, inking = 0.4f,
        )
        assertEquals(s, DeviceStateLog.decode(DeviceStateLog.encode(s)))
    }

    @Test fun `sin temperatura y con texto raro no se rompe`() {
        val s = DeviceSample(t = 5L, screen = "a\"b\\c\nd")
        val line = DeviceStateLog.encode(s)
        assertTrue(line, "\"tmp\":null" in line)
        val back = DeviceStateLog.decode(line)!!
        assertTrue(back.batteryTempC.isNaN())
        assertEquals("a_b_c_d", back.screen)
        assertNull(DeviceStateLog.decode("basura"))
        assertNull(DeviceStateLog.decode("{\"ev\":\"x\"}"))
    }

    @Test fun `cuadros atrasados y pausas`() {
        val period = 8_333_333L // 120 Hz
        val f = FrameStats()
        var t = 1_000_000_000L
        f.onFrame(t, period)
        repeat(100) { t += period; f.onFrame(t, period) }
        t += period * 3; f.onFrame(t, period) // uno atrasado
        t += 2_000_000_000L; f.onFrame(t, period) // app quieta: no cuenta
        assertEquals(101, f.frames)
        assertEquals(1, f.jank)
        assertEquals(25f, f.worstMs, 0.1f)
        assertTrue(f.p95Ms() <= 9f)
        f.reset()
        assertEquals(0, f.frames)
    }

    @Test fun `cuadros con duracion medida`() {
        val period = 16_666_667L // 60 Hz
        val f = FrameStats()
        repeat(9) { f.onFrameDuration(10_000_000L, period) }
        f.onFrameDuration(40_000_000L, period)
        f.onFrameDuration(0L, period)
        assertEquals(10, f.frames)
        assertEquals(1, f.jank)
        assertEquals(40f, f.worstMs, 0.01f)
    }

    @Test fun `percentiles del histograma`() {
        val d = DurationStats()
        repeat(90) { d.add(2f) }
        repeat(10) { d.add(50f) }
        assertEquals(3f, d.percentile(0.5f), 0.01f)
        assertEquals(50f, d.percentile(0.99f), 0.01f)
        assertEquals(6.8f, d.average, 0.01f)
    }

    @Test fun `el resumen junta pantallas, memoria y consumo`() {
        val base = 1_000_000L
        val samples = (0 until 40).map { i ->
            DeviceSample(
                t = base + i * 15_000L, screen = if (i < 30) "Editor" else "Notebooks", battery = 90 - i / 10,
                heapUsedMb = 100 + i, heapMaxMb = 512, ramAvailMb = 3000 - i, thermal = if (i > 35) 2 else 0,
                refreshHz = 120f, frames = 1000, jank = if (i < 30) 20 else 0, worstFrameMs = 30f, p95FrameMs = 10f,
                inkDraws = if (i < 30) 100 else 0, inkDrawAvgMs = 3f, inkDrawWorstMs = 12f,
                strokes = if (i < 30) 500 else -1, inking = if (i < 10) 0.5f else 0f,
            )
        } + DeviceSample(t = base + 41 * 15_000L, event = "trim:15", screen = "Editor")
        val text = DeviceStateLog.summarize(samples)
        assertTrue(text, "- Editor: 2.0 % (600/30000)" in text)
        assertTrue(text, "hoja 3.0 ms prom" in text)
        assertTrue(text, "hasta 500 trazos" in text)
        assertTrue(text, "- Notebooks: 0 % (0/10000)" in text || "- Notebooks: 0.0 % (0/10000)" in text)
        assertTrue(text, "pico 139/512 MB" in text)
        assertTrue(text, "Avisos de poca memoria: 1" in text)
        assertTrue(text, "Escribiendo" in text)
        assertTrue(text, "Consumo sin cargar" in text)
        assertEquals("Sin muestras todavía.", DeviceStateLog.summarize(emptyList()))
    }

    @Test fun `acciones ida y vuelta y separadas de las muestras`() {
        val a = DeviceAction(10L, "undo", "Editor")
        val line = DeviceStateLog.encodeAction(a)
        assertEquals(a.copy(ms = Float.NaN).toString(), DeviceStateLog.decodeAction(line).toString())
        assertNull(DeviceStateLog.decode(line))
        assertNull(DeviceStateLog.decodeAction(DeviceStateLog.encode(DeviceSample(t = 3L))))
        val b = DeviceStateLog.decodeAction(DeviceStateLog.encodeAction(DeviceAction(11L, "slow-frame", "Editor", 48f)))!!
        assertEquals(48f, b.ms, 0.01f)
    }

    @Test fun `los momentos trabados muestran lo que se hacia a esa hora`() {
        val samples = (0..4).map { i -> DeviceSample(t = i * 5_000L, screen = "Editor", frames = 600, jank = if (i == 3) 40 else 0, heapUsedMb = 100) }
        val actions = listOf(
            DeviceAction(11_000L, "touch:stylus", "Editor"),
            DeviceAction(12_000L, "PageChanged", "Editor"),
            DeviceAction(13_000L, "vm:undo", "Editor"),
            DeviceAction(13_500L, "slow-frame", "Editor", 70f),
            DeviceAction(1_000L, "vm:addPage", "Editor"),
        )
        val m = DeviceStateLog.slowMoments(samples, actions)
        assertEquals(1, m.size)
        assertTrue(m[0], "40/600 atrasados" in m[0])
        assertTrue(m[0], "cuadro más lento 70 ms" in m[0])
        assertTrue(m[0], "vm:undo ×1" in m[0] && "touch:stylus ×1" in m[0] && "PageChanged ×1" in m[0])
        assertTrue(m[0], "addPage" !in m[0])
        val text = DeviceStateLog.summarize(samples, actions)
        assertTrue(text, "Momentos más trabados" in text)
    }
}
