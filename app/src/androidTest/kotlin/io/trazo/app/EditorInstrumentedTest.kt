package io.trazo.app

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.data.TrazoSettings
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.DefaultPatterns
import io.trazo.core.InkController
import io.trazo.core.InkEvent
import io.trazo.core.InkStroke
import io.trazo.core.Tool
import io.trazo.core.fixtures.HandScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la app nativa con eventos de lápiz SINTETIZADOS (emulador o
 * dispositivo). No reemplazan la validación física con S Pen (docs/test-results.md).
 */
@RunWith(AndroidJUnit4::class)
class EditorInstrumentedTest {
    private val app get() = ApplicationProvider.getApplicationContext<TrazoApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var notebookId: String
    private lateinit var pen: StylusDriver

    @Before fun setUp() {
        app.repository.restoreDefaultPatterns()
        // Pausa de 1 s (la de diseño de estas pruebas), no la de 500 ms por defecto: el emulador de CI
        // cargado frena la inyección más de medio segundo y la app ve, con razón, una pausa cumplida
        // en medio de un trazo libre. Los 500 ms se prueban en JVM con reloj sintético
        // (Dwell500msRegressionTest).
        app.settings.update { TrazoSettings(holdMillis = 1000L) }
        notebookId = app.repository.createNotebook("Prueba instrumentada").id
        scenario = ActivityScenario.launch(
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTEBOOK_ID, notebookId),
        )
        waitForCanvas()
        pen = StylusDriver { canvas() }
    }

    @After fun tearDown() {
        scenario.close()
        app.repository.deleteNotebook(notebookId)
    }

    private fun canvas(): InkCanvasView {
        var v: InkCanvasView? = null
        scenario.onActivity { v = it.window.decorView.findViewWithTag(InkCanvasView.TAG) }
        return v ?: error("Sin lienzo")
    }

    private fun waitForCanvas() {
        repeat(100) {
            var ready = false
            scenario.onActivity { a ->
                val v = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG)
                ready = v != null && v.width > 0 && v.controller != null && v.pxPerUnit > 0f
            }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(100)
        }
        fail("El lienzo no apareció")
    }

    private fun <T> onController(block: (InkController) -> T): T {
        var r: T? = null
        scenario.onActivity { a -> r = block(a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG).controller!!) }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun strokes(): List<InkStroke> = onController { it.page.strokes.toList() }

    /** Para los mensajes de falla: patrón (o "libre") y cantidad de muestras de cada trazo. */
    private fun describe(s: List<InkStroke>) = s.joinToString(prefix = "[", postfix = "]") { "${it.patternId ?: "libre"}/${it.points.size}" }

    /** Fija la pausa y espera a que llegue al controlador (pasa por Compose: no es inmediato). */
    private fun useHoldMillis(ms: Long) {
        app.settings.update { it.copy(holdMillis = ms) }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (onController { it.holdMillis } != ms && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("pausa aplicada", ms, onController { it.holdMillis })
    }

    @Test fun t01_freeDrawingCreatesAStrokeWithSamples() {
        val stalls = pen.freeStroke(listOf(150f to 300f, 400f to 360f, 600f to 320f))
        val all = strokes()
        assertEquals("un trazo libre: ${describe(all)}, hueco ${pen.maxGapMs} ms, intentos frenados $stalls", 1, all.size)
        val s = all.single()
        assertTrue(s.points.size > 20)
        assertNull(s.patternId)
        assertTrue(s.points.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
    }

    @Test fun t02_integralHeldOneSecondBecomesHandwrittenOutput() {
        pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 1300, tremorPx = 1.5f)
        val s = strokes()
        assertEquals(DefaultPatterns.integral().outputInk.size, s.size)
        assertTrue(s.all { it.patternId == DefaultPatterns.INTEGRAL && it.points.size > 10 })
    }

    @Test fun t03_liftingBeforeThePauseEndsNeverConverts() {
        // Se levanta tras 1 s quieto con una pausa de 4 s. El emulador de CI cargado llega a frenar
        // la inyección más de un segundo y la app ve, con razón, una pausa cumplida (con pausa de
        // 1 s y 800 ms quieto el UP llegó tarde). El borde exacto (levantar a 900 ms con pausa de
        // 1 s) lo prueba T03 en JVM con reloj sintético.
        useHoldMillis(4000L)
        pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 1000)
        val s = strokes()
        assertTrue("sin conversión (hueco máx. ${pen.maxGapMs} ms): ${describe(s)}", s.size == 1 && s[0].patternId == null)
    }

    @Test fun t10_twoStrokeRootIsReplacedAsVectors() {
        pen.stroke(listOf(150f to 260f, 168f to 250f, 195f to 310f, 240f to 160f))
        SystemClock.sleep(150)
        pen.stroke(listOf(240f to 160f, 300f to 162f, 360f to 160f), holdMs = 1300)
        val s = strokes()
        assertEquals(2, s.size)
        assertTrue(s.all { it.patternId == DefaultPatterns.ROOT })
    }

    @Test fun t11_fractionLineBecomesHandwrittenBar() {
        pen.stroke(HandScript.fractionGesture(200f, 500f, 220f), holdMs = 1300)
        assertEquals(DefaultPatterns.FRACTION, strokes().single().patternId)
    }

    @Test fun t12_outputCanBeWrittenOverAndPartiallyErased() {
        pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 1300)
        val output = strokes().single()
        // Escritura nueva sobre la salida (un límite arriba a la derecha), lejos de la goma.
        val top = output.bounds
        pen.stroke(listOf(top.right + 4f to top.top - 30f, top.right + 30f to top.top))
        onController { it.tool = Tool.ERASER }
        val mid = output.points[output.points.size / 2]
        pen.stroke(listOf(mid.x - 4f to mid.y, mid.x + 4f to mid.y))
        val s = strokes()
        assertEquals(2, s.count { it.patternId == DefaultPatterns.INTEGRAL })
        assertEquals(1, s.count { it.patternId == null })
    }

    @Test fun t13_t14_undoAndRedoConversionAsSingleActions() {
        pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 1300)
        val output = strokes()
        assertTrue(onController { it.undo() })
        val restored = strokes().single()
        assertNull(restored.patternId)
        assertTrue(onController { it.redo() })
        assertEquals(output, strokes())
    }

    @Test fun t17_notesAndPatternsPersistAcrossRecreateAndBackground() {
        pen.stroke(listOf(150f to 300f, 400f to 360f))
        pen.stroke(HandScript.integralGesture(500f, 150f, 200f), holdMs = 1300)
        val before = strokes()
        // Sin esto la prueba pasaría vacía si se perdieran los trazos inyectados.
        assertTrue("trazo libre", before.any { it.patternId == null })
        assertTrue("integral convertida", before.any { it.patternId == DefaultPatterns.INTEGRAL })
        scenario.moveToState(Lifecycle.State.CREATED) // ON_STOP → guardado inmediato
        val saved = app.repository.loadNotebook(notebookId)!!.pages[0].strokes
        assertEquals(before, saved)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.recreate()
        waitForCanvas()
        assertEquals(before, strokes())
        assertTrue(app.repository.patterns.value.any { it.id == DefaultPatterns.INTEGRAL })
    }

    /** Termina un contacto pendiente: el UP inyectado puede llegar tarde y cerrar el trazo después. */
    private fun settleContact() {
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (onController { it.isContactActive } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        if (onController { it.isContactActive }) onController { it.cancel() }
        instrumentation.waitForIdleSync()
    }

    @Test fun t20_recreatingDuringAGestureCancelsSafely() {
        // Pausa de 60 s: con 2 s y con 10 s, el emulador de CI cargado frenó el hilo principal lo
        // suficiente para que la pausa se cumpliera antes de que llegara la recreación (run 50).
        // Lo que se prueba es que recrear cancele el contacto en curso, no cuánto tarda el emulador.
        useHoldMillis(60_000L)
        // Precondición, no lo que se prueba: el gesto tiene que seguir en curso. Si el emulador lo
        // cerró o convirtió igual (pasó en CI, sin hueco largo), se limpia la hoja, se informa con los
        // eventos del controlador y se repite.
        var problem: String? = null
        val problems = mutableListOf<String>()
        for (attempt in 1..3) {
            // Hoja vacía y sin contacto antes de cada intento. clearPage y no deshacer: deshacer no
            // limpia trazos que no estén en el historial y dejaba restos para el intento siguiente.
            settleContact()
            val leftover = describe(strokes())
            onController { it.clearPage() }
            instrumentation.waitForIdleSync()
            val hold = onController { it.holdMillis }
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val t0 = SystemClock.uptimeMillis()
            val listener: (InkEvent) -> Unit = { e ->
                val dt = SystemClock.uptimeMillis() - t0
                when (e) {
                    is InkEvent.Converted -> events += "Converted(${e.pattern.id}, ${"%.2f".format(e.score)}) a los $dt ms"
                    is InkEvent.Ambiguous -> events += "Ambiguous a los $dt ms"
                    is InkEvent.NotRecognized -> events += "NotRecognized a los $dt ms"
                    else -> Unit
                }
            }
            onController { it.addListener(listener) }
            pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 300, lift = false)
            val took = SystemClock.uptimeMillis() - t0
            onController { it.removeListener(listener) }
            if (onController { it.isContactActive } && strokes().isEmpty()) { problem = null; break }
            val patternHolds = app.repository.patterns.value.filter { it.enabled && it.holdMillis != null }
                .joinToString { "${it.id}=${it.holdMillis}" }
            problem = "intento $attempt: contacto=${onController { it.isContactActive }}, pausa=$hold→" +
                "${onController { it.holdMillis }} ms, pausas propias=[$patternHolds], trazo $took ms, " +
                "hueco máx. ${pen.maxGapMs} ms, hoja previa $leftover, eventos=$events: ${describe(strokes())}"
            problems += problem
            android.util.Log.w("TrazoTest", "t20 $problem")
            pen.liftAt(150f, 150f)
        }
        assertNull("gesto en curso antes de recrear (${problems.joinToString(" | ")})", problem)
        // Eventos del controlador durante la recreación, para saber cuándo apareció un trazo si falla.
        val t0 = SystemClock.uptimeMillis()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val listener: (InkEvent) -> Unit = { e ->
            if (e is InkEvent.Converted) events += "Converted(${e.pattern.id}) a los ${SystemClock.uptimeMillis() - t0} ms"
        }
        onController { it.addListener(listener) }
        scenario.recreate()
        val recreated = SystemClock.uptimeMillis() - t0
        waitForCanvas()
        assertFalse("recrear cancela el contacto", onController { it.isContactActive })
        pen.liftAt(150f, 150f)
        instrumentation.waitForIdleSync()
        onController { it.removeListener(listener) }
        assertTrue(
            "sin conversión ni trazo fantasma: ${describe(strokes())}, recreado a los $recreated ms, eventos=$events",
            strokes().isEmpty(),
        )
        // La hoja sigue funcionando después de recrear.
        pen.stroke(listOf(300f to 600f, 500f to 650f))
        assertEquals(1, strokes().size)
    }

    @Test fun t18_zoomKeepsDocumentGeometry() {
        pen.stroke(listOf(200f to 200f, 300f to 300f))
        val before = strokes().single()
        SystemClock.sleep(800) // fuera de la ventana de rechazo de palma tras usar el lápiz
        var center = 0f to 0f
        // Pellizco con dos dedos, despachado directamente a la vista.
        scenario.onActivity { a ->
            val v = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG)
            val cx = v.width / 2f
            val cy = v.height / 2f
            val t = SystemClock.uptimeMillis()
            fun ev(action: Int, d: Float, n: Int, time: Long): MotionEvent {
                val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { x = cx + (if (i == 0) -d else d); y = cy; pressure = 1f; size = 1f } }
                return MotionEvent.obtain(t, time, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            }
            val scale0 = v.pxPerUnit
            // ScaleGestureDetector ignora separaciones menores a ~27 mm: se abre de 300 a 1200 px.
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 150f, 1, t))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f, 2, t + 10))
            for (k in 1..15) v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 150f + k * 30f, 2, t + 10 + k * 16))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 600f, 2, t + 300))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 600f, 1, t + 310))
            assertTrue("zoom aplicado", v.pxPerUnit > scale0 * 1.5f)
            center = v.screenToDocument(v.width / 2f, v.height / 2f)
        }
        assertEquals(before, strokes().single())
        // Un trazo nuevo después del zoom cae en las coordenadas de documento correctas.
        val (cx, cy) = center
        pen.stroke(listOf(cx to cy, cx + 20f to cy + 20f))
        val p0 = strokes().last().points.first()
        assertEquals(cx, p0.x, 2f)
        assertEquals(cy, p0.y, 2f)
    }

    @Test fun t25_twoFingerTapUndoesTheLastChange() {
        pen.stroke(listOf(150f to 300f, 400f to 360f))
        assertEquals(1, strokes().size)
        SystemClock.sleep(800) // fuera de la ventana de rechazo de palma
        scenario.onActivity { a ->
            val v = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG)
            val t = SystemClock.uptimeMillis()
            fun ev(action: Int, n: Int, time: Long): MotionEvent {
                val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { x = 300f + i * 150f; y = 600f; pressure = 1f; size = 0.1f } }
                return MotionEvent.obtain(t, time, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            }
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 1, t))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, t + 20))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, t + 90))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 1, t + 110))
        }
        assertTrue("deshecho con dos dedos", strokes().isEmpty())
    }

    @Test fun t19_fingerTouchWhileStylusIsDownDoesNotDraw() {
        scenario.onActivity { a ->
            val v = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG)
            val t = SystemClock.uptimeMillis()
            val stylus = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val palm = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
            fun c(x: Float, y: Float) = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.7f; size = 0.2f }
            fun ev(action: Int, time: Long, sx: Float, n: Int) = MotionEvent.obtain(
                t, time, action, n, if (n == 1) arrayOf(stylus) else arrayOf(stylus, palm),
                if (n == 1) arrayOf(c(sx, 200f)) else arrayOf(c(sx, 200f), c(400f, 700f)), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0,
            )
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 100f, 1))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + 8, 110f, 2))
            for (k in 1..20) v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, t + 8 + k * 8, 110f + k * 10f, 2))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + 200, 310f, 2))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, t + 210, 310f, 1))
        }
        val s = strokes().single()
        assertTrue("el trazo es del lápiz", s.bounds.height < 30f)
    }

    @Test fun t19b_actionCancelDiscardsTheStroke() {
        scenario.onActivity { a ->
            val v = a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG)
            val t = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            fun ev(action: Int, time: Long, x: Float) = MotionEvent.obtain(
                t, time, action, 1, props, arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 300f; pressure = 0.7f }),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0,
            )
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 100f))
            for (k in 1..10) v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, t + k * 8, 100f + k * 10))
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_CANCEL, t + 100, 200f))
        }
        assertTrue(strokes().isEmpty())
    }

    @Test fun t23_noWebViewInTheHierarchy() {
        scenario.onActivity { a ->
            fun walk(v: View): Boolean = v.javaClass.name.contains("WebView") ||
                (v is ViewGroup && (0 until v.childCount).any { walk(v.getChildAt(it)) })
            assertFalse(walk(a.window.decorView))
            assertNotNull(a.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG))
        }
    }
}
