package io.trazo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.trazo.app.TrazoApp
import io.trazo.app.data.TrazoSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
class SettingsGuiMatrixTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TrazoApp
        get() = RuntimeEnvironment.getApplication() as TrazoApp

    @Before
    fun resetSettingsBeforeTest() {
        app.settings.update { TrazoSettings() }
    }

    @After
    fun restoreSettingsAfterTest() {
        app.settings.update { TrazoSettings() }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun everySettingsSectionRendersAndScrollsOnPhone() {
        verifyAllSectionsOnCurrentDevice()
    }

    @Test
    @Config(qualifiers = "w840dp-h1180dp")
    fun everySettingsSectionRendersAndScrollsOnTablet() {
        verifyAllSectionsOnCurrentDevice()
    }

    private fun verifyAllSectionsOnCurrentDevice() {
        var route by mutableStateOf(PreferenceSection.APPEARANCE.name)
        compose.setContent {
            val currentRoute = route
            key(currentRoute) { RenderSettingsScreen(currentRoute) }
        }

        val sectionHeadings = mapOf(
            PreferenceSection.APPEARANCE to "Tono de hoja",
            PreferenceSection.PEN to "Botones del lápiz",
            PreferenceSection.PAPER to "Cuadernos nuevos",
            PreferenceSection.TOOLS to "Modo de la goma",
            PreferenceSection.RECOGNITION to "Auto-alinear lo reconocido",
            PreferenceSection.MATH to "Cuentas en la hoja",
            PreferenceSection.GENERAL to "Vibrar al reemplazar un gesto",
            PreferenceSection.AI to "Registro de envíos",
            PreferenceSection.SYNC to "Vincular un dispositivo",
        )
        val footer = "Todo funciona sin conexión"

        PreferenceSection.entries.forEach { section ->
            compose.runOnIdle { route = section.name }
            compose.waitForIdle()

            assertTrue(
                "${section.name} section title is missing",
                compose.onAllNodesWithText(section.title).fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithText(sectionHeadings.getValue(section)).assertExists()
            // Bring the last paragraph into view. On shorter sections this is a no-op; on longer
            // phone/tablet layouts it verifies the real page scroll container can reach the end.
            compose.onNodeWithText(footer, substring = true).performScrollTo().assertIsDisplayed()
            assertTrue(
                "${section.name} title should remain available while scrolled",
                compose.onAllNodesWithText(section.title).fetchSemanticsNodes().isNotEmpty(),
            )

            when (section) {
                PreferenceSection.RECOGNITION -> toggleLocalSetting(
                    label = "Mostrar el anillo de pausa",
                    before = app.settings.settings.value.showHoldIndicator,
                    read = { app.settings.settings.value.showHoldIndicator },
                )
                PreferenceSection.MATH -> toggleLocalSetting(
                    label = "Resolver las cuentas",
                    before = app.settings.settings.value.mathNotes,
                    read = { app.settings.settings.value.mathNotes },
                )
                PreferenceSection.GENERAL -> toggleLocalSetting(
                    label = "Vibrar al reemplazar un gesto",
                    before = app.settings.settings.value.haptics,
                    read = { app.settings.settings.value.haptics },
                )
                else -> Unit
            }
        }
    }

    private fun toggleLocalSetting(label: String, before: Boolean, read: () -> Boolean) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("$label should update its local preference", !before, read())
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
