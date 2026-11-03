package io.trazo.app.data

import java.io.Closeable
import java.io.File

/**
 * Process-local file leases for model storage. Every engine that uses a file must hold a lease
 * from before model load until the native engine has finished releasing that file.
 */
internal class LocalModelLeases {
    private val lock = Any()
    private val readers = HashMap<String, Int>()

    fun acquire(modelFile: File): Closeable {
        val path = modelFile.canonicalPath
        synchronized(lock) {
            require(modelFile.isFile) { "Modelo no encontrado: ${modelFile.name}" }
            readers[path] = (readers[path] ?: 0) + 1
        }
        var released = false
        return Closeable {
            synchronized(lock) {
                if (released) return@Closeable
                released = true
                val n = readers[path] ?: 0
                if (n <= 1) readers.remove(path) else readers[path] = n - 1
            }
        }
    }

    /** Enforces no in-use model and performs the destructive action in the same critical section. */
    fun <R> whileUnused(modelFiles: Collection<File>, operation: () -> R): R = synchronized(lock) {
        val paths = modelFiles.map { it.canonicalPath }
        check(paths.none { (readers[it] ?: 0) > 0 }) {
            "El modelo está en uso. Detené la inferencia antes de modificarlo."
        }
        operation()
    }

    fun <R> whileUnused(modelFile: File, operation: () -> R): R = whileUnused(listOf(modelFile), operation)
    fun activeLeases(file: File): Int = synchronized(lock) { readers[file.canonicalPath] ?: 0 }
}
