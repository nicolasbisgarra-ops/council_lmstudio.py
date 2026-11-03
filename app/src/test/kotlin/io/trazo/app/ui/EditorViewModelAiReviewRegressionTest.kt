package io.trazo.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.trazo.ai.AiProfile
import io.trazo.ai.AiProvider
import io.trazo.ai.AiReview
import io.trazo.app.TrazoApp
import io.trazo.app.ink.InkCanvasView
import io.trazo.core.GeoKind
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.ParametricInk
import java.io.File
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = TrazoApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelAiReviewRegressionTest {
    private val app: TrazoApp get() = RuntimeEnvironment.getApplication() as TrazoApp
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `selected parametric shape sends its rendered ink and compact raw metadata`() {
        val book = app.repository.createNotebook("AI shape payload ${System.nanoTime()}")
        val store = ViewModelStore()
        val profileId = "vm-shape-${System.nanoTime()}"
        val previousProfile = app.ai.settings.value.current?.id
        val callCount = AtomicInteger()
        val sentLines = AtomicReference<List<String>>()
        val sentPng = AtomicReference<ByteArray?>()
        app.ai.save(AiProfile(profileId, "VM fake review", AiProvider.OPENAI_COMPATIBLE, model = "fake"))
        app.ai.select(profileId)

        try {
            val vm = onMain {
                editor(store, book.id, { _, _, lines, png, _ ->
                    callCount.incrementAndGet()
                    sentLines.set(lines)
                    sentPng.set(png)
                    AiReview(correct = true, score = null, marks = emptyList())
                }, { true })
            }
            await("editor loaded") { !onMain { vm.loading } }

            lateinit var shapeId: String
            lateinit var expectedRenderIds: Set<String>
            onMain {
                vm.insertParametric(GeoKind.CIRCLE, 800, 1000)
                shapeId = requireNotNull(vm.controller.selectedShapeId)
                expectedRenderIds = vm.controller.page.allInk
                    .filter { it.groupId == ParametricInk.groupOf(shapeId) }
                    .map { it.id }
                    .toSet()
                assertTrue("the circle should expose stable rendered strokes", expectedRenderIds.isNotEmpty())
                vm.reviewWithAi(selectionOnly = true)
            }

            await("fake AI review completed") { callCount.get() == 1 && onMain { vm.aiProgress?.running == false && vm.aiProgress?.message?.startsWith("Sin errores") == true } }

            val raw = requireNotNull(sentLines.get()).single()
            assertNotNull("review should include a rendered PNG", sentPng.get())
            assertTrue("captured PNG should have a PNG signature", sentPng.get()!!.copyOfRange(0, 8).contentEquals(
                byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            ))
            assertTrue(raw.startsWith("{\"box\":["))
            assertTrue(raw.contains("\"labels\":["))
            assertTrue(raw.contains("\"unlabeled\":"))
            assertFalse("wire metadata must not contain generated UUIDs", raw.contains(shapeId))
            assertFalse("wire metadata must not contain internal render ids", raw.contains("parametric:"))

            val record = onMain { vm.controller.page.ai?.history?.lastOrNull() }
            assertNotNull("the saved review record should retain the shape's internal IDs", record)
            assertTrue(requireNotNull(record).selection)
            assertEquals(expectedRenderIds, record.strokeIds.toSet())
        } finally {
            onMain { store.clear() }
            app.repository.deleteNotebook(book.id)
            app.ai.delete(profileId)
            previousProfile?.let { if (app.ai.settings.value.profiles.any { p -> p.id == it }) app.ai.select(it) }
        }
    }

    @Test
    fun `editing a selected shape while review is running rejects the stale result`() {
        val book = app.repository.createNotebook("AI stale shape ${System.nanoTime()}")
        val store = ViewModelStore()
        val profileId = "vm-stale-${System.nanoTime()}"
        val previousProfile = app.ai.settings.value.current?.id
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        app.ai.save(AiProfile(profileId, "VM stale fake", AiProvider.OPENAI_COMPATIBLE, model = "fake"))
        app.ai.select(profileId)

        try {
            val vm = onMain {
                editor(store, book.id, { _, _, _, _, _ ->
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    AiReview(correct = true, score = null, marks = emptyList())
                }, { true })
            }
            await("editor loaded") { !onMain { vm.loading } }
            onMain {
                vm.insertParametric(GeoKind.CIRCLE, 800, 1000)
                vm.reviewWithAi(selectionOnly = true)
            }
            await("fake reviewer started") { started.count == 0L }
            onMain {
                vm.controller.editShape { shape -> shape.copy(origin = shape.origin.copy(x = shape.origin.x + 40f)) }
            }
            release.countDown()
            await("stale review was rejected") {
                onMain { vm.aiProgress?.running == false && vm.aiProgress?.message == "La página cambió durante la revisión." }
            }
            assertTrue(onMain { vm.controller.page.ai?.history.isNullOrEmpty() })
            assertTrue(onMain { vm.aiNotes.isEmpty() })
        } finally {
            release.countDown()
            onMain { store.clear() }
            app.repository.deleteNotebook(book.id)
            app.ai.delete(profileId)
            previousProfile?.let { if (app.ai.settings.value.profiles.any { p -> p.id == it }) app.ai.select(it) }
        }
    }

    @Test
    fun `pending request stays queued across reopen and page switches until explicit review`() {
        val book = app.repository.createNotebook("AI explicit queue ${System.nanoTime()}")
        val profileId = "vm-queue-${System.nanoTime()}"
        val previousProfile = app.ai.settings.value.current?.id
        val firstStore = ViewModelStore()
        var reopenedStore: ViewModelStore? = null
        app.ai.save(AiProfile(profileId, "VM queue fake", AiProvider.OPENAI_COMPATIBLE, model = "fake"))
        app.ai.select(profileId)

        try {
            val offline = onMain { editor(firstStore, book.id, { _, _, _, _, _ -> error("offline must not call the reviewer") }, { false }) }
            await("offline editor loaded") { !onMain { offline.loading } }
            onMain {
                offline.controller.addInk(listOf(InkStroke("queued-source", listOf(InkPoint(100f, 100f), InkPoint(180f, 150f)))))
                offline.addPage()
                offline.goToPage(0)
                offline.reviewWithAi()
                offline.saveNow()
            }
            await("request persisted in durable queue") { onMain { offline.aiPending == 1 } }
            onMain { offline.goToPage(1) }
            assertEquals("page navigation must not send a pending request", 1, onMain { offline.aiPending })
            onMain { firstStore.clear() }

            val calls = AtomicInteger()
            val online = onMain {
                val newStore = ViewModelStore().also { reopenedStore = it }
                editor(newStore, book.id, { _, _, _, _, _ ->
                    calls.incrementAndGet()
                    AiReview(correct = true, score = null, marks = emptyList())
                }, { true })
            }
            await("reopened editor loaded") { !onMain { online.loading } }
            assertEquals(0, calls.get())
            assertEquals(1, onMain { online.aiPending })
            onMain { online.goToPage(0) }
            assertEquals("opening a queued page by navigation must not send it", 0, calls.get())
            onMain { online.goToPage(1) }
            assertEquals("switching away from a queued page must not send it", 0, calls.get())
            onMain { online.goToPage(0); online.reviewWithAi() }
            await("explicit review sends the pending request") { calls.get() == 1 && onMain { online.aiPending == 0 } }
        } finally {
            onMain {
                firstStore.clear()
                reopenedStore?.clear()
            }
            runCatching { app.reviewQueue.discardPage(book.id, book.pages.first().id) }
            app.repository.deleteNotebook(book.id)
            app.ai.delete(profileId)
            previousProfile?.let { if (app.ai.settings.value.profiles.any { p -> p.id == it }) app.ai.select(it) }
        }
    }

    /** Set `-Dtrazo.userNotes=<directory>` to decode and draw every page in each supplied archive. */
    @Test
    fun `optional real user notes open every page through the native canvas`() {
        val root = System.getProperty("trazo.userNotes")?.let(::File)
        assumeTrue("set -Dtrazo.userNotes to enable real user-note coverage", root?.isDirectory == true)
        val archives = requireNotNull(root).listFiles { file -> file.isFile && file.extension.equals("zip", true) }
            .orEmpty().sortedBy { it.name }
        assumeTrue("the user-note directory should contain archive files", archives.isNotEmpty())

        for (archive in archives) {
            val notebookId = archive.inputStream().use { app.repository.importNotebook(it).id }
            val store = ViewModelStore()
            var activityController: ActivityController<Activity>? = null
            var view: InkCanvasView? = null
            var bitmap: Bitmap? = null
            try {
                val vm = onMain { editor(store, notebookId) }
                await("${archive.name} editor loaded") { !onMain { vm.loading } }
                activityController = onMain { Robolectric.buildActivity(Activity::class.java).setup() }
                val activity = requireNotNull(activityController).get()
                onMain {
                    view = InkCanvasView(activity).apply {
                        controller = vm.controller
                        viewport = vm.viewport
                        paper = vm.paper
                        hapticsEnabled = false
                    }
                    activity.setContentView(requireNotNull(view))
                    requireNotNull(view).measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                    )
                    requireNotNull(view).layout(0, 0, 720, 1000)
                    assertTrue("native canvas should attach its controller listener", requireNotNull(view).isAttachedToWindow)
                    bitmap = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
                }
                val pageCount = onMain { vm.pageCount }
                assertTrue("${archive.name} should have pages", pageCount > 0)
                val target = requireNotNull(bitmap)
                val targetCanvas = Canvas(target)
                val pixels = IntArray(720 * 1000)
                val frameHashes = LinkedHashSet<Int>()
                for (index in 0 until pageCount) {
                    frameHashes += onMain {
                        vm.goToPage(index)
                        val canvasView = requireNotNull(view)
                        // Match AndroidView.update: the VM resets fit on navigation and the native View
                        // must receive the shared viewport again before drawing the new page.
                        canvasView.viewport = vm.viewport
                        canvasView.paper = vm.paper
                        canvasView.invalidate()
                        target.eraseColor(Color.WHITE)
                        canvasView.draw(targetCanvas)
                        target.getPixels(pixels, 0, 720, 0, 0, 720, 1000)
                        Arrays.hashCode(pixels)
                    }
                }
                assertTrue("${archive.name}: expected visibly distinct pages, hashes=$frameHashes", frameHashes.size >= 2)
            } finally {
                onMain { activityController?.pause()?.stop()?.destroy() }
                onMain { store.clear() }
                view = null
                bitmap?.recycle()
                bitmap = null
                app.repository.deleteNotebook(notebookId)
            }
        }
    }

    private fun editor(
        store: ViewModelStore,
        notebookId: String,
        reviewer: AiReviewCall? = null,
        online: (() -> Boolean)? = null,
    ): EditorViewModel {
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass.isAssignableFrom(EditorViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return EditorViewModel(app, notebookId, reviewer, online) as T
            }
        })
        return provider[EditorViewModel::class.java]
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        val handler = android.os.Handler(Looper.getMainLooper())
        var value: Result<T>? = null
        val latch = CountDownLatch(1)
        handler.post {
            value = runCatching(block)
            latch.countDown()
        }
        mainLooper.idle()
        check(latch.await(5, TimeUnit.SECONDS)) { "main looper did not run the posted test action" }
        return requireNotNull(value).getOrThrow()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            mainLooper.idle()
            if (onMain(condition)) return
            Thread.sleep(5)
        }
        assertTrue("timed out waiting for $description", onMain(condition))
    }
}
