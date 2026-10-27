package io.trazo.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.trazo.app.data.DEFAULT_HIGHLIGHTER
import io.trazo.app.data.TrazoSettings
import io.trazo.core.Bounds
import io.trazo.core.Brush
import io.trazo.core.DEFAULT_INK_COLOR
import io.trazo.core.DefaultPatterns
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkReplacement
import io.trazo.core.InkStroke
import io.trazo.core.Page
import io.trazo.core.Paper
import io.trazo.core.PresetPatterns
import io.trazo.core.bounds
import io.trazo.core.fixtures.HandScript
import io.trazo.core.fixtures.SyntheticPen
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Recorrido de la interfaz para las capturas de la entrega: la lista de cuadernos, el editor con la
 * barra y los paneles de pluma y goma, el selector de color, el de patrones, la biblioteca, las
 * preferencias y el tema oscuro. No verifica lógica (de eso se ocupan las otras pruebas): falla
 * solo si no encuentra un control. Deja las capturas en /data/local/tmp/trazo-gui-*.png, que
 * scripts/ci-emulator.sh baja junto con las de la demo.
 */
@RunWith(AndroidJUnit4::class)
class GuiTourTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<TrazoApp>()
    private val device = UiDevice.getInstance(instrumentation)

    @After fun restoreSettings() = app.settings.update { TrazoSettings() }

    @Test fun tourOfTheInterface() {
        app.repository.restoreDefaultPatterns()
        app.settings.update { TrazoSettings() }
        seedNotebooks()

        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            find(By.text("Cálculo I"))
            shot("1-cuadernos")

            find(By.text("Cálculo I")).click()
            find(By.desc("Pluma"))
            shot("2-editor")

            find(By.desc("Pluma")).click() // ya elegida: abre sus ajustes
            find(By.text("Grosor"))
            shot("3-pluma")
            device.pressBack()

            find(By.desc("Goma")).click()
            SystemClock.sleep(400)
            find(By.desc("Goma")).click()
            find(By.text("Botón lateral del lápiz"))
            shot("4-goma")
            device.pressBack()
            find(By.desc("Pluma")).click()

            find(By.descStartsWith("Color #")).longClick() // primer color rápido
            find(By.text("Cambiar este color rápido"))
            shot("5-color")
            device.pressBack()

            // En el teléfono la segunda fila de la barra no entra: se desliza hasta el final.
            val row = find(By.desc("Pluma")).visibleBounds
            device.swipe(device.displayWidth - 40, row.centerY(), 40, row.centerY(), 40)
            find(By.desc("Insertar patrón")).click()
            find(By.text("Integral"))
            shot("6-insertar")
            device.pressBack()

            menu("Biblioteca de patrones")
            find(By.text("Enseñar patrón"))
            shot("7-biblioteca")
            find(By.text("Integral")).click()
            find(By.text("Editar patrón"))
            shot("8-patron")
            device.pressBack()
            find(By.text("Enseñar patrón"))
            device.pressBack()

            menu("Preferencias")
            find(By.text("Apariencia"))
            shot("9-preferencias")
            find(By.text("Oscuro")).click()
            shot("10-oscuro")
            device.pressBack()
            find(By.desc("Más opciones"))
            shot("11-oscuro-editor")
            device.pressBack()
            find(By.text("Cálculo I"))
            shot("12-oscuro-cuadernos")
        }
    }

    private fun find(selector: BySelector, timeoutMs: Long = 10_000): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: error("No apareció $selector")

    /** Abre «Más opciones» del editor y elige [item]. */
    private fun menu(item: String) {
        find(By.desc("Más opciones")).click()
        find(By.text(item)).click()
    }

    /** Captura con `screencap` (usuario shell) después de que la interfaz se asiente. */
    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(900)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/trazo-gui-$name.png").use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    /**
     * Tres cuadernos para la lista: el ejercicio de la demo resuelto (patrones con la tinta de la
     * biblioteca y escritura de mano simulada), uno con signos de la biblioteca y uno vacío. El último
     * creado queda primero.
     */
    private fun seedNotebooks() {
        val library = app.repository.patterns.value.associateBy { it.id }
        fun sign(key: String) = library.getValue(PresetPatterns.PREFIX + key)

        notebook("Apuntes", Paper.DOTS) { }
        notebook("Álgebra", Paper.GRID) {
            write("2x+3=7", 90f, 90f, 62f)
            write("2x=4", 90f, 200f, 62f)
            highlight(80f, 260f, 342f)
            write("x=2", 90f, 310f, 62f)
            val sum = place(sign("sumatoria"), Bounds(90f, 450f, 170f, 560f))
            write("x^2", sum.right + 20f, 470f, 62f)
            place(sign("pi"), Bounds(90f, 640f, 160f, 710f))
            place(sign("infinito"), Bounds(220f, 640f, 340f, 700f))
            place(sign("para-todo"), Bounds(400f, 630f, 460f, 710f))
            place(sign("pertenece"), Bounds(510f, 640f, 560f, 705f))
        }
        notebook("Cálculo I", Paper.LINES) {
            val integral = place(library.getValue(DefaultPatterns.INTEGRAL), HandScript.integralGesture(70f, 60f, 170f).box())
            write("2", integral.right + 2f, integral.top - 8f, 34f)
            write("0", integral.centerX + 4f, integral.bottom - 24f, 30f)
            var x = write("(3x^2+2x)", integral.right + 40f, integral.centerY - 34f, 62f)
            write("dx", x + 22f, integral.centerY - 34f, 62f)
            x = write("=[x^3+x^2]", 120f, 300f, 62f)
            write("2", x + 4f, 285f, 32f)
            write("0", x + 4f, 352f, 32f)
            write("=(8+4)-0", 120f, 430f, 62f)
            highlight(110f, 250f, 592f)
            write("=12", 120f, 560f, 62f)

            val root = place(library.getValue(DefaultPatterns.ROOT), HandScript.rootGesture(120f, 720f, 95f, 70f).box())
            x = write("16", root.left + root.width * 0.42f, root.top + root.height * 0.3f, root.height * 0.58f)
            write("=4", maxOf(x, root.right) + 25f, root.top + root.height * 0.3f, root.height * 0.58f)

            val bar = place(library.getValue(DefaultPatterns.FRACTION), HandScript.fractionGesture(100f, 1010f, 110f).box())
            write("6", bar.centerX - 18f, bar.top - 72f, 58f)
            write("3", bar.centerX - 18f, bar.bottom + 14f, 58f)
            write("=2", bar.right + 30f, bar.centerY - 34f, 62f)
        }
    }

    /** Rectángulo de un recorrido del lápiz (lo que el editor toma como gesto). */
    private fun List<Pair<Float, Float>>.box() = Bounds(minOf { it.first }, minOf { it.second }, maxOf { it.first }, maxOf { it.second })

    private fun notebook(name: String, paper: Paper, content: PageInk.() -> Unit) {
        val nb = app.repository.createNotebook(name, paper)
        val ink = PageInk().apply(content)
        app.repository.saveNotebook(nb.copy(pages = listOf(Page(nb.pages[0].id, ink.strokes)), updatedAt = System.currentTimeMillis()))
        SystemClock.sleep(20) // fechas distintas: el orden de la lista es el de creación
    }

    /** Arma la tinta de una página como la dejaría el lápiz: trazos de mano, patrones y resaltador. */
    private class PageInk {
        private val ids = IdGenerator.RANDOM
        val strokes = mutableListOf<InkStroke>()

        /** Escribe con la mano simulada; devuelve la x final. */
        fun write(text: String, x: Float, y: Float, size: Float): Float {
            val (paths, end) = HandScript.write(text, x, y, size, seed = text.hashCode())
            paths.forEach { strokes += stroke(it, DEFAULT_INK_COLOR, 3f, Brush.PEN) }
            return end
        }

        /** Inserta la tinta de salida de un patrón en el rectángulo de un gesto. */
        fun place(pattern: InkPattern, at: Bounds): Bounds =
            InkReplacement.place(pattern, at, ids).also { strokes += it }.bounds()

        fun highlight(x0: Float, x1: Float, y: Float) {
            strokes += stroke(listOf(x0 to y, x1 to y), DEFAULT_HIGHLIGHTER, 40f, Brush.HIGHLIGHTER)
        }

        private fun stroke(path: List<Pair<Float, Float>>, color: Int, width: Float, brush: Brush): InkStroke {
            val points = SyntheticPen.resample(path, 3f).mapIndexed { i, (x, y) ->
                InkPoint(x, y, pressure = 0.6f + 0.2f * sin(i * 0.25f), timeMs = i * 8L)
            }
            return InkStroke(ids.next(), points, color = color, width = width, brush = brush)
        }
    }
}
