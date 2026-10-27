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
        app.settings.update { TrazoSettings() }
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

    @Test fun t01_freeDrawingCreatesAStrokeWithSamples() {
        pen.stroke(listOf(150f to 300f, 400f to 360f, 600f to 320f))
        val s = strokes().single()
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
        // Se levanta a mitad de una pausa de 2 s. Con la de 1 s y 800 ms quieto, el emulador de CI
        // cargado entregó el UP tarde y la app vio, con razón, una pausa completa. El borde exacto
        // (levantar a 900 ms con pausa de 1 s) lo prueba T03 en JVM con reloj sintético.
        app.settings.update { it.copy(holdMillis = 2000L) }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (onController { it.holdMillis } != 2000L && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("pausa de 2 s aplicada", 2000L, onController { it.holdMillis })
        pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 1000)
        val s = strokes().single()
        assertNull(s.patternId)
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

    @Test fun t20_recreatingDuringAGestureCancelsSafely() {
        // Pausa larga: si la recreación tarda, un lápiz quieto 1 s convertiría con toda razón.
        app.settings.update { it.copy(holdMillis = 2000L) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        pen.stroke(HandScript.integralGesture(150f, 150f, 220f), holdMs = 300, lift = false)
        scenario.recreate()
        waitForCanvas()
        pen.liftAt(150f, 150f)
        SystemClock.sleep(2500)
        assertTrue("sin conversión ni trazo fantasma", strokes().isEmpty())
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
