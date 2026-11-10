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
    val aiChats: io.trazo.app.data.AiChatStore by lazy { io.trazo.app.data.AiChatStore(filesDir) }
    val reviewQueue by lazy {
        io.trazo.ai.DurableReviewQueue(io.trazo.ai.AtomicFileReviewQueueStorage(
            java.io.File(filesDir, "trazo_ai_review_queue_v2.bin")))
    }
    val study: StudyStore by lazy { StudyStore(this) }
    /** Sincronización con los otros dispositivos del usuario (red de casa o Tailscale). */
    val sync: io.trazo.app.sync.SyncService by lazy { io.trazo.app.sync.SyncService(this, repository) }

    /** La revisión en el navegador de otro equipo de la red de casa. */
    val web: io.trazo.app.sync.SyncWebAccess by lazy { io.trazo.app.sync.SyncWebAccess(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReportHandlerInstaller.install(crashReports, appVersionName())
        val images = java.io.File(filesDir, "images")
        io.trazo.app.image.ImagePainter.dir = images
        // Limpieza de imágenes que ya no usa ningún cuaderno, sin demorar el arranque. Sin imágenes no hay
        // nada que limpiar y el repositorio no se crea fuera del hilo principal.
        if (images.list().isNullOrEmpty()) return
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
