package io.trazo.app.ui

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.trazo.app.MainActivity
import io.trazo.app.TrazoApp
import io.trazo.app.data.TrazoSettings
import io.trazo.core.IdGenerator
import io.trazo.core.Page
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** El panel empieza minimizado en teléfono y tableta; abrirlo es una elección que se restaura. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
class PagesInitialStateGuiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: TrazoApp
        get() = compose.activity.application as TrazoApp

    private var notebookId: String? = null

    @Before
    fun resetSettings() {
        compose.runOnIdle { app.settings.update { TrazoSettings() } }
    }

    @After
    fun cleanUp() {
        notebookId?.let(app.repository::deleteNotebook)
        notebookId = null
        compose.runOnIdle { app.settings.update { TrazoSettings() } }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneStartsCollapsedAndKeepsAnOpenedPanelAfterStateRestore() {
        verifyInitialAndRestoredState()
    }

    @Test
    @Config(qualifiers = "w840dp-h1180dp")
    fun tabletStartsCollapsedAndKeepsAnOpenedPanelAfterStateRestore() {
        verifyInitialAndRestoredState()
    }

    @Test
    fun pageThumbnailOpensTheSelectedPageInTheEditor() {
        val name = "Page switch GUI ${System.currentTimeMillis()}"
        val notebook = app.repository.createNotebook(name)
        notebookId = notebook.id
        app.repository.saveNotebook(notebook.copy(pages = notebook.pages + Page(IdGenerator.RANDOM.next())))
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Páginas", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Sin tira plegada: el panel se abre solo desde el botón «Páginas» de la barra.
        compose.onAllNodesWithContentDescription("Páginas", substring = true).onFirst().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Página 2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Página 2").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun verifyInitialAndRestoredState() {
        val name = "Pages GUI ${System.currentTimeMillis()}"
        createNotebook(name)

        // Closed by default, with no collapsed strip: only the toolbar «Páginas» button opens it.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Páginas", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Mostrar vista previa de páginas", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Opciones de la página 1").assertDoesNotExist()

        compose.onAllNodesWithContentDescription("Páginas", substring = true).onFirst().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Opciones de la página 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Minimizar vista previa de páginas").assertExists()

        // Recreate the real activity to cover rememberSaveable through Android saved state.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Minimizar vista previa de páginas").assertExists()
        compose.onNodeWithContentDescription("Opciones de la página 1").assertExists()

        compose.onNodeWithContentDescription("Minimizar vista previa de páginas").performClick()
        compose.onNodeWithContentDescription("Minimizar vista previa de páginas").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mostrar vista previa de páginas", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Opciones de la página 1").assertDoesNotExist()
    }

    private fun createNotebook(name: String) {
        notebookId = app.repository.createNotebook(name).id

        // Refresh the real notebook list after seeding the repository; avoid its text-entry dialog,
        // whose platform TextField layout is unstable in Robolectric's line breaker.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Pluma").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
