package io.trazo.app.sync

import android.util.AtomicFile
import io.trazo.app.data.TrazoRepository
import io.trazo.core.sync.NotebookPrint
import io.trazo.core.sync.SyncBase
import io.trazo.core.sync.SyncConfig
import io.trazo.core.sync.SyncKey
import io.trazo.core.sync.SyncPeer
import io.trazo.core.sync.SyncProtocol
import io.trazo.core.sync.SyncStore
import java.io.File
import kotlinx.serialization.builtins.ListSerializer

/**
 * Lo que la sincronización guarda: este dispositivo, los vinculados (con sus claves) y la base común con
 * cada uno. Vive en `sync/`, que queda fuera de las copias de seguridad: las claves no salen del equipo.
 */
class SyncPeerStore(root: File) {
    private val dir = File(root, "sync").apply { mkdirs() }
    private val configFile = AtomicFile(File(dir, "config.json"))
    private val peersFile = AtomicFile(File(dir, "peers.json"))
    private val lock = Any()

    fun config(): SyncConfig = synchronized(lock) {
        read(configFile)?.let { runCatching { SyncProtocol.json.decodeFromString(SyncConfig.serializer(), it) }.getOrNull() }
            ?: SyncConfig().also { saveConfig(it) }
    }

    fun saveConfig(c: SyncConfig) = synchronized(lock) { write(configFile, SyncProtocol.json.encodeToString(SyncConfig.serializer(), c)) }

    fun peers(): List<SyncPeer> = synchronized(lock) {
        read(peersFile)?.let { runCatching { SyncProtocol.json.decodeFromString(ListSerializer(SyncPeer.serializer()), it) }.getOrNull() }.orEmpty()
    }

    fun savePeer(p: SyncPeer) = synchronized(lock) {
        val list = peers().filterNot { it.deviceId == p.deviceId } + p
        write(peersFile, SyncProtocol.json.encodeToString(ListSerializer(SyncPeer.serializer()), list))
    }

    fun update(id: String, f: (SyncPeer) -> SyncPeer) = synchronized(lock) {
        peers().firstOrNull { it.deviceId == id }?.let { savePeer(f(it)) }
    }

    fun forget(id: String) = synchronized(lock) {
        val list = peers().filterNot { it.deviceId == id }
        write(peersFile, SyncProtocol.json.encodeToString(ListSerializer(SyncPeer.serializer()), list))
        baseFile(id).delete()
    }

    private fun baseFile(peer: String) = AtomicFile(File(dir, "base-" + peer.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json"))

    fun loadBase(peer: String): SyncBase = synchronized(lock) { SyncProtocol.decodeBase(read(baseFile(peer))) }

    fun saveBase(peer: String, base: SyncBase) = synchronized(lock) { write(baseFile(peer), SyncProtocol.encodeBase(base)) }

    private fun read(f: AtomicFile): String? =
        if (!f.baseFile.exists()) null else runCatching { f.readFully().toString(Charsets.UTF_8) }.getOrNull()

    private fun write(f: AtomicFile, text: String) {
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }
}

/** El repositorio de cuadernos visto por el motor de sincronización. */
class SyncRepositoryStore(
    private val repo: TrazoRepository,
    private val peers: SyncPeerStore,
    override val deviceId: String,
) : SyncStore {
    override fun notebookPrints(): Map<String, NotebookPrint> = repo.syncPrints()
    override fun busy(): Set<String> = repo.openNotebookIds()
    override fun read(key: SyncKey): String? = repo.syncRead(key)
    override fun replace(key: SyncKey, expect: String, text: String?): Boolean = repo.syncReplace(key, expect, text)
    override fun blobNames(): Set<String> = repo.syncBlobNames()
    override fun readBlob(name: String): ByteArray? = repo.syncReadBlob(name)
    override fun writeBlob(name: String, bytes: ByteArray) = repo.syncWriteBlob(name, bytes)
    override fun loadBase(peerId: String): SyncBase = peers.loadBase(peerId)
    override fun saveBase(peerId: String, base: SyncBase) = peers.saveBase(peerId, base)
}
