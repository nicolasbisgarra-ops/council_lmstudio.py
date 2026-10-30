package io.trazo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.trazo.app.ui.EditorScreen
import io.trazo.app.ui.LocalDarkTheme
import io.trazo.app.ui.NotebookListScreen
import io.trazo.app.ui.PatternEditorScreen
import io.trazo.app.ui.PatternLibraryScreen
import io.trazo.app.ui.SettingsScreen
import io.trazo.app.ui.TrazoTheme
import io.trazo.core.InkStroke

sealed interface Screen {
    data object Notebooks : Screen
    data class Editor(val notebookId: String) : Screen
    data object Patterns : Screen
    /** @param seedGesture gesto de la hoja para empezar un patrón nuevo ("enseñar este gesto"). */
    data class PatternEdit(val patternId: String?, val seedGesture: List<InkStroke>? = null) : Screen
    /** @param section página de preferencias a abrir (nombre de [io.trazo.app.ui.PreferenceSection]); null = la lista. */
    data class Settings(val section: String? = null) : Screen
}

/** Entrada de la pila: cada pantalla tiene su propio almacén de ViewModels, que se limpia al salir. */
class NavEntry(val screen: Screen) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** Pila de pantallas; sobrevive a rotaciones. */
class NavViewModel : ViewModel() {
    val stack = mutableStateListOf(NavEntry(Screen.Notebooks))
    fun push(s: Screen) { stack += NavEntry(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex).viewModelStore.clear() }
    override fun onCleared() { stack.forEach { it.viewModelStore.clear() } }
}

class MainActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && nav.stack.size == 1) {
            intent.getStringExtra(EXTRA_NOTEBOOK_ID)?.let { nav.push(Screen.Editor(it)) }
        }
        val banner = intent.getStringExtra(EXTRA_DEMO_BANNER)
        setContent {
            val settings by trazo.settings.settings.collectAsState()
            TrazoTheme(settings.themeMode, settings.accent, settings.accent2) {
                // Íconos de las barras del sistema según el tema elegido en la app, no el del sistema.
                val dark = LocalDarkTheme.current
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                    )
                    onDispose {}
                }
                TrazoRoot(nav, banner)
            }
        }
    }

    companion object {
        // Velos de la barra de navegación con botones: los mismos que usa enableEdgeToEdge por defecto.
        private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        /** Abre directamente un cuaderno (atajos y pruebas instrumentadas). */
        const val EXTRA_NOTEBOOK_ID = "io.trazo.extra.NOTEBOOK_ID"
        /** Cartel de interfaz que identifica una demo automatizada (no aparece sobre la hoja). */
        const val EXTRA_DEMO_BANNER = "io.trazo.extra.DEMO_BANNER"
    }
}

@Composable
private fun TrazoRoot(nav: NavViewModel, banner: String?) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val entry = nav.stack.last()
    Box(Modifier.fillMaxSize()) {
        key(entry) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                ScreenContent(entry.screen, nav)
            }
        }
        if (banner != null) {
            Text(
                banner,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC2B2F2E))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen, nav: NavViewModel) {
    when (screen) {
        Screen.Notebooks -> NotebookListScreen(
            onOpen = { nav.push(Screen.Editor(it)) },
            onPatterns = { nav.push(Screen.Patterns) },
            onSettings = { nav.push(Screen.Settings()) },
        )
        is Screen.Editor -> EditorScreen(
            notebookId = screen.notebookId,
            onBack = nav::pop,
            onPatterns = { nav.push(Screen.Patterns) },
            onSettings = { nav.push(Screen.Settings()) },
            onTeachNewPattern = { gesture -> nav.push(Screen.PatternEdit(null, gesture)) },
            onAiSettings = { nav.push(Screen.Settings("AI")) },
        )
        Screen.Patterns -> PatternLibraryScreen(
            onBack = nav::pop,
            onEdit = { nav.push(Screen.PatternEdit(it)) },
        )
        is Screen.PatternEdit -> PatternEditorScreen(patternId = screen.patternId, seedGesture = screen.seedGesture, onDone = nav::pop)
        is Screen.Settings -> SettingsScreen(onBack = nav::pop, initialSection = screen.section)
    }
}
