package io.trazo.app.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.trazo.app.TrazoApp
import io.trazo.core.DefaultPatterns
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.PresetPatterns
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integración del ViewModel real con el repositorio y el InkController: los cambios pasan por sus
 * listeners, actualizan estado Compose y sobreviven al autosave y a una nueva instancia del VM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
class EditorViewModelIntegrationTest {
    private val app: TrazoApp get() = RuntimeEnvironment.getApplication() as TrazoApp
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `sign patterns conversion page changes and autosave stay coherent across reopen`() {
        val book = app.repository.createNotebook("VM integration ${System.nanoTime()}")
        val firstStore = ViewModelStore()
        var reopenedStore: ViewModelStore? = null

        try {
            val first = onMain { editor(firstStore, book.id) }
            await("editor loaded") { !first.loading }
            assertNull("the first page should open without a load error", onMain { first.pageLoadError })
            onMain { first.applySettings(app.settings.settings.value.copy(mathNotes = true), density = 1f) }

            val patterns = app.repository.patterns.value
            val equal = patterns.single { it.id == "${PresetPatterns.PREFIX}igual" }
            val two = patterns.single { it.id == "${PresetPatterns.PREFIX}2" }
            val plus = patterns.single { it.id == "${PresetPatterns.PREFIX}mas" }
            val three = patterns.single { it.id == "${PresetPatterns.PREFIX}3" }

            val initialRevision = onMain { first.revision }
            // This uses the same VM entry point as the pattern picker and exercises PageChanged.
            onMain { first.insertPattern(equal, viewWidth = 800, viewHeight = 1000) }
            assertTrue("a standalone equals must remain a valid page edit", onMain { first.controller.page.strokes.isNotEmpty() })
            assertTrue("an equals without a left hand side has no computed answer", onMain { first.controller.mathResults.isEmpty() })
            assertTrue("PageChanged must update Compose-observed revision", onMain { first.revision > initialRevision })

            // Use the controller's real pattern insertion path to put 2+3= on its own row.
            onMain {
                first.controller.insertPattern(two, 120f, 360f, height = 40f, nowMs = 20_000L)
                first.controller.insertPattern(plus, 180f, 360f, height = 40f, nowMs = 20_010L)
                first.controller.insertPattern(three, 240f, 360f, height = 40f, nowMs = 20_020L)
                first.controller.insertPattern(equal, 300f, 360f, height = 40f, nowMs = 20_030L)
            }
            val resultInk = onMain { first.controller.mathResults }
            assertTrue("2+3= should derive a 5", resultInk.isNotEmpty())
            assertTrue(resultInk.all { it.patternId == "${PresetPatterns.PREFIX}5" })

            // Conversion raises Converted and PageChanged through the VM's installed listener.
            val handwritten = InkStroke(
                id = "vm-conversion-source",
                points = listOf(InkPoint(620f, 620f), InkPoint(650f, 650f)),
            )
            onMain {
                first.controller.addInk(listOf(handwritten))
                assertNotNull(first.controller.convertWith(DefaultPatterns.integral(), listOf(handwritten.id), 20_040L))
            }
            assertEquals(DefaultPatterns.INTEGRAL, onMain { first.lastConversion?.pattern?.id })
            assertTrue(onMain { first.controller.page.strokes.any { it.patternId == DefaultPatterns.INTEGRAL } })

            onMain { first.undo() }
            assertTrue("undo of conversion restores its source ink", onMain { first.controller.page.strokes.any { it.id == handwritten.id } })
            onMain { first.redo() }
            assertTrue("redo restores the converted ink", onMain { first.controller.page.strokes.any { it.patternId == DefaultPatterns.INTEGRAL } })

            onMain {
                first.addPage()
                assertEquals(1, first.pageIndex)
                assertEquals(2, first.pageCount)
                first.goToPage(0)
            }
            assertEquals("the editor returns to page 1", 0, onMain { first.pageIndex })
            assertTrue("the expression remains on its original page", onMain {
                first.controller.page.strokes.any { it.patternId == "${PresetPatterns.PREFIX}igual" }
            })
            assertNull(onMain { first.pageLoadError })

            // Run the VM's 700 ms debounce on Robolectric's actual main looper, then allow IO to
            // finish and verify the repository snapshot before recreating the editor.
            mainLooper.idleFor(Duration.ofMillis(750))
            await("autosave reached repository") {
                val saved = app.repository.loadLazy(book.id) ?: return@await false
                saved.page(0).strokes.any { it.patternId == DefaultPatterns.INTEGRAL } && saved.pageCount == 2
            }
            val saved = app.repository.loadLazy(book.id)!!
            assertEquals(0, saved.lastPage)
            assertFalse(saved.page(0).strokes.any { it.id == handwritten.id })
            assertTrue(saved.page(0).strokes.any { it.patternId == DefaultPatterns.INTEGRAL })
            assertTrue(saved.page(0).strokes.any { it.patternId == "${PresetPatterns.PREFIX}igual" })

            onMain { firstStore.clear() }
            val secondStore = ViewModelStore()
            reopenedStore = secondStore
            val reopened = onMain { editor(secondStore, book.id) }
            await("reopened editor loaded") { !reopened.loading }
            assertNull("reopening the saved page must not report a decode error", onMain { reopened.pageLoadError })
            assertEquals(0, onMain { reopened.pageIndex })
            assertTrue(onMain { reopened.controller.page.strokes.any { it.patternId == DefaultPatterns.INTEGRAL } })
            assertTrue(onMain { reopened.controller.mathResults.any { it.patternId == "${PresetPatterns.PREFIX}5" } })
        } finally {
            onMain {
                firstStore.clear()
                reopenedStore?.clear()
            }
            app.repository.deleteNotebook(book.id)
        }
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
        val handler = android.os.Handler(Looper.getMainLooper())
        var value: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            value = runCatching(block)
            latch.countDown()
        }
        mainLooper.idle()
        check(latch.await(5, TimeUnit.SECONDS)) { "main looper did not run the posted test action" }
        return requireNotNull(value).getOrThrow()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            mainLooper.idle()
            if (onMain(condition)) return
            Thread.sleep(5)
        }
        assertTrue("timed out waiting for $description", onMain(condition))
    }
}
