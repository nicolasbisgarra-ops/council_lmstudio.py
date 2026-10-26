package io.trazo.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.trazo.app.data.TrazoSettings
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.Bounds
import io.trazo.core.DefaultPatterns
import io.trazo.core.InkStroke
import io.trazo.core.bounds
import io.trazo.core.fixtures.HandScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * DEMO AUTOMATIZADA (T22 en emulador): la app Android nativa recibe eventos de lápiz
 * sintetizados que escriben y resuelven ∫₀² (3x² + 2x) dx = 12, más una raíz y una fracción,
 * usando los gestos con pausa de 1 s; después enseña un gesto desconocido desde la hoja y lo
 * reutiliza. Pensada para grabarse con `adb shell screenrecord` (scripts/ci-emulator.sh).
 * Un cartel de la interfaz lo identifica como automatizado. No es una grabación con S Pen físico.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DemoExerciseTest {
    private val app get() = ApplicationProvider.getApplicationContext<TrazoApp>()

    @Test fun a_solveFullIntegralExerciseByHand() = DemoSession(app, "Demo: integral resuelta a mano").use { s ->
        val pen = s.pen

        // 1) Gesto de integral + pausa de 1 s → tinta de salida manuscrita.
        pen.stroke(HandScript.integralGesture(70f, 60f, 170f), holdMs = 1400, tremorPx = 1.5f)
        SystemClock.sleep(1500)
        pen.screenshot("trazo-demo-1-integral")
        val integral = s.lastOutput()
        s.write("2", integral.right + 2f, integral.top - 8f, 34f)
        s.write("0", integral.centerX + 4f, integral.bottom - 24f, 30f)
        var x = s.write("(3x^2+2x)", integral.right + 40f, integral.centerY - 34f, 62f)
        s.write("dx", x + 22f, integral.centerY - 34f, 62f)
        SystemClock.sleep(600)
        x = s.write("=[x^3+x^2]", 120f, 300f, 62f)
        s.write("2", x + 4f, 285f, 32f)
        s.write("0", x + 4f, 352f, 32f)
        s.write("=(8+4)-0", 120f, 430f, 62f)
        s.write("=12", 120f, 560f, 62f)
        SystemClock.sleep(1000)
        pen.screenshot("trazo-demo-2-integral-resuelta")

        // 2) Raíz: √16 = 4
        pen.stroke(HandScript.rootGesture(120f, 720f, 95f, 70f), holdMs = 1400, tremorPx = 1.5f)
        SystemClock.sleep(1500)
        val root = s.lastOutput()
        x = s.write("16", root.left + root.width * 0.42f, root.top + root.height * 0.3f, root.height * 0.58f)
        s.write("=4", maxOf(x, root.right) + 25f, root.top + root.height * 0.3f, root.height * 0.58f)

        // 3) Fracción: 6/3 = 2
        pen.stroke(HandScript.fractionGesture(100f, 1010f, 110f), holdMs = 1400, tremorPx = 1f)
        SystemClock.sleep(1500)
        val bar = s.lastOutput()
        s.write("6", bar.centerX - 18f, bar.top - 72f, 58f)
        s.write("3", bar.centerX - 18f, bar.bottom + 14f, 58f)
        s.write("=2", bar.right + 30f, bar.centerY - 34f, 62f)
        SystemClock.sleep(1500)
        pen.screenshot("trazo-demo-3-final")

        val strokes = s.strokes()
        val groups = strokes.filter { it.patternId != null }.groupBy { it.groupId }.mapValues { it.value.first().patternId }
        assertEquals(setOf(DefaultPatterns.INTEGRAL, DefaultPatterns.ROOT, DefaultPatterns.FRACTION), groups.values.toSet())
        assertTrue(strokes.size > 40)
    }

    /** Un gesto propio ("rayo") no se reconoce; se enseña desde la hoja y la próxima vez se convierte solo. */
    @Test fun b_teachAnUnknownGestureFromThePageAndReuseIt() = DemoSession(app, "Demo: enseñar un gesto").use { s ->
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun zigzag(x: Float, y: Float, step: Float, amp: Float) = (0..10).map { i -> (x + i * step) to (if (i % 2 == 0) y else y + amp) }

        s.pen.stroke(zigzag(120f, 160f, 22f, 60f), holdMs = 1400, tremorPx = 1f)
        val teach = device.wait(Until.findObject(By.text("Enseñar")), 6000) ?: error("No apareció la opción Enseñar")
        SystemClock.sleep(900)
        teach.click()
        val integral = device.wait(Until.findObject(By.text("Integral")), 6000) ?: error("No apareció la lista de patrones")
        SystemClock.sleep(1200)
        s.pen.screenshot("trazo-demo-4-ensenar")
        integral.click()
        SystemClock.sleep(1500)
        assertTrue("el gesto se reemplazó por la integral", s.strokes().all { it.patternId == DefaultPatterns.INTEGRAL })
        val learned = app.repository.patterns.value.first { it.id == DefaultPatterns.INTEGRAL }
        assertEquals(DefaultPatterns.integral().gestureExamples.size + 1, learned.gestureExamples.size)

        // El mismo gesto, en otro lugar y otro tamaño: ahora se convierte solo.
        s.pen.stroke(zigzag(480f, 620f, 26f, 75f), holdMs = 1400, tremorPx = 1f)
        SystemClock.sleep(1500)
        s.pen.screenshot("trazo-demo-5-aprendido")
        val groups = s.strokes().filter { it.patternId == DefaultPatterns.INTEGRAL }.mapNotNull { it.groupId }.toSet()
        assertEquals(2, groups.size)
        app.repository.restoreDefaultPatterns()
    }
}

/** Abre un cuaderno nuevo con el cartel de demo y ofrece el lápiz sintético y utilidades. */
private class DemoSession(private val app: TrazoApp, title: String) : AutoCloseable {
    private val scenario: ActivityScenario<MainActivity>
    val pen = StylusDriver { canvas() }

    init {
        app.repository.restoreDefaultPatterns()
        app.settings.update { TrazoSettings() }
        val nb = app.repository.createNotebook(title)
        scenario = ActivityScenario.launch(
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTEBOOK_ID, nb.id)
                .putExtra(MainActivity.EXTRA_DEMO_BANNER, "DEMO AUTOMATIZADA · lápiz sintetizado en emulador (no es S Pen físico)"),
        )
        for (i in 0 until 100) {
            if (runCatching { canvas().width > 0 && canvas().controller != null }.getOrDefault(false)) break
            SystemClock.sleep(100)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(1500)
    }

    fun canvas(): InkCanvasView {
        var v: InkCanvasView? = null
        scenario.onActivity { v = it.window.decorView.findViewWithTag(InkCanvasView.TAG) }
        return v!!
    }

    fun strokes(): List<InkStroke> {
        var r = emptyList<InkStroke>()
        scenario.onActivity { a -> r = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG).controller!!.page.strokes.toList() }
        return r
    }

    /** Tinta del último patrón insertado. */
    fun lastOutput(): Bounds = strokes().filter { it.patternId != null }.let { all -> all.filter { it.groupId == all.last().groupId }.bounds() }

    /** Escribe con la mano simulada (recorridos de HandScript, solo en pruebas). */
    fun write(text: String, x: Float, y: Float, size: Float): Float {
        val (paths, end) = HandScript.write(text, x, y, size, seed = text.hashCode())
        for (p in paths) { pen.stroke(p, sampleMs = 6); SystemClock.sleep(90) }
        return end
    }

    override fun close() = scenario.close()
}
