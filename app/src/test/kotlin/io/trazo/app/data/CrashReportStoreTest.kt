package io.trazo.app.data

import android.app.Application
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class CrashReportStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `report keeps runtime classification and frames but excludes exception messages`() {
        val directory = temporary.newFolder("report")
        val store = CrashReportStore(directory)
        assertFalse(store.hasReport())
        val error = IllegalStateException(
            "private token and written expression 2+3=",
            IllegalArgumentException("nested private content"),
        ).apply {
            stackTrace = arrayOf(StackTraceElement("io.trazo.test.Editor", "complete", "Editor.kt", 42))
        }

        store.save(Thread("editor-worker"), error, appVersion = "0.2.test", sdk = 34, timeMillis = 1_234_567L)

        val report = store.readLatest()
        assertNotNull(report)
        assertTrue(store.hasReport())
        val savedReport = requireNotNull(report)
        assertTrue(savedReport.contains("appVersion: 0.2.test"))
        assertTrue(savedReport.contains("sdk: 34"))
        assertTrue(savedReport.contains("threadName: editor-worker"))
        assertTrue(savedReport.contains("exception[0]: java.lang.IllegalStateException"))
        assertTrue(savedReport.contains("exception[1]: java.lang.IllegalArgumentException"))
        assertTrue(savedReport.contains("at io.trazo.test.Editor.complete(Editor.kt:42)"))
        assertFalse(savedReport.contains("private token"))
        assertFalse(savedReport.contains("2+3="))
        assertFalse(savedReport.contains("nested private content"))
        assertEquals(1_234_567L, CrashReportStore.timeOf(savedReport))
        store.clear()
        assertFalse(store.hasReport())
        assertEquals(null, CrashReportStore.timeOf("Trazo uncaught exception report v1\nsdk: 34\n"))
    }

    @Test
    fun `report is byte bounded and replaces the previous report`() {
        val directory = temporary.newFolder("bounded")
        val store = CrashReportStore(directory)
        store.save(Thread("first"), IllegalStateException("first message"), "0.1", 33)
        val longTrace = Array(10_000) { index ->
            StackTraceElement("x".repeat(500), "frame$index", "LongFileName.kt", index)
        }
        val longError = OutOfMemoryError("must not be persisted").apply { stackTrace = longTrace }

        store.save(Thread("second"), longError, "0.2", 34)

        val report = requireNotNull(store.readLatest())
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= CrashReportStore.MAX_REPORT_BYTES)
        assertTrue(report.contains("exception[0]: java.lang.OutOfMemoryError"))
        assertTrue(report.contains("[report truncated]"))
        assertFalse(report.contains("first message"))
        assertFalse(report.contains("must not be persisted"))
        assertFalse(report.contains("exception[0]: java.lang.IllegalStateException"))
    }

    @Test
    fun `handler installation is idempotent and a replacement delegates once to original`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val delegated = AtomicInteger()
        val receivedThread = arrayOfNulls<Thread>(1)
        val receivedError = arrayOfNulls<Throwable>(1)
        val original = Thread.UncaughtExceptionHandler { thread, error ->
            delegated.incrementAndGet()
            receivedThread[0] = thread
            receivedError[0] = error
        }
        val firstStore = CrashReportStore(temporary.newFolder("first-handler"))
        val secondStore = CrashReportStore(temporary.newFolder("second-handler"))
        val crashThread = Thread("crash-thread")
        val error = IllegalArgumentException("not in the report")
        Thread.setDefaultUncaughtExceptionHandler(original)

        try {
            CrashReportHandlerInstaller.install(firstStore, "0.2", sdk = 34)
            val first = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(first)
            CrashReportHandlerInstaller.install(CrashReportStore(firstStore.directory), "0.2", sdk = 34)
            assertSame("repeated Application.onCreate must not stack handlers", first, Thread.getDefaultUncaughtExceptionHandler())

            CrashReportHandlerInstaller.install(secondStore, "0.2", sdk = 34)
            val replacement = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(replacement)
            assertNotSame(first, replacement)
            requireNotNull(replacement).uncaughtException(crashThread, error)

            assertEquals(1, delegated.get())
            assertSame(crashThread, receivedThread[0])
            assertSame(error, receivedError[0])
            assertFalse("replacement must unwrap its own old handler", firstStore.hasReport())
            assertTrue(secondStore.hasReport())
            assertFalse(requireNotNull(secondStore.readLatest()).contains("not in the report"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `handler delegates even when persisting the report fails`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val delegated = AtomicInteger()
        val original = Thread.UncaughtExceptionHandler { _, _ -> delegated.incrementAndGet() }
        val invalidDirectory = temporary.newFile("not-a-directory")
        Thread.setDefaultUncaughtExceptionHandler(original)

        try {
            CrashReportHandlerInstaller.install(CrashReportStore(invalidDirectory), "0.2", sdk = 34)
            val handler = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(Thread("crash-thread"), IllegalStateException("not stored"))
            assertEquals("the original handler still runs after a write failure", 1, delegated.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
