package io.trazo.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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

            // La paleta de colores queda al final de la fila deslizable: se desliza hasta verla.
            val toolsY = find(By.desc("Pluma")).visibleBounds.centerY()
            scrollToolbarTo("Otro color", toolsY)
            // El selector de color se abre en colorPicker(), que CI corre aparte: llegó a tumbar el
            // emulador y así no se pierden las capturas del recorrido.
            shot("5-color")

            // Sigue en la misma fila.
            scrollToolbarTo("Insertar patrón", toolsY)
            find(By.desc("Insertar patrón")).click()
            find(By.text("Integral"))
            shot("6-insertar")
            device.pressBack()

            menu("Biblioteca de patrones")
            find(By.text("Biblioteca de patrones"))
            shot("7-biblioteca")
            find(By.text("Integral")).click()
            find(By.text("Editar patrón"))
            shot("8-patron")
            device.pressBack()
            find(By.text("Biblioteca de patrones"))
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

    /** Abre el selector de color libre desde la barra, lo captura y elige un color. */
    @Test fun colorPicker() {
        app.settings.update { TrazoSettings() }
        notebook("Colores", Paper.DOTS) { }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            find(By.text("Colores")).click()
            val toolsY = find(By.desc("Pluma")).visibleBounds.centerY()
            scrollToolbarTo("Otro color", toolsY)
            find(By.desc("Otro color")).click()
            find(By.text("Usar"))
            shot("13-selector-color")
            find(By.text("Usar")).click()
            find(By.desc("Pluma"))
        }
    }

    /**
     * Lista y editor en el formato de pantalla que fija el script con `wm size`/`wm density`
     * (teléfono, tableta, PC): `-e formato <nombre>` da el prefijo de las capturas.
     */
    @Test fun formFactor() {
        val name = InstrumentationRegistry.getArguments().getString("formato") ?: "actual"
        app.repository.restoreDefaultPatterns()
        app.settings.update { TrazoSettings() }
        seedNotebooks()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            find(By.text("Cálculo I"))
            capture("trazo-formato-$name-1-cuadernos")
            find(By.text("Cálculo I")).click()
            find(By.desc("Pluma"))
            capture("trazo-formato-$name-2-editor")
            find(By.desc("Pluma")).click()
            find(By.text("Grosor"))
            capture("trazo-formato-$name-3-pluma")
            device.pressBack()
        }
    }

    private fun find(selector: BySelector, timeoutMs: Long = 10_000): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: error("No apareció $selector. En pantalla: ${onScreen()}")

    /** Textos y descripciones visibles con su posición, para saber qué había cuando algo falta. */
    private fun onScreen(): String {
        shot("fallo")
        val xml = java.io.ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
        return Regex("""(text|content-desc)="([^"]+)"[^>]*?bounds="([^"]+)"""").findAll(xml)
            .joinToString(" | ") { "${it.groupValues[2]} ${it.groupValues[3]}" }
    }

    /**
     * Desliza la barra hasta que [desc] se vea entero. Por accesibilidad y no con un gesto: en el
     * emulador de CI el deslizamiento con el dedo no movía la fila.
     */
    private fun scrollToolbarTo(desc: String, y: Int) {
        val tried = mutableListOf<String>()
        repeat(12) { i ->
            // Lo que queda fuera de la fila no está en el árbol: si no aparece, se sigue deslizando.
            val b = device.findObject(By.desc(desc))?.visibleBounds
            // Entero = no recortado por el borde: los botones redondos miden algo menos de ancho que de alto.
            if (b != null && b.width() * 5 >= b.height() * 4 && b.right < device.displayWidth - 4) return
            // Se alternan tres vías porque cada emulador responde a una distinta: el gesto de
            // UiAutomator dentro de la fila, la acción de accesibilidad «a la derecha» y la «adelante».
            val row = device.findObjects(By.scrollable(true)).firstOrNull {
                val r = it.visibleBounds
                y in r.top..r.bottom && r.width() > r.height()
            }
            when (i % 3) {
                0 -> {
                    row?.setGestureMargins(row.visibleBounds.width() / 8, 0, row.visibleBounds.width() / 8, 0)
                    tried += "gesto:${row?.visibleBounds}:${row?.scroll(androidx.test.uiautomator.Direction.RIGHT, 0.8f)}"
                }
                else -> {
                    val node = scrollableAt(instrumentation.uiAutomation.rootInActiveWindow, y)
                    val action = if (i % 3 == 1) android.R.id.accessibilityActionScrollRight
                    else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    tried += "a11y$action:${node?.className}:${node?.performAction(action)}"
                }
            }
            SystemClock.sleep(500)
        }
        error("$desc no quedó a la vista. Intentos: $tried. En pantalla: ${onScreen()}")
    }

    /** El nodo deslizable más bajo que cruza la altura [y]. */
    private fun scrollableAt(node: AccessibilityNodeInfo?, y: Int): AccessibilityNodeInfo? {
        node ?: return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        val own = node.takeIf { it.isScrollable && y in r.top..r.bottom }
        val child = (0 until node.childCount).mapNotNull { scrollableAt(node.getChild(it), y) }
            .minByOrNull { Rect().also { c -> it.getBoundsInScreen(c) }.height() }
        return child ?: own
    }

    /** Abre «Más opciones» del editor y elige [item]. */
    private fun menu(item: String) {
        find(By.desc("Más opciones")).click()
        find(By.text(item)).click()
    }

    /** Captura con `screencap` (usuario shell) después de que la interfaz se asiente. */
    private fun shot(name: String) = capture("trazo-gui-$name")

    private fun capture(file: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(900)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$file.png").use { pfd ->
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
