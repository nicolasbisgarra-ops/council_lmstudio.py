package io.trazo.core

import io.trazo.core.sync.SecureChannel
import io.trazo.core.sync.SyncBase
import io.trazo.core.sync.SyncEngine
import io.trazo.core.sync.SyncHash
import io.trazo.core.sync.SyncKey
import io.trazo.core.sync.SyncMerge
import io.trazo.core.sync.SyncMessage
import io.trazo.core.sync.SyncReport
import io.trazo.core.sync.SyncStore
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de regresión para la auditoría de sincronización (noviembre 2026):
 * - A1: Validación de id en SyncKey.parse y descarte en SyncEngine.serve.
 * - A6: No duplicar copias de conflicto si falla el envío remoto.
 * - M1: Detección y resolución de ciclos y profundidad excesiva en SyncMerge.folders.
 */
class AuditSyncTest {

    private fun stroke(id: String, x: Float) = InkStroke(id, listOf(InkPoint(x, 10f), InkPoint(x + 5f, 20f)))
    private fun page(id: String, vararg xs: Float) = Page(id, xs.mapIndexed { i, x -> stroke("$id-s$i", x) })
    private fun nb(id: String, vararg pages: Page, name: String = "Cuaderno") =
        LazyNotebook.of(Notebook(id, name, pages.toList(), createdAt = 1, updatedAt = 1))

    private class Pipe {
        val aIn = PipedInputStream(1 shl 20)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 20)
        val aOut = PipedOutputStream(bIn)
    }

    private fun <T> async(p: Pipe, block: () -> T): () -> T {
        val result = AtomicReference<Result<T>>()
        val t = Thread {
            result.set(runCatching(block))
            runCatching { p.bOut.close() }
        }.apply { isDaemon = true; start() }
        return {
            t.join(20_000)
            (result.get() ?: error("El servidor no terminó a tiempo")).getOrThrow()
        }
    }

    private class MemStore(override val deviceId: String) : SyncStore {
        val files = HashMap<String, String>()
        val blobs = HashMap<String, ByteArray>()
        val bases = HashMap<String, SyncBase>()
        val open = HashSet<String>()
        /** Rechaza la escritura sin figurar como ocupado (un fallo que aparece recién al escribir). */
        val reject = HashSet<String>()

        fun put(n: LazyNotebook) { files["nb:${n.id}"] = TrazoFormat.encodeLazy(n) }
        fun nb(id: String) = files["nb:$id"]?.let(TrazoFormat::decodeLazy)

        fun notebookIds() = files.keys.filter { it.startsWith("nb:") }.map { it.substring(3) }.toSet()
        override fun notebookPrints() = notebookIds().associateWith { SyncMerge.print(nb(it)!!) }
        override fun busy() = open.toSet()
        override fun read(key: SyncKey) = files[key.wire]
        override fun replace(key: SyncKey, expect: String, text: String?): Boolean {
            if (key is SyncKey.NotebookKey && (key.id in open || key.id in reject)) return false
            val current = files[key.wire]
            if (SyncHash.fingerprint(current) != expect) return false
            if (text == null) files.remove(key.wire) else {
                var t = text
                if (key is SyncKey.NotebookKey && current != null) {
                    val keep = TrazoFormat.decodeLazy(current).lastPage
                    t = TrazoFormat.encodeLazy(TrazoFormat.decodeLazy(text).let { it.copy(lastPage = keep) })
                }
                files[key.wire] = t
            }
            return true
        }
        override fun blobNames() = blobs.keys.toSet()
        override fun readBlob(name: String) = blobs[name]
        override fun writeBlob(name: String, bytes: ByteArray) { blobs[name] = bytes }
        override fun loadBase(peerId: String) = bases[peerId] ?: SyncBase()
        override fun saveBase(peerId: String, base: SyncBase) { bases[peerId] = base }
    }

    // ------------------------------------------------------------------ A1: SyncKey.parse y serve

    @Test fun `SyncKey parse accepts only valid notebook ids`() {
        assertEquals(SyncKey.NotebookKey("note-1"), SyncKey.parse("nb:note-1"))
        assertEquals(SyncKey.NotebookKey("note_2"), SyncKey.parse("nb:note_2"))
        assertEquals(SyncKey.NotebookKey("ABC"), SyncKey.parse("nb:ABC"))
        assertEquals(SyncKey.NotebookKey("123"), SyncKey.parse("nb:123"))

        assertNull(SyncKey.parse("nb:"))
        assertNull(SyncKey.parse("nb:a/b"))
        assertNull(SyncKey.parse("nb:a..b"))
        assertNull(SyncKey.parse("nb:a b"))
        assertNull(SyncKey.parse("nb:a@b"))
        assertNull(SyncKey.parse("nb:../secret"))
        assertNull(SyncKey.parse("nb:a\nb"))
        assertNull(SyncKey.parse("other"))

        assertEquals(SyncKey.Patterns, SyncKey.parse("patterns"))
        assertEquals(SyncKey.Folders, SyncKey.parse("folders"))
        assertEquals(SyncKey.Order, SyncKey.parse("order"))
    }

    @Test fun `SyncEngine serve ignores invalid keys in get and put`() {
        val store = MemStore("tablet")
        val key = SecureChannel.newKey()
        val p = Pipe()
        val server = async(p) {
            val ch = SecureChannel.server(p.bIn, p.bOut, "tablet") { _, _ -> key }
            SyncEngine(store).serve(ch)
        }
        val ch = SecureChannel.client(p.aIn, p.aOut, "phone", SecureChannel.Mode.SYNC, key)

        // Get con id inválido: responde con text null y huella "-" sin arrojar excepción
        ch.send(SyncMessage.Get("nb:a/b"))
        val textMsg = ch.receiveMessage() as SyncMessage.Text
        assertEquals("nb:a/b", textMsg.key)
        assertNull(textMsg.text)
        assertEquals("-", textMsg.fingerprint)

        // Put con id inválido: responde putResult ok = false sin escribir
        ch.send(SyncMessage.Put("nb:a/b", "{}", "-"))
        val putMsg = ch.receiveMessage() as SyncMessage.PutResult
        assertEquals("nb:a/b", putMsg.key)
        assertFalse(putMsg.ok)

        ch.send(SyncMessage.Bye)
        server()
    }

    // ------------------------------------------------------------------ A6: Registro de cambio local

    @Test fun `failed remote send registers local result in SyncBase without duplicating conflicts`() {
        val phone = MemStore("phone")
        val tablet = MemStore("tablet")
        val sharedKey = SecureChannel.newKey()

        fun syncNow(): SyncReport {
            val p = Pipe()
            val s = async(p) {
                val ch = SecureChannel.server(p.bIn, p.bOut, tablet.deviceId) { _, _ -> sharedKey }
                SyncEngine(tablet).serve(ch)
            }
            val ch = SecureChannel.client(p.aIn, p.aOut, phone.deviceId, SecureChannel.Mode.SYNC, sharedKey)
            val report = SyncEngine(phone).runClient(ch)
            s()
            return report
        }

        // Base común: n1 con 1 página
        phone.put(nb("n1", page("a", 1f, 10f)))
        tablet.put(nb("n1", page("a", 1f, 10f)))
        syncNow()

        // Los dos lados editan la misma página -> conflicto
        phone.put(phone.nb("n1")!!.withPage(0, page("a", 1f, 10f, 30f)))
        tablet.put(tablet.nb("n1")!!.withPage(0, page("a", 1f, 10f, 40f)))

        // La escritura en la tableta falla después de que el teléfono ya aplicó la mezcla
        tablet.reject += "n1"
        val r1 = syncNow()
        assertEquals(1, r1.conflicts)
        assertEquals(1, r1.deferred)
        // El teléfono aplicó el cambio localmente: 2 páginas (versión local + copia de conflicto)
        assertEquals(2, phone.nb("n1")!!.pageCount)

        // La tableta vuelve a aceptar escrituras
        tablet.reject.clear()

        // La siguiente sincronización no debe duplicar las copias de conflicto
        val r2 = syncNow()
        assertEquals(0, r2.conflicts)
        // Ambos dispositivos deben tener exactamente 2 páginas, no 3
        assertEquals(2, phone.nb("n1")!!.pageCount)
        assertEquals(2, tablet.nb("n1")!!.pageCount)
    }

    // ------------------------------------------------------------------ M1: Carpetas (ciclos y MAX_DEPTH)

    @Test fun `folders merge resolves cycles by relocating to root`() {
        val base = FolderCatalog(listOf(Folder("f1", "Uno"), Folder("f2", "Dos")))
        val bh = SyncMerge.folderHashes(base)
        // El teléfono mueve f1 dentro de f2
        val local = FolderCatalog(listOf(Folder("f1", "Uno", parentId = "f2"), Folder("f2", "Dos")))
        // La tableta mueve f2 dentro de f1
        val remote = FolderCatalog(listOf(Folder("f1", "Uno"), Folder("f2", "Dos", parentId = "f1")))

        val merged = SyncMerge.folders(bh, local, remote)
        val f1 = merged["f1"]!!
        val f2 = merged["f2"]!!

        // Uno queda en la raíz y el otro como hijo (o ambos en raíz), sin ciclos
        assertTrue(f1.parentId == null || f2.parentId == null)
        assertFalse(f1.parentId == "f2" && f2.parentId == "f1")

        assertEquals(if (f1.parentId == null) 1 else 2, merged.path("f1").size)
        assertEquals(if (f2.parentId == null) 1 else 2, merged.path("f2").size)
    }

    @Test fun `folders merge resolves self loop`() {
        val base = FolderCatalog(listOf(Folder("f1", "Uno")))
        val bh = SyncMerge.folderHashes(base)
        val local = FolderCatalog(listOf(Folder("f1", "Uno", parentId = "f1")))
        val remote = FolderCatalog(listOf(Folder("f1", "Uno")))

        val merged = SyncMerge.folders(bh, local, remote)
        assertNull(merged["f1"]!!.parentId)
        assertEquals(1, merged.path("f1").size)
    }

    @Test fun `folders merge respects max depth and relocates excessive depth to root`() {
        // Cadena de 7 carpetas: f1 -> f2 -> f3 -> f4 -> f5 -> f6 -> f7
        val folders = listOf(
            Folder("f1", "F1"),
            Folder("f2", "F2", parentId = "f1"),
            Folder("f3", "F3", parentId = "f2"),
            Folder("f4", "F4", parentId = "f3"),
            Folder("f5", "F5", parentId = "f4"),
            Folder("f6", "F6", parentId = "f5"),
            Folder("f7", "F7", parentId = "f6"),
        )
        val base = FolderCatalog(listOf(Folder("f1", "F1")))
        val bh = SyncMerge.folderHashes(base)
        val local = FolderCatalog(folders)
        val remote = FolderCatalog(listOf(Folder("f1", "F1")))

        val merged = SyncMerge.folders(bh, local, remote)
        for (f in merged.folders) {
            val depth = merged.path(f.id).size
            assertTrue("Carpeta ${f.id} con profundidad $depth supera MAX_DEPTH", depth <= FolderCatalog.MAX_DEPTH)
        }
        // f6 estaba a profundidad 6, debe quedar en la raíz
        assertNull(merged["f6"]!!.parentId)
        // f7 era hija de f6, por lo que queda a profundidad 2 debajo de f6
        assertEquals("f6", merged["f7"]!!.parentId)
        assertEquals(2, merged.path("f7").size)
    }
}
