package io.trazo.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.ui.ReorderableItem
import io.trazo.app.ui.ReorderableState
import io.trazo.app.ui.dragHandle
import io.trazo.app.ui.rememberReorderableState
import io.trazo.core.moveItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Host aislado: no guarda preferencias ni modifica cuadernos. Sin ui-test-junit4 disponible,
 * usa eventos de dedo y reloj real, como los otros tests instrumentados del proyecto.
 */
@RunWith(AndroidJUnit4::class)
class ReorderableInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var hostView: View
    private val probe = HostProbe()
    private val finger = Finger()

    @After fun tearDown() {
        try {
            if (finger.pressed) finger.cancel()
        } finally {
            scenario?.close()
        }
    }

    @Test fun longPressMovesFiveToTheSecondPosition() {
        openHost()
        moveFiveToSecond()
        assertOrder(listOf(1, 5, 2, 3, 4) + (6..20))
        assertEquals("tres cruces vecinos, sin otra llamada al soltar", 3, main { probe.moves.size })
    }

    @Test fun stableKeysKeepCheckboxStateAndTheRelativeOrderOfOtherRows() {
        openHost()
        finger.down(main { probe.checkboxes.getValue(5).boundsInWindow().center })
        finger.up()
        await("el checkbox de la fila 5 queda marcado") { probe.checked[5] == true }

        moveFiveToSecond()

        val order = main { probe.rows.value }
        assertEquals((1..20).filter { it != 5 }, order.filter { it != 5 })
        assertOrder(listOf(1, 5, 2, 3, 4) + (6..20))
        main {
            assertTrue("el remember de la fila movida conserva la selección", probe.checked.getValue(5))
            for (id in listOf(1, 2, 3, 4, 6)) {
                assertFalse("la selección no pasa a la fila $id", probe.checked.getValue(id))
            }
        }
    }

    @Test fun holdingAtTheBottomAutoScrollsTwoThousandRows() {
        openHost(total = 2_000)
        startDraggingFive()
        val edge = main {
            Offset(finger.position.x, probe.viewport.bottom - probe.handleBounds(5).height * 0.1f)
        }
        moveFingerTo(edge)
        val beforeHold = main { probe.list.firstVisibleItemIndex }

        // No hay más MOVE: el loop de frames debe avanzar con el dedo quieto en el borde.
        await("el auto-scroll avanza con 2.000 filas", timeoutMillis = 8_000) {
            probe.list.firstVisibleItemIndex > beforeHold
        }
        main {
            assertTrue(probe.list.firstVisibleItemIndex > 0)
            assertEquals(5, probe.reorder.draggingKey)
        }
        finger.up()
        await("soltar termina el auto-scroll") { probe.reorder.draggingKey == null }
        val order = main { probe.rows.value }
        assertEquals((1..2_000).toList(), order.sorted())
        assertEquals((1..2_000).filter { it != 5 }, order.filter { it != 5 })
    }

    @Test fun cancellingBeforeLongPressDoesNotReorder() {
        openHost()
        finger.down(main { probe.handleBounds(5).center })
        finger.cancel()
        // También detecta un long-press pendiente que hubiera quedado vivo tras CANCEL.
        finger.advanceEventTime(main { probe.longPressMillis } + 100)
        main {
            assertNull(probe.reorder.draggingKey)
            assertTrue(probe.moves.isEmpty())
        }
        assertOrder((1..20).toList())
    }

    @Test fun cancellingAnActiveDragBeforeCrossingDoesNotReorder() {
        openHost()
        startDraggingFive()
        finger.moveBy(Offset(0f, -main { probe.handleBounds(5).height } * 0.2f))
        main { assertEquals(5, probe.reorder.draggingKey) }
        finger.cancel()
        await("cancelar libera la fila") { probe.reorder.draggingKey == null }
        main { assertTrue(probe.moves.isEmpty()) }
        assertOrder((1..20).toList())
    }

    @Test fun cancellingAfterCrossingKeepsTheMovesAlreadyAppliedByTheContract() {
        openHost()
        startDraggingFive()
        moveFingerTo(secondPositionTarget())
        val expected = listOf(1, 5, 2, 3, 4) + (6..20)
        await("los cruces se aplican antes de terminar el gesto") { probe.rows.value == expected }
        val movesBeforeCancel = main { probe.moves.toList() }
        finger.cancel()
        await("cancelar termina el arrastre sin otra llamada") { probe.reorder.draggingKey == null }
        assertOrder(expected)
        assertEquals(movesBeforeCancel, main { probe.moves.toList() })
    }

    private fun openHost(total: Int = 20) {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            hostView = activity.window.decorView
            activity.setContent { MaterialTheme { ReorderHost(total, probe) } }
        }
        await("el host muestra las primeras seis filas") {
            probe.ready && (1..6).all { probe.handles.containsKey(it) && probe.checkboxes.containsKey(it) }
        }
        main { assertEquals(0, probe.list.firstVisibleItemIndex) }
    }

    private fun startDraggingFive() {
        finger.down(main { probe.handleBounds(5).center })
        finger.advanceEventTime(main { probe.longPressMillis } + 100)
        await("el long-press activa el handle de la fila 5") { probe.reorder.isDragging(5) }
    }

    private fun secondPositionTarget(): Offset = main {
        val destination = probe.handleBounds(2)
        // Cruza el centro de la posición 2, sin alcanzar el de la posición 1.
        Offset(finger.position.x, destination.center.y - destination.height * 0.2f)
    }

    private fun moveFiveToSecond() {
        startDraggingFive()
        moveFingerTo(secondPositionTarget())
        await("la fila 5 llega al índice final 1 antes de soltar") { probe.rows.value.indexOf(5) == 1 }
        finger.up()
        await("soltar libera la fila") { probe.reorder.draggingKey == null }
    }

    private fun moveFingerTo(target: Offset) {
        val step = (target - finger.position) / 24f
        repeat(24) {
            finger.advanceEventTime(16)
            finger.moveBy(step)
        }
    }

    private fun assertOrder(expected: List<Int>) {
        await("el layout refleja el orden de las primeras seis filas") {
            probe.list.layoutInfo.visibleItemsInfo.take(6).map { it.key } == expected.take(6)
        }
        assertEquals(expected, main { probe.rows.value })
        // Espera también la animación de desplazamiento, no solo el modelo del callback.
        await("las filas quedan ubicadas en el orden nuevo") {
            val centers = expected.take(6).map {
                val coordinates = probe.handles[it] ?: return@await false
                if (!coordinates.isAttached) return@await false
                coordinates.boundsInWindow().center.y
            }
            val height = probe.handleBounds(expected.first()).height
            centers.zipWithNext().all { (a, b) -> abs(b - a - height) < 2f }
        }
    }

    private fun await(message: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(16)
        }
        assertTrue(message, main(condition))
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Todo el estado observado y las coordenadas se leen/escriben en el hilo principal. */
    private class HostProbe {
        var ready = false
        lateinit var rows: MutableState<List<Int>>
        lateinit var list: LazyListState
        lateinit var reorder: ReorderableState
        var longPressMillis = 0L
        var viewport = Rect.Zero
        val handles = mutableMapOf<Int, LayoutCoordinates>()
        val checkboxes = mutableMapOf<Int, LayoutCoordinates>()
        val checked = mutableMapOf<Int, Boolean>()
        val moves = mutableListOf<Pair<Int, Int>>()

        // Se consultan al leer, así incluyen la traslación actual de graphicsLayer.
        fun handleBounds(id: Int): Rect = handles.getValue(id).boundsInWindow()
    }

    @Composable
    private fun ReorderHost(total: Int, probe: HostProbe) {
        val rows = remember { mutableStateOf((1..total).toList()) }
        val list = rememberLazyListState()
        val reorder = rememberReorderableState(list) { from, to ->
            probe.moves += from to to
            rows.value = moveItem(rows.value, from, to)
        }
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        SideEffect {
            probe.rows = rows
            probe.list = list
            probe.reorder = reorder
            probe.longPressMillis = longPressMillis
            probe.ready = true
        }
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().height(384.dp).testTag("lista")
                    .onGloballyPositioned { probe.viewport = it.boundsInWindow() },
            ) {
                items(rows.value, key = { it }) { id ->
                    ReorderableItem(reorder, id, Modifier.testTag("fila-$id")) { _ ->
                        // Es estado de composición por key, no un atributo del modelo de filas.
                        var checked by remember { mutableStateOf(false) }
                        SideEffect { probe.checked[id] = checked }
                        DisposableEffect(id) {
                            onDispose {
                                probe.handles.remove(id)
                                probe.checkboxes.remove(id)
                                probe.checked.remove(id)
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).testTag("handle-$id")
                                    .onGloballyPositioned { probe.handles[id] = it }
                                    .dragHandle(reorder, id),
                                contentAlignment = Alignment.Center,
                            ) { Text("↕") }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { checked = it },
                                modifier = Modifier.testTag("seleccion-$id")
                                    .onGloballyPositioned { probe.checkboxes[id] = it },
                            )
                            Text("Fila $id")
                        }
                    }
                }
            }
        }
    }

    /** Equivalente disponible a down/advanceEventTime/moveBy/up; no simula el reloj Compose. */
    private inner class Finger {
        var position = Offset.Zero
            private set
        var pressed = false
            private set
        private var downTime = 0L

        fun down(point: Offset) {
            check(!pressed)
            position = point
            downTime = SystemClock.uptimeMillis()
            pressed = true
            dispatch(MotionEvent.ACTION_DOWN)
        }

        fun advanceEventTime(millis: Long) = SystemClock.sleep(millis)

        fun moveBy(delta: Offset) {
            check(pressed)
            position += delta
            dispatch(MotionEvent.ACTION_MOVE)
        }

        fun up() = finish(MotionEvent.ACTION_UP)
        fun cancel() = finish(MotionEvent.ACTION_CANCEL)

        private fun finish(action: Int) {
            check(pressed)
            dispatch(action)
            pressed = false
        }

        private fun dispatch(action: Int) {
            main {
                val decor = hostView
                val origin = IntArray(2)
                decor.getLocationInWindow(origin)
                val properties = MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = position.x - origin[0]
                    y = position.y - origin[1]
                    pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                    size = 0.1f
                }
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, 1,
                    arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                try {
                    val handled = decor.dispatchTouchEvent(event)
                    // CANCEL puede devolver false aunque ya haya cancelado el detector.
                    if (action == MotionEvent.ACTION_DOWN) assertTrue("el host recibe DOWN", handled)
                } finally {
                    event.recycle()
                }
            }
        }
    }
}
