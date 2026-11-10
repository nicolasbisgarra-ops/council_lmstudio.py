package io.trazo.app.sync

import android.util.AtomicFile
import io.trazo.core.share.WebAccessConfig
import io.trazo.core.share.WebStateBuilder
import java.io.File

/**
 * Lo que la web de revisión guarda: si está prendida, su puerto y los navegadores vinculados (solo el hash
 * de su secreto). Vive en `sync/`, que queda fuera de las copias de seguridad.
 */
class SyncWebStore(root: File) {
    private val file = AtomicFile(File(File(root, "sync").apply { mkdirs() }, "web.json"))

    @Synchronized
    fun load(): WebAccessConfig {
        if (!file.baseFile.exists()) return WebAccessConfig()
        return runCatching {
            WebStateBuilder.JSON.decodeFromString(WebAccessConfig.serializer(), file.readFully().toString(Charsets.UTF_8))
        }.getOrDefault(WebAccessConfig())
    }

    @Synchronized
    fun save(c: WebAccessConfig) {
        val out = file.startWrite()
        try {
            out.write(WebStateBuilder.JSON.encodeToString(WebAccessConfig.serializer(), c).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
