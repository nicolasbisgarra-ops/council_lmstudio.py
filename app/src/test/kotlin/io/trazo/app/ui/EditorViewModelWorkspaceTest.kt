package io.trazo.app.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.trazo.app.TrazoApp
import io.trazo.core.Bounds
import io.trazo.core.DefaultPatterns
import io.trazo.core.GeoKind
import io.trazo.core.GeoPoint
import io.trazo.core.Notebook
import io.trazo.core.bounds
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Patternos y formas se insertan en el centro visible, también fuera de la página A4 central. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
class EditorViewModelWorkspaceTest {
    private val app: TrazoApp
        get() = RuntimeEnvironment.getApplication() as TrazoApp

    private val mainLooper
        get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `inserciones fuera de la pagina central conservan centro y persisten`() {
        val notebook = app.repository.createNotebook("VM workspace ${System.nanoTime()}")
        val store = ViewModelStore()
        val targets = listOf(GeoPoint(-500f, -700f), GeoPoint(1500f, 2000f))
        val pattern = DefaultPatterns.integral()

        try {
            val vm = onMain { editor(store, notebook.id) }
            await("editor loaded") { !onMain { vm.loading } }

            onMain {
                assertNull(vm.pageLoadError)
                targets.forEach { target ->
                    val width = 800
                    val height = 1000
                    vm.viewport.scale = 1f
                    vm.viewport.tx = width / 2f - target.x
                    vm.viewport.ty = height / 2f - target.y

                    val firstPatternStroke = vm.controller.page.strokes.size
                    vm.insertPattern(pattern, width, height)
                    val insertedInk = vm.controller.page.strokes.drop(firstPatternStroke)
                    assertTrue("pattern ink should be inserted", insertedInk.isNotEmpty())
                    assertCenter(insertedInk.bounds(), target, "live pattern")

                    vm.insertParametric(GeoKind.CIRCLE, width, height)
                    val shape = vm.controller.page.shapes.last()
                    assertCenter(shape.origin, target, "live shape")
                    assertOutsideCentralPage(target)
                }

                vm.saveNow()
            }

            val saved = requireNotNull(app.repository.loadLazy(notebook.id))
            val savedPage = saved.page(0)
            val savedPatternGroups = savedPage.strokes
                .filter { it.patternId == pattern.id }
                .groupBy { it.groupId }
                .values
            assertEquals("one saved group per pattern insertion", targets.size, savedPatternGroups.size)
            savedPatternGroups.zip(targets).forEach { (group, target) ->
                assertCenter(group.bounds(), target, "saved pattern")
                assertOutsideCentralPage(target)
            }
            assertEquals("one saved parametric shape per insertion", targets.size, savedPage.parametricShapes.size)
            savedPage.parametricShapes.zip(targets).forEach { (shape, target) ->
                assertCenter(shape.origin, target, "saved shape")
                assertOutsideCentralPage(target)
            }
        } finally {
            onMain { store.clear() }
            app.repository.deleteNotebook(notebook.id)
        }
    }

    private fun assertCenter(bounds: Bounds, target: GeoPoint, label: String) {
        assertEquals("$label x center", target.x, bounds.centerX, 0.05f)
        assertEquals("$label y center", target.y, bounds.centerY, 0.05f)
    }

    private fun assertCenter(point: GeoPoint, target: GeoPoint, label: String) {
        assertEquals("$label x center", target.x, point.x, 0.001f)
        assertEquals("$label y center", target.y, point.y, 0.001f)
    }

    private fun assertOutsideCentralPage(target: GeoPoint) {
        assertTrue("x should be outside the central A4 page", target.x < 0f || target.x > Notebook.PAGE_WIDTH)
        assertTrue("y should be outside the central A4 page", target.y < 0f || target.y > Notebook.PAGE_HEIGHT)
    }

    private fun editor(store: ViewModelStore, notebookId: String): EditorViewModel {
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass.isAssignableFrom(EditorViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return EditorViewModel(app, notebookId) as T
            }
        })
        return provider[EditorViewModel::class.java]
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        val handler = Handler(Looper.getMainLooper())
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        handler.post {
            result = runCatching(block)
            latch.countDown()
        }
        mainLooper.idle()
        check(latch.await(5, TimeUnit.SECONDS)) { "main looper did not run the posted test action" }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            mainLooper.idleFor(Duration.ofMillis(5))
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("timed out waiting for $description", condition())
    }
}
