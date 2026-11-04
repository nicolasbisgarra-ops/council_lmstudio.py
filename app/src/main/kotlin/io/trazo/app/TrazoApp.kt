package io.trazo.app

import android.app.Application
import android.content.Context
import io.trazo.app.data.AiJournal
import io.trazo.app.data.AiStore
import io.trazo.app.data.CrashReportHandlerInstaller
import io.trazo.app.data.CrashReportStore
import io.trazo.app.data.SettingsStore
import io.trazo.app.data.StudyStore
import io.trazo.app.data.TrazoRepository

class TrazoApp : Application() {
    val crashReports: CrashReportStore by lazy { CrashReportStore(filesDir) }
    val repository: TrazoRepository by lazy { TrazoRepository(filesDir) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val ai: AiStore by lazy { AiStore(this) }
    val aiJournal: AiJournal by lazy { AiJournal(filesDir) }
    val reviewQueue by lazy {
        io.trazo.ai.DurableReviewQueue(io.trazo.ai.AtomicFileReviewQueueStorage(
            java.io.File(filesDir, "trazo_ai_review_queue_v2.bin")))
    }
    val study: StudyStore by lazy { StudyStore(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReportHandlerInstaller.install(crashReports, appVersionName())
        io.trazo.app.ink.ImagePainter.dir = java.io.File(filesDir, "images")
        // Limpieza de imágenes que ya no usa ningún cuaderno, sin demorar el arranque.
        Thread({ runCatching { repository.collectUnusedImages() } }, "trazo-images-gc").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}

val Context.trazo: TrazoApp get() = applicationContext as TrazoApp
