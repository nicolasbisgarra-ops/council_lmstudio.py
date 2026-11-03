package io.trazo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import io.trazo.ai.AiProfile
import io.trazo.ai.AiProvider
import io.trazo.ai.AiReasoningEffortMode
import io.trazo.ai.AiReasoningEffortOption
import io.trazo.app.TrazoApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
class ReasoningEffortGuiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TrazoApp
        get() = RuntimeEnvironment.getApplication() as TrazoApp

    private val profileId = "qa-reasoning-effort-gui"
    private lateinit var server: ServerSocket
    private lateinit var serverExecutor: ExecutorService
    private lateinit var originalBaseUrl: String
    private val requestCount = AtomicInteger()
    @Volatile private var authorization: String? = null
    @Volatile private var requestTarget: String? = null

    @Before
    fun prepareLocalCatalog() {
        app.ai.delete(profileId)
        requestCount.set(0)
        authorization = null
        requestTarget = null
        serverExecutor = Executors.newSingleThreadExecutor()
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        originalBaseUrl = "http://127.0.0.1:${server.localPort}/v1"
        serverExecutor.execute(::serveCatalogRequests)
        app.ai.save(
            AiProfile(
                id = profileId,
                name = "QA Reasoning Model",
                provider = AiProvider.OPENAI_COMPATIBLE,
                baseUrl = originalBaseUrl,
                model = "qa-model",
            ),
        )
    }

    @After
    fun stopLocalCatalog() {
        runCatching { server.close() }
        serverExecutor.shutdownNow()
        app.ai.delete(profileId)
    }

    private fun serveCatalogRequests() {
        val body = """{"data":[{"id":"qa-model","display_name":"QA Model","supported_reasoning_levels":[{"effort":"low","description":"Lower effort"},{"effort":"high","description":"Higher effort"}],"default_reasoning_level":"low"}]}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            client.use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                requestTarget = reader.readLine()
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.substringBefore(':').trim().equals("Authorization", ignoreCase = true)) {
                        authorization = header.substringAfter(':').trim()
                    }
                }
                requestCount.incrementAndGet()
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(responseHeaders.toByteArray(Charsets.US_ASCII))
                    write(bodyBytes)
                    flush()
                }
            }
        }
    }

    @Test
    fun `catalog options are selected and saved through the AI settings UI`() {
        compose.setContent { RenderSettingsScreen(initialSection = PreferenceSection.AI.name) }

        compose.onNodeWithContentDescription("Editar QA Reasoning Model").performClick()
        compose.onNodeWithText("Consultar esfuerzos").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("El catálogo indica low como valor predeterminado", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Manual").performScrollTo().performClick()
        compose.onNodeWithText("Niveles informados por el catálogo").assertExists()
        compose.onNodeWithText("high · Higher effort").performScrollTo().performClick()
        compose.onNodeWithText("Manual").assertIsSelected()
        compose.onNodeWithText("high · Higher effort").assertIsSelected()
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.waitForIdle()

        val saved = app.ai.settings.value.profiles.single { it.id == profileId }
        assertEquals(AiReasoningEffortMode.MANUAL, saved.reasoningEffortMode)
        assertEquals("high", saved.reasoningEffort)
        assertEquals("low", saved.defaultReasoningEffort)
        assertEquals(
            listOf(AiReasoningEffortOption("low", "Lower effort"), AiReasoningEffortOption("high", "Higher effort")),
            saved.availableReasoningEfforts,
        )
        assertEquals(1, requestCount.get())
        assertNull("compatible local server must not receive an Authorization header", authorization)
        assertTrue(requestTarget.orEmpty().startsWith("GET /v1/models "))

        // Scroll the outer page, not just the nested profile list, below the fixed app bar.
        compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10000f) }
        compose.waitForIdle()
        // Reopen from the persisted list to verify the selected mode and catalog snapshot survive.
        compose.onNodeWithContentDescription("Editar QA Reasoning Model").performScrollTo().performClick()
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Editar modelo").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            System.err.println("Reopening the profile did not show the editor. Semantics tree:")
            System.err.println(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        assertSelectedAfterScroll("Manual")
        assertSelectedAfterScroll("high · Higher effort")
        assertTrue(app.ai.settings.value.profiles.any { it.id == profileId && it.reasoningEffort == "high" })

        val changedBaseUrl = originalBaseUrl.replace("/v1", "/unqueried")
        val urlField = compose.onNode(hasSetTextAction() and hasText(originalBaseUrl))
        urlField.performScrollTo()
        urlField.performTextReplacement(changedBaseUrl)
        compose.onNodeWithText("Automático").performScrollTo().assertIsSelected()
        compose.onNodeWithText("No hay opciones de esfuerzo en este perfil.", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("Niveles informados por el catálogo").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.waitForIdle()

        val retargeted = app.ai.settings.value.profiles.single { it.id == profileId }
        assertEquals(changedBaseUrl, retargeted.baseUrl)
        assertEquals(AiReasoningEffortMode.AUTO, retargeted.reasoningEffortMode)
        assertNull(retargeted.reasoningEffort)
        assertTrue(retargeted.availableReasoningEfforts.isEmpty())
        assertNull(retargeted.defaultReasoningEffort)
        assertTrue("catalog-known state must be cleared after the endpoint changes", !retargeted.reasoningEffortCatalogKnown)
        assertTrue(!retargeted.reasoningEffortManualSupported)
        assertTrue(!retargeted.reasoningThinkingSupported)
        assertEquals("URL changes must not trigger an implicit catalog fetch", 1, requestCount.get())
    }

    private fun assertSelectedAfterScroll(text: String) {
        try {
            compose.onNodeWithText(text).performScrollTo().assertIsSelected()
        } catch (failure: Throwable) {
            System.err.println("Could not confirm '$text' is selected after reopening. Semantics tree:")
            System.err.println(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
    }
}

@Composable
private fun RenderSettingsScreen(initialSection: String) {
    val app = LocalContext.current.applicationContext as TrazoApp
    val settings by app.settings.settings.collectAsState()
    TrazoTheme(settings.themeMode, settings.accent, settings.accent2) {
        SettingsScreen(onBack = {}, initialSection = initialSection)
    }
}
