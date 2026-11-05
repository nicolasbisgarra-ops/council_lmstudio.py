package io.trazo.app

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.ui.ChatRichText
import io.trazo.app.ui.rememberLatex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El chat de revisión no debe cerrar la app con respuestas raras del modelo: fórmulas vacías,
 * mal cerradas, enormes o con texto acentuado, ni con cada prefijo de una respuesta que llega por partes.
 */
@RunWith(AndroidJUnit4::class)
class AiChatInstrumentedTest {
    private val app get() = ApplicationProvider.getApplicationContext<TrazoApp>()

    private val samples = listOf(
        "Vacía inline: \\(\\) y con espacio \\( \\) y otra \\(  \\).",
        "Display vacío: $$ $$ y \\[ \\] fin.",
        "Acentos: \\(\\text{área} = \\pi r^2\\) y ñ fuera.",
        "Alineado:\n\\[\\begin{aligned} x &= 1 \\\\ y &= 2 \\end{aligned}\\]",
        "Mal cerrada: \\(\\frac{1}{ \\) y \\(\\sqrt{\\) y \\(}\\).",
        "Sin cerrar: \\(x^2 + 1",
        "Solo comando: \\(\\\\\\) y \\(\\,\\).",
        "Matriz grande: \\[" + "\\begin{pmatrix}" + (1..40).joinToString("\\\\") { r -> (1..40).joinToString("&") { "${r * it}" } } + "\\end{pmatrix}\\]",
        "Larga: \\(" + (1..400).joinToString("+") { "x_{$it}^{$it}" } + "\\)",
        "",
        "   ",
    )

    private val streamed = "Revisemos la línea 2: \\(\\int_0^2 (3x^2+2x)\\,dx = \\left[x^3+x^2\\right]_0^2\\). " +
        "Queda \\[ (8+4) - 0 = 12 \\] y la raíz \\(\\sqrt{16}=4\\). ¡Bien! \\( \\)"

    @Test fun rendersHostileLatexWithoutCrashing() {
        val nb = app.repository.createNotebook("Prueba chat IA")
        val prefixes = (0..streamed.length step 3).map { streamed.take(it) } + streamed
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTEBOOK_ID, nb.id)).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { a ->
                a.setContent {
                    MaterialTheme {
                        rememberLatex()
                        Column(Modifier.verticalScroll(rememberScrollState())) { samples.forEach { ChatRichText(it) } }
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(800)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // La respuesta llega por partes: cada prefijo deja delimitadores abiertos o fórmulas vacías.
            for (chunk in prefixes.chunked(12)) {
                scenario.onActivity { a ->
                    a.setContent { MaterialTheme { rememberLatex(); Column { chunk.forEach { ChatRichText(it) } } } }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            SystemClock.sleep(500)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
        app.repository.deleteNotebook(nb.id)
    }
}
