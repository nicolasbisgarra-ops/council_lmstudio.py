package io.trazo.app

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.trazo.app.ui.EditorViewModel
import io.trazo.core.GeoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** QA48 H1: volver a una hoja conserva su deshacer/rehacer, y borrarla olvida su historial. */
@RunWith(AndroidJUnit4::class)
class PageHistoryInstrumentedTest {
    private val app get() = ApplicationProvider.getApplicationContext<TrazoApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun openEditor(): EditorViewModel {
        val id = app.repository.createNotebook("Historial por hoja").id
        val vm = main { EditorViewModel(app, id) }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (main { vm.loading } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertFalse("el cuaderno no terminó de cargar", main { vm.loading })
        return vm
    }

    @Test fun undoSurvivesGoingToAnotherPageAndBack() {
        val vm = openEditor()
        main { vm.insertParametric(GeoKind.CIRCLE, 1000, 1000) }
        assertTrue(main { vm.canUndo })
        main { vm.addPage(); vm.goToPage(0) }
        assertEquals("la figura sigue en la hoja", 1, main { vm.controller.page.shapes.size })
        assertTrue("el deshacer de la hoja 1 sigue disponible", main { vm.canUndo })
        main { vm.undo() }
        assertEquals(0, main { vm.controller.page.shapes.size })
        main { vm.goToPage(1); vm.goToPage(0) }
        assertTrue("y también el rehacer", main { vm.canRedo })
        main { vm.redo() }
        assertEquals(1, main { vm.controller.page.shapes.size })
    }

    @Test fun aNewPageStartsWithoutHistory() {
        val vm = openEditor()
        main { vm.insertParametric(GeoKind.CIRCLE, 1000, 1000); vm.addPage() }
        assertFalse(main { vm.canUndo })
        assertEquals(0, main { vm.controller.page.shapes.size })
    }
}
