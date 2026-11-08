package io.trazo.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso.pressBack
import io.trazo.app.data.BarDock
import io.trazo.app.data.TrazoSettings
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.InkPattern
import io.trazo.core.PointerKind
import io.trazo.core.TrazoFormat
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * User-facing smoke coverage for the navigation and controls that are hard to exercise in model
 * tests. These tests intentionally stay on local flows: opening AI settings never starts a review,
 * model lookup, or connection check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
class GuiUserFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: TrazoApp
        get() = compose.activity.application as TrazoApp

    @After
    fun restoreSettings() {
        compose.runOnIdle { app.settings.update { TrazoSettings() } }
    }

    @Test
    fun createOpenNotebookUseToolsAndCollapsePagesPreview() {
        val name = uniqueNotebookName()
        createNotebook(name)

        compose.onNodeWithContentDescription("Pluma").assertExists().performClick()
        compose.onNodeWithText("Grosor").assertExists()
        compose.onNodeWithContentDescription("Goma").performClick()
        compose.onNodeWithContentDescription("Goma").performClick()
        compose.onNodeWithText("Botón lateral del lápiz").assertExists()
        pressBack()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Deshacer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Rehacer").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Páginas").performClick()
        compose.onNodeWithContentDescription("Minimizar vista previa de páginas").assertExists().performClick()
        compose.onNode(hasContentDescription("Mostrar vista previa de páginas", substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Páginas").performClick()
        compose.onNodeWithContentDescription("Minimizar vista previa de páginas").assertExists()

        compose.onNodeWithContentDescription("Nueva página").performClick()
        compose.onNodeWithText("Página 2 de 2").assertExists()
        compose.onNodeWithContentDescription("Página anterior").performClick()
        compose.onNodeWithText("Página 1 de 2").assertExists()
        compose.onNodeWithContentDescription("Página siguiente").performClick()
        compose.onNodeWithText("Página 2 de 2").assertExists()
    }

    @Test
    fun aiSettingsCanBeOpenedWithoutStartingRemoteActions() {
        val name = uniqueNotebookName()
        createNotebook(name)

        compose.onNodeWithContentDescription("Modo IA").performClick()
        compose.onNodeWithText("Configurar").assertExists().performClick()
        compose.onNodeWithText("Agregar modelo").assertExists().performClick()
        compose.onNodeWithText("OpenAI").performClick()
        compose.onNodeWithText("Obtener modelos").assertExists()
        compose.onNodeWithText("Probar conexión").assertExists()

        // Cancel the unsaved profile; no remote action was selected.
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithContentDescription("Pluma").assertExists()
    }

    @Test
    fun patternsPracticeAndExamRoutesRenderTheirLocalScreens() {
        val name = uniqueNotebookName()
        createNotebook(name)

        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Biblioteca de patrones").performScrollTo().performClick()
        compose.onNodeWithText("Biblioteca de patrones").assertExists()
        waitForTeachingPatternAction()
        compose.onNodeWithText("Enseñar patrón", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Gesto a reconocer").assertExists()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithContentDescription("Volver").performClick()

        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Practicar errores frecuentes").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Práctica").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Práctica").assertExists()
        compose.onNodeWithText("Todavía no hay errores de revisión compatibles con ejercicios verificables.", substring = true)
            .assertExists()
        compose.onNodeWithText("Atrás").performClick()

        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Modo examen").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Modo examen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Modo examen").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Para preparar el examen hacen falta marcas compatibles de una revisión anterior.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Para preparar el examen hacen falta marcas compatibles de una revisión anterior.")
            .assertExists()
    }

    @Test
    fun convertedUserEqualsRefreshesMathAndShowsSnackbarInOpenEditor() {
        val uploadedPatterns = userPatternsFromProperty()
        val userEquals = uploadedPatterns.single { it.name == "Igual" && !it.id.startsWith("preset-") }
        app.repository.upsertPattern(userEquals)

        val name = uniqueNotebookName()
        createNotebook(name)

        var conversionSucceeded = false
        var resultPatternIds = emptyList<String>()
        var didDraw = false
        compose.runOnUiThread {
            val canvas = requireNotNull(
                compose.activity.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG),
            ) { "Editor InkCanvasView was not attached" }
            val controller = requireNotNull(canvas.controller) { "Editor controller was not attached" }
            val patterns = app.repository.patterns.value.associateBy { it.id }
            val two = requireNotNull(patterns["preset-2"]) { "The 2 preset is missing" }
            val plus = requireNotNull(patterns["preset-mas"]) { "The plus preset is missing" }
            val three = requireNotNull(patterns["preset-3"]) { "The 3 preset is missing" }
            val equal = requireNotNull(patterns[userEquals.id]) { "Uploaded Igual pattern was not stored" }

            val now = SystemClock.uptimeMillis()
            controller.mathEnabled = true
            controller.insertPattern(two, 120f, 360f, height = 40f, nowMs = now)
            controller.insertPattern(plus, 180f, 360f, height = 40f, nowMs = now + 20)
            controller.insertPattern(three, 240f, 360f, height = 40f, nowMs = now + 40)

            // Draw a real source gesture into this editor's controller, then convert that existing
            // gesture with the uploaded user pattern. The replacement path also refreshes math.
            val t = now + 100
            controller.penDown(286f, 350f, 0.7f, t, PointerKind.STYLUS)
            controller.penMove(294f, 370f, 0.7f, t + 20)
            controller.penMove(306f, 350f, 0.7f, t + 40)
            controller.penMove(314f, 370f, 0.7f, t + 60)
            controller.penUp(314f, 370f, 0.7f, t + 80)
            val source = controller.page.strokes.last { it.patternId == null }
            conversionSucceeded = !controller.convertWith(equal, listOf(source.id), nowMs = t + 100).isNullOrEmpty()
            resultPatternIds = controller.mathResults.map { it.patternId.orEmpty() }

            // Explicitly draw the attached native canvas after the controller update. Its next
            // Compose/AndroidView frame must preserve the editor and the displayed calculation.
            require(canvas.width > 0 && canvas.height > 0) { "Editor canvas has no measured size" }
            val bitmap = Bitmap.createBitmap(canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                canvas.invalidate()
                canvas.draw(Canvas(bitmap))
                didDraw = true
            } finally {
                bitmap.recycle()
            }
        }

        compose.waitForIdle()
        assertTrue("the selected user pattern should convert the existing gesture", conversionSucceeded)
        assertTrue("the '=' replacement should derive the 5 pattern", resultPatternIds.contains("preset-5"))
        assertTrue("the attached native InkCanvasView should draw after conversion", didDraw)
        compose.onNodeWithContentDescription("Pluma").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Reemplazado en tinta: Igual", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Reemplazado en tinta: Igual", substring = true).assertExists()
    }

    @Test
    fun editorToolbarRendersForEveryDockAndCanvasStaysMeasured() {
        val name = uniqueNotebookName()
        createNotebook(name)

        for (dock in BarDock.entries) {
            compose.runOnIdle { app.settings.update { it.copy(toolbarDock = dock) } }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Pluma").assertExists()
            compose.onNodeWithContentDescription("Más opciones").assertExists()
            compose.onNodeWithContentDescription("Nueva página").assertExists()

            var canvasWidth = 0
            var canvasHeight = 0
            compose.runOnUiThread {
                val canvas = requireNotNull(
                    compose.activity.window.decorView.findViewWithTag<InkCanvasView>(InkCanvasView.TAG),
                ) { "Editor InkCanvasView was not attached for $dock" }
                canvasWidth = canvas.width
                canvasHeight = canvas.height
            }
            assertTrue("canvas should remain measured at $dock: ${canvasWidth}x$canvasHeight", canvasWidth > 0 && canvasHeight > 0)
        }
    }

    private fun createNotebook(name: String) {
        compose.onNodeWithText("Nuevo cuaderno", useUnmergedTree = true).performClick()
        compose.onNode(
            hasSetTextAction(),
            useUnmergedTree = true,
        ).apply {
            performTextClearance()
            performTextInput(name)
        }
        compose.onNodeWithText("Crear").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Pluma").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun uniqueNotebookName() = "GUI ${System.currentTimeMillis()}"

    private fun waitForTeachingPatternAction() {
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Enseñar patrón", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val root = compose.onRoot(useUnmergedTree = true)
            val tree = root.printToString()
            root.printToLog("TrazoPatternLibraryFailure")
            throw AssertionError(
                "Pattern library did not expose its Enseñar patrón FAB; semantics tree:\n$tree",
                failure,
            )
        }
    }

    private fun userPatternsFromProperty(): List<InkPattern> {
        val path = System.getProperty("trazo.userPatterns")
        assumeTrue("requires supplied user fixture", path != null)
        return File(requireNotNull(path)).inputStream().use { TrazoFormat.decodePatterns(it) }
    }
}
