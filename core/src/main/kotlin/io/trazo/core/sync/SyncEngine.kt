package io.trazo.core.sync

import io.trazo.core.Covers
import io.trazo.core.FolderCatalog
import io.trazo.core.IdGenerator
import io.trazo.core.LazyNotebook
import io.trazo.core.NotebookOrder
import io.trazo.core.PageImages
import io.trazo.core.TrazoFormat
import java.io.EOFException
import java.util.Base64

/**
 * Los datos del dispositivo, vistos por la sincronización. Las escrituras son condicionales: solo se
 * hacen si el archivo sigue siendo el que se leyó ([SyncHash.fingerprint]), así un cambio hecho
 * mientras tanto nunca se pisa (queda para la vuelta siguiente).
 */
interface SyncStore {
    val deviceId: String
    /** Huellas de los cuadernos por id (pueden salir de una caché); los ilegibles no aparecen. */
    fun notebookPrints(): Map<String, NotebookPrint>
    /** Cuadernos abiertos en el editor. */
    fun busy(): Set<String>
    fun read(key: SyncKey): String?
    /**
     * Reemplaza el archivo por [text] (null lo borra) si su huella actual es [expect]. Un cuaderno
     * conserva su `lastPage` local y uno abierto en el editor no se toca.
     */
    fun replace(key: SyncKey, expect: String, text: String?): Boolean
    fun blobNames(): Set<String>
    fun readBlob(name: String): ByteArray?
    fun writeBlob(name: String, bytes: ByteArray)
    fun loadBase(peerId: String): SyncBase
    fun saveBase(peerId: String, base: SyncBase)
}

/** El otro dispositivo está sincronizando con un tercero; se reintenta más tarde. */
class SyncBusyException : Exception("El otro dispositivo está ocupado")

/** Qué pasó en una vuelta, para mostrarlo. */
data class SyncReport(
    val received: Int = 0,
    val sent: Int = 0,
    val conflicts: Int = 0,
    /** Cuadernos que quedaron para la próxima (abiertos o cambiados durante la vuelta). */
    val deferred: Int = 0,
)

/**
 * Una vuelta de sincronización. El que llama ([runClient]) pide el estado del otro, fusiona aquí y le
 * manda el resultado; el otro ([serve]) solo lee y escribe lo que le piden.
 */
class SyncEngine(private val store: SyncStore, private val ids: IdGenerator = IdGenerator.RANDOM) {

    private var patternsMemo: Pair<String, Map<String, String>>? = null
    private var foldersMemo: Pair<String, Map<String, String>>? = null

    fun manifest(): SyncManifest {
        val prints = store.notebookPrints()
        val order = NotebookOrder.decode(store.read(SyncKey.Order))
        return SyncManifest(
            deviceId = store.deviceId,
            notebooks = prints,
            busy = store.busy(),
            patterns = patternHashes(store.read(SyncKey.Patterns)),
            folders = folderHashes(store.read(SyncKey.Folders)),
            order = SyncMerge.orderHash(order),
            blobs = store.blobNames().filter(SyncProtocol::isValidBlob).toSet(),
        )
    }

    private fun patternHashes(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val fp = SyncHash.short(text)
        patternsMemo?.let { if (it.first == fp) return it.second }
        val h = runCatching { SyncMerge.patternHashes(TrazoFormat.decodePatterns(text)) }.getOrDefault(emptyMap())
        patternsMemo = fp to h
        return h
    }

    private fun folderHashes(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val fp = SyncHash.short(text)
        foldersMemo?.let { if (it.first == fp) return it.second }
        val h = SyncMerge.folderHashes(FolderCatalog.decode(text))
        foldersMemo = fp to h
        return h
    }

    // ------------------------------------------------------------------ el que llama

    fun runClient(ch: SecureChannel): SyncReport {
        val peer = ch.peerId
        ch.send(SyncMessage.GetManifest)
        val remote = when (val m = ch.receiveMessage()) {
            is SyncMessage.Manifest -> m.manifest
            SyncMessage.Busy -> throw SyncBusyException()
            else -> throw SyncAuthException("Respuesta inesperada")
        }
        if (remote.protocol != SyncProtocol.VERSION) throw IllegalStateException("Versiones de Trazo distintas: actualizá las dos")
        val local = manifest()
        val base = store.loadBase(peer)
        var received = 0
        var sent = 0
        var conflicts = 0
        var deferred = 0

        // Imágenes y portadas primero: los cuadernos que llegan ya las encuentran.
        for (name in (remote.blobs - local.blobs).filter(SyncProtocol::isValidBlob).sorted()) {
            ch.send(SyncMessage.GetBlob(name))
            val b = ch.receiveMessage() as? SyncMessage.Blob ?: throw SyncAuthException("Respuesta inesperada")
            val bytes = b.data?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: continue
            if (b.name == name && blobMatches(name, bytes)) store.writeBlob(name, bytes)
        }
        for (name in (local.blobs - remote.blobs).sorted()) {
            val bytes = store.readBlob(name) ?: continue
            ch.send(SyncMessage.PutBlob(name, Base64.getEncoder().encodeToString(bytes)))
            ch.receiveMessage() as? SyncMessage.Ok ?: throw SyncAuthException("Respuesta inesperada")
        }

        fun get(key: SyncKey): SyncMessage.Text {
            ch.send(SyncMessage.Get(key.wire))
            val t = ch.receiveMessage() as? SyncMessage.Text ?: throw SyncAuthException("Respuesta inesperada")
            if (t.key != key.wire) throw SyncAuthException("Respuesta inesperada")
            return t
        }

        fun put(key: SyncKey, text: String?, expect: String): Boolean {
            ch.send(SyncMessage.Put(key.wire, text, expect))
            val r = ch.receiveMessage() as? SyncMessage.PutResult ?: throw SyncAuthException("Respuesta inesperada")
            return r.key == key.wire && r.ok
        }

        // ---- cuadernos
        val notebooks = base.notebooks.toMutableMap()
        val allIds = (local.notebooks.keys + remote.notebooks.keys + base.notebooks.keys).sorted()
        for (id in allIds) {
            val lp = local.notebooks[id]
            val rp = remote.notebooks[id]
            if (lp == rp) {
                if (lp == null) notebooks.remove(id) else notebooks[id] = lp
                continue
            }
            if (id in local.busy || id in remote.busy) { deferred++; continue }
            val key = SyncKey.NotebookKey(id)
            val localText = if (lp != null) store.read(key) else null
            val localNb = localText?.let { decodeNotebook(it, id) }
            if (localText != null && localNb == null) { deferred++; continue }
            val r = if (rp != null) get(key) else null
            val remoteNb = r?.text?.let { decodeNotebook(it, id) }
            if (r?.text != null && remoteNb == null) { deferred++; continue }
            val result = SyncMerge.notebook(base.notebooks[id], localNb, remoteNb, ids)
            val merged = result.notebook
            val mergedPrint = merged?.let(SyncMerge::print)
            val mergedText = merged?.let(TrazoFormat::encodeLazy)
            var ok = true
            if (mergedPrint != lp) {
                if (store.replace(key, SyncHash.fingerprint(localText), mergedText)) received++ else ok = false
            }
            if (ok && mergedPrint != rp) {
                if (put(key, mergedText, r?.fingerprint ?: SyncHash.fingerprint(null))) sent++ else ok = false
            }
            if (ok) {
                conflicts += result.conflicts
                if (mergedPrint == null) notebooks.remove(id) else notebooks[id] = mergedPrint
            } else deferred++
        }

        // ---- patrones
        var patterns = base.patterns
        if (local.patterns == remote.patterns) patterns = local.patterns else {
            val lt = store.read(SyncKey.Patterns)
            val rt = get(SyncKey.Patterns)
            val l = lt?.let { runCatching { TrazoFormat.decodePatterns(it) }.getOrNull() }
            val rl = rt.text?.let { runCatching { TrazoFormat.decodePatterns(it) }.getOrNull() }
            if ((lt == null || l != null) && (rt.text == null || rl != null)) {
                val merged = SyncMerge.patterns(base.patterns, l.orEmpty(), rl.orEmpty())
                val text = TrazoFormat.encodePatterns(merged)
                val mh = SyncMerge.patternHashes(merged)
                var ok = mh == local.patterns || store.replace(SyncKey.Patterns, SyncHash.fingerprint(lt), text)
                if (ok && mh != remote.patterns) ok = put(SyncKey.Patterns, text, rt.fingerprint)
                if (ok) { patterns = mh; received++ }
            }
        }

        // ---- carpetas
        var folders = base.folders
        if (local.folders == remote.folders) folders = local.folders else {
            val lt = store.read(SyncKey.Folders)
            val rt = get(SyncKey.Folders)
            val merged = SyncMerge.folders(base.folders, FolderCatalog.decode(lt), FolderCatalog.decode(rt.text))
            val text = FolderCatalog.encode(merged)
            val mh = SyncMerge.folderHashes(merged)
            var ok = mh == local.folders || store.replace(SyncKey.Folders, SyncHash.fingerprint(lt), text)
            if (ok && mh != remote.folders) ok = put(SyncKey.Folders, text, rt.fingerprint)
            if (ok) folders = mh
        }

        // ---- orden manual
        var order = base.order
        if (local.order == remote.order) order = local.order else {
            val lt = store.read(SyncKey.Order)
            val rt = get(SyncKey.Order)
            val merged = SyncMerge.order(base.order, NotebookOrder.decode(lt), NotebookOrder.decode(rt.text))
            val text = NotebookOrder.encode(merged)
            val mh = SyncMerge.orderHash(NotebookOrder.decode(text))
            var ok = mh == local.order || store.replace(SyncKey.Order, SyncHash.fingerprint(lt), text)
            if (ok && mh != remote.order) ok = put(SyncKey.Order, text, rt.fingerprint)
            if (ok) order = mh
        }

        val next = SyncBase(notebooks, patterns, folders, order)
        ch.send(SyncMessage.Commit(next))
        ch.receiveMessage() as? SyncMessage.Ok ?: throw SyncAuthException("Respuesta inesperada")
        store.saveBase(peer, next)
        ch.send(SyncMessage.Bye)
        return SyncReport(received, sent, conflicts, deferred)
    }

    private fun decodeNotebook(text: String, id: String): LazyNotebook? =
        runCatching { TrazoFormat.decodeLazy(text) }.getOrNull()?.takeIf { it.id == id }

    // ------------------------------------------------------------------ el que atiende

    /** Atiende pedidos hasta que el otro se despide o corta. */
    fun serve(ch: SecureChannel) {
        val peer = ch.peerId
        while (true) {
            val m = try { ch.receiveMessage() } catch (_: EOFException) { return }
            when (m) {
                SyncMessage.GetManifest -> ch.send(SyncMessage.Manifest(manifest()))
                is SyncMessage.Get -> {
                    val key = SyncKey.parse(m.key) ?: throw SyncAuthException("Clave inválida")
                    val text = store.read(key)
                    ch.send(SyncMessage.Text(m.key, text, SyncHash.fingerprint(text)))
                }
                is SyncMessage.Put -> {
                    val key = SyncKey.parse(m.key)
                    val ok = key != null && valid(key, m.text) && store.replace(key, m.expect, m.text)
                    ch.send(SyncMessage.PutResult(m.key, ok))
                }
                is SyncMessage.GetBlob -> {
                    val bytes = if (SyncProtocol.isValidBlob(m.name)) store.readBlob(m.name) else null
                    ch.send(SyncMessage.Blob(m.name, bytes?.let { Base64.getEncoder().encodeToString(it) }))
                }
                is SyncMessage.PutBlob -> {
                    val bytes = runCatching { Base64.getDecoder().decode(m.data) }.getOrNull()
                    if (bytes != null && blobMatches(m.name, bytes) && m.name !in store.blobNames()) store.writeBlob(m.name, bytes)
                    ch.send(SyncMessage.Ok())
                }
                is SyncMessage.Commit -> {
                    store.saveBase(peer, m.base)
                    ch.send(SyncMessage.Ok())
                }
                SyncMessage.Bye -> return
                else -> throw SyncAuthException("Mensaje inesperado")
            }
        }
    }

    /** Lo que llega se valida antes de escribirlo: un archivo roto no reemplaza uno bueno. */
    private fun valid(key: SyncKey, text: String?): Boolean {
        if (text == null) return key is SyncKey.NotebookKey
        return when (key) {
            is SyncKey.NotebookKey -> decodeNotebook(text, key.id) != null
            SyncKey.Patterns -> runCatching { TrazoFormat.decodePatterns(text) }.isSuccess
            SyncKey.Folders -> runCatching { FolderCatalog.decode(text) }.isSuccess
            SyncKey.Order -> runCatching { NotebookOrder.decode(text) }.isSuccess
        }
    }

    companion object {
        /** El nombre es el SHA-256 del contenido y la extensión coincide con el formato real. */
        fun blobMatches(name: String, bytes: ByteArray): Boolean {
            if (!SyncProtocol.isValidBlob(name)) return false
            val file = name.substringAfter('/')
            val ext = when {
                name.startsWith("images/") -> PageImages.extensionOf(bytes)
                else -> Covers.extensionOf(bytes)
            } ?: return false
            return file == SyncHash.of(bytes) + "." + ext
        }
    }
}
