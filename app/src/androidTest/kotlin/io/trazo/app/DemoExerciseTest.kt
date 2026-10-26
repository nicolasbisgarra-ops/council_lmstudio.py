package io.trazo.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.data.TrazoSettings
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.Bounds
import io.trazo.core.DefaultPatterns
import io.trazo.core.InkStroke
import io.trazo.core.bounds
import io.trazo.core.fixtures.HandScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEMO AUTOMATIZADA (T22 en emulador): la app Android nativa recibe eventos de lápiz
 * sintetizados que escriben y resuelven ∫₀² (3x² + 2x) dx = 12, más una raíz y una fracción,
 * usando los gestos con pausa de 1 s. Pensada para grabarse con `adb shell screenrecord`
 * (scripts/ci-emulator.sh). Un cartel de la interfaz lo identifica como automatizado.
 * No es una grabación con S Pen físico.
 */
@RunWith(AndroidJUnit4::class)
class DemoExerciseTest {
    private val app get() = ApplicationProvider.getApplicationContext<TrazoApp>()

    @Test fun solveFullIntegralExerciseByHand() {
        app.repository.restoreDefaultPatterns()
        app.settings.update { TrazoSettings() }
        val nb = app.repository.createNotebook("Demo: integral resuelta a mano")
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTEBOOK_ID, nb.id)
                .putExtra(MainActivity.EXTRA_DEMO_BANNER, "DEMO AUTOMATIZADA · lápiz sintetizado en emulador (no es S Pen físico)"),
        )
        fun canvas(): InkCanvasView {
            var v: InkCanvasView? = null
            scenario.onActivity { v = it.window.decorView.findViewWithTag(InkCanvasView.TAG) }
            return v!!
        }
        for (i in 0 until 100) {
            if (runCatching { canvas().width > 0 && canvas().controller != null }.getOrDefault(false)) break
            SystemClock.sleep(100)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(1500)
        val pen = StylusDriver { canvas() }

        fun strokes(): List<InkStroke> { var r = emptyList<InkStroke>(); scenario.onActivity { a -> r = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG).controller!!.page.strokes.toList() }; return r }
        fun lastOutput(): Bounds = strokes().filter { it.patternId != null }.let { all -> all.filter { it.groupId == all.last().groupId }.bounds() }
        fun write(text: String, x: Float, y: Float, size: Float): Float {
            val (paths, end) = HandScript.write(text, x, y, size, seed = text.hashCode())
            for (p in paths) { pen.stroke(p, sampleMs = 6); SystemClock.sleep(90) }
            return end
        }

        // 1) Gesto de integral + pausa de 1 s → tinta de salida manuscrita.
        pen.stroke(HandScript.integralGesture(70f, 60f, 170f), holdMs = 1400, tremorPx = 1.5f)
        SystemClock.sleep(1500)
        pen.screenshot("trazo-demo-1-integral")
        val integral = lastOutput()
        write("2", integral.right + 2f, integral.top - 8f, 34f)
        write("0", integral.centerX + 4f, integral.bottom - 24f, 30f)
        var x = write("(3x^2+2x)", integral.right + 40f, integral.centerY - 34f, 62f)
        write("dx", x + 22f, integral.centerY - 34f, 62f)
        SystemClock.sleep(600)
        x = write("=[x^3+x^2]", 120f, 300f, 62f)
        write("2", x + 4f, 285f, 32f)
        write("0", x + 4f, 352f, 32f)
        write("=(8+4)-0", 120f, 430f, 62f)
        write("=12", 120f, 560f, 62f)
        SystemClock.sleep(1000)
        pen.screenshot("trazo-demo-2-integral-resuelta")

        // 2) Raíz: √16 = 4
        pen.stroke(HandScript.rootGesture(120f, 720f, 95f, 70f), holdMs = 1400, tremorPx = 1.5f)
        SystemClock.sleep(1500)
        val root = lastOutput()
        x = write("16", root.left + root.width * 0.42f, root.top + root.height * 0.3f, root.height * 0.58f)
        write("=4", maxOf(x, root.right) + 25f, root.top + root.height * 0.3f, root.height * 0.58f)

        // 3) Fracción: 6/3 = 2
        pen.stroke(HandScript.fractionGesture(100f, 1010f, 110f), holdMs = 1400, tremorPx = 1f)
        SystemClock.sleep(1500)
        val bar = lastOutput()
        write("6", bar.centerX - 18f, bar.top - 72f, 58f)
        write("3", bar.centerX - 18f, bar.bottom + 14f, 58f)
        write("=2", bar.right + 30f, bar.centerY - 34f, 62f)
        SystemClock.sleep(1500)
        pen.screenshot("trazo-demo-3-final")

        val s = strokes()
        val groups = s.filter { it.patternId != null }.groupBy { it.groupId }.mapValues { it.value.first().patternId }
        assertEquals(setOf(DefaultPatterns.INTEGRAL, DefaultPatterns.ROOT, DefaultPatterns.FRACTION), groups.values.toSet())
        assertTrue(s.size > 40)
        scenario.close()
    }
}
