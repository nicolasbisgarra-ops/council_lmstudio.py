package io.trazo.app.data

import android.os.Build
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/** One bounded, local report of the most recent uncaught exception. */
class CrashReportStore(directory: File) {
    internal val directory: File = directory.absoluteFile
    private val file = AtomicFile(File(this.directory, FILE_NAME))

    fun hasReport(): Boolean = readLatest() != null

    fun readLatest(): String? = runCatching {
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_REPORT_BYTES + 1 - total))
                if (count < 0) break
                total += count
                if (total > MAX_REPORT_BYTES) return null
                output.write(buffer, 0, count)
            }
            if (total == 0) return null
            output.toByteArray()
        }
        bytes.toString(StandardCharsets.UTF_8)
    }.getOrNull()

    /** Stores class names and frames only. Exception messages and application content are omitted. */
    fun save(thread: Thread, error: Throwable, appVersion: String, sdk: Int = Build.VERSION.SDK_INT) {
        val bytes = reportBytes(thread, error, appVersion, sdk)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Unable to create crash report directory")
        }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            runCatching { file.failWrite(stream) }
            throw failure
        }
    }

    private fun reportBytes(thread: Thread, error: Throwable, appVersion: String, sdk: Int): ByteArray {
        val out = StringBuilder()
        var bytesWritten = 0
        var truncated = false

        fun appendLine(value: String): Boolean {
            val cleaned = value.take(MAX_LINE_CHARS).map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
            val line = "$cleaned\n"
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            if (bytesWritten + bytes.size > MAX_REPORT_BYTES - TRUNCATED_LINE_BYTES) {
                truncated = true
                return false
            }
            out.append(line)
            bytesWritten += bytes.size
            return true
        }

        appendLine("Trazo uncaught exception report v1")
        appendLine("appVersion: ${safeValue(appVersion)}")
        appendLine("sdk: $sdk")
        appendLine("threadId: ${thread.id}")
        appendLine("threadName: ${safeValue(thread.name)}")

        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var causeIndex = 0
        while (current != null && seen.add(current) && causeIndex < MAX_CAUSES) {
            if (!appendLine("exception[$causeIndex]: ${current.javaClass.name.take(512)}")) break
            val frames = current.stackTrace
            for (index in 0 until minOf(frames.size, MAX_FRAMES_PER_CAUSE)) {
                val frame = frames[index]
                val source = when {
                    frame.fileName == null && frame.isNativeMethod -> "Native Method"
                    frame.fileName == null -> "Unknown Source"
                    frame.lineNumber >= 0 -> "${frame.fileName.take(256)}:${frame.lineNumber}"
                    else -> frame.fileName.take(256)
                }
                if (!appendLine("  at ${frame.className.take(384)}.${frame.methodName.take(160)}($source)")) break
            }
            if (truncated) break
            if (frames.size > MAX_FRAMES_PER_CAUSE) {
                truncated = true
                break
            }
            current = current.cause
            causeIndex++
        }
        if (current != null && causeIndex >= MAX_CAUSES) truncated = true
        if (truncated) out.append(TRUNCATED_LINE)
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun safeValue(value: String): String = value
        .take(MAX_METADATA_CHARS)
        .map { if (Character.isISOControl(it)) ' ' else it }
        .joinToString("")

    companion object {
        const val MAX_REPORT_BYTES = 64 * 1024
        private const val FILE_NAME = "last-crash-report.txt"
        private const val MAX_LINE_CHARS = 768
        private const val MAX_METADATA_CHARS = 160
        private const val MAX_CAUSES = 16
        private const val MAX_FRAMES_PER_CAUSE = 2048
        private const val TRUNCATED_LINE = "[report truncated]\n"
        private const val TRUNCATED_LINE_BYTES = 32
    }
}

/** Installs a process handler without retaining an Application or stacking this wrapper on recreate. */
internal object CrashReportHandlerInstaller {
    @Synchronized
    fun install(store: CrashReportStore, appVersion: String, sdk: Int = Build.VERSION.SDK_INT) {
        val current = Thread.getDefaultUncaughtExceptionHandler() ?: return
        if (current is CrashReportUncaughtExceptionHandler && current.matches(store, appVersion, sdk)) return

        val delegate = originalDelegate(current) ?: return
        Thread.setDefaultUncaughtExceptionHandler(
            CrashReportUncaughtExceptionHandler(delegate, store, appVersion, sdk),
        )
    }

    private fun originalDelegate(handler: Thread.UncaughtExceptionHandler): Thread.UncaughtExceptionHandler? {
        var current = handler
        val seen = Collections.newSetFromMap(IdentityHashMap<Thread.UncaughtExceptionHandler, Boolean>())
        while (current is CrashReportUncaughtExceptionHandler && seen.add(current)) {
            current = current.delegate ?: return null
        }
        return current
    }
}

internal class CrashReportUncaughtExceptionHandler(
    internal val delegate: Thread.UncaughtExceptionHandler?,
    private val store: CrashReportStore,
    private val appVersion: String,
    private val sdk: Int,
) : Thread.UncaughtExceptionHandler {
    fun matches(otherStore: CrashReportStore, otherVersion: String, otherSdk: Int): Boolean =
        store.directory == otherStore.directory && appVersion == otherVersion && sdk == otherSdk

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            store.save(thread, error, appVersion, sdk)
        } catch (_: Throwable) {
            // Diagnostics must never replace the process's original uncaught-exception behavior.
        }
        delegate?.uncaughtException(thread, error)
    }
}
