package io.trazo.core.sync

import io.trazo.core.Folder
import io.trazo.core.FolderCatalog
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.LazyNotebook
import io.trazo.core.Notebook
import io.trazo.core.NotebookOrder
import io.trazo.core.Page
import io.trazo.core.TrazoFormat
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sincronización nativa: fusión de tres vías, canal cifrado y una vuelta completa entre dos dispositivos. */
class SyncTest {

    private fun stroke(id: String, x: Float) = InkStroke(id, listOf(InkPoint(x, 10f), InkPoint(x + 5f, 20f)))
    private fun page(id: String, vararg xs: Float) = Page(id, xs.mapIndexed { i, x -> stroke("$id-s$i", x) })
    private fun nb(id: String, vararg pages: Page, name: String = "Cuaderno") =
        LazyNotebook.of(Notebook(id, name, pages.toList(), createdAt = 1, updatedAt = 1))

    private fun pageIds(n: LazyNotebook?) = n!!.slots.map { it.id }

    // ------------------------------------------------------------------ fusión

    @Test fun `a page edited on one side only takes that version`() {
        val base = nb("n", page("a", 1f), page("b", 2f))
        val local = base.withPage(0, page("a", 1f, 50f))
        val r = SyncMerge.notebook(SyncMerge.print(base), local, base)
        assertEquals(0, r.conflicts)
        assertEquals(2, r.notebook!!.page(0).strokes.size)
        val r2 = SyncMerge.notebook(SyncMerge.print(base), base, local)
        assertEquals(2, r2.notebook!!.page(0).strokes.size)
        assertEquals(listOf("a", "b"), pageIds(r2.notebook))
    }

    @Test fun `the same page edited on both sides keeps both versions`() {
        val base = nb("n", page("a", 1f), page("b", 2f))
        val local = base.withPage(0, page("a", 1f, 50f))
        val remote = base.withPage(0, page("a", 1f, 90f))
        val r = SyncMerge.notebook(SyncMerge.print(base), local, remote, IdGenerator.sequential("c"))
        assertEquals(1, r.conflicts)
        val n = r.notebook!!
        assertEquals(3, n.pageCount)
        assertEquals("a", n.slots[0].id)
        assertEquals(50f, n.page(0).strokes[1].points[0].x)
        assertEquals(90f, n.page(1).strokes[1].points[0].x)
        assertNull(n.page(1).ai)
        assertTrue(n.page(1).strokes.none { it.id.startsWith("a-") })
        assertEquals("b", n.slots[2].id)
    }

    @Test fun `identical edits on both sides are not a conflict`() {
        val base = nb("n", page("a", 1f))
        val both = base.withPage(0, page("a", 1f, 50f))
        val r = SyncMerge.notebook(SyncMerge.print(base), both, both)
        assertEquals(0, r.conflicts)
        assertEquals(1, r.notebook!!.pageCount)
    }

    @Test fun `pages added on each side are both kept in place`() {
        val base = nb("n", page("a", 1f), page("b", 2f))
        val local = base.withPageAdded(page("x", 3f), 1)
        val remote = base.withPageAdded(page("y", 4f))
        val r = SyncMerge.notebook(SyncMerge.print(base), local, remote)
        assertEquals(listOf("a", "x", "b", "y"), pageIds(r.notebook))
    }

    @Test fun `a deleted page stays deleted unless the other side edited it`() {
        val base = nb("n", page("a", 1f), page("b", 2f))
        val local = base.withPageRemoved(1)
        assertEquals(listOf("a"), pageIds(SyncMerge.notebook(SyncMerge.print(base), local, base).notebook))
        val remoteEdited = base.withPage(1, page("b", 2f, 7f))
        assertEquals(listOf("a", "b"), pageIds(SyncMerge.notebook(SyncMerge.print(base), local, remoteEdited).notebook))
    }

    @Test fun `a reorder on one side is kept`() {
        val base = nb("n", page("a", 1f), page("b", 2f), page("c", 3f))
        val remote = base.withPageMoved(2, 0)
        assertEquals(listOf("c", "a", "b"), pageIds(SyncMerge.notebook(SyncMerge.print(base), base, remote).notebook))
    }

    @Test fun `notebook deletes and edits`() {
        val base = nb("n", page("a", 1f))
        val bp = SyncMerge.print(base)
        assertNull(SyncMerge.notebook(bp, null, base).notebook)
        assertNull(SyncMerge.notebook(bp, base, null).notebook)
        val edited = base.copy(name = "Otro")
        assertEquals("Otro", SyncMerge.notebook(bp, null, edited).notebook!!.name)
        assertEquals("Otro", SyncMerge.notebook(bp, edited, null).notebook!!.name)
        // Sin base (primera vez) nada se borra.
        assertNotNull(SyncMerge.notebook(null, null, base).notebook)
    }

    @Test fun `metadata merges field by field`() {
        val base = nb("n", page("a", 1f))
        val local = base.copy(name = "Álgebra")
        val remote = base.copy(folderId = "f1", lastPage = 0)
        val n = SyncMerge.notebook(SyncMerge.print(base), local, remote).notebook!!
        assertEquals("Álgebra", n.name)
        assertEquals("f1", n.folderId)
    }

    @Test fun `patterns merge by id and the newest edit wins`() {
        fun pat(id: String, name: String, t: Long) = InkPattern(id, name, listOf(listOf(stroke("$id-g", 1f))), listOf(stroke("$id-o", 2f)), updatedAt = t)
        val base = listOf(pat("p1", "uno", 1), pat("p2", "dos", 1))
        val bh = SyncMerge.patternHashes(base)
        val local = listOf(pat("p1", "UNO", 5), pat("p2", "dos", 1), pat("p3", "tres", 1))
        val remote = listOf(pat("p1", "Uno!", 9))
        val merged = SyncMerge.patterns(bh, local, remote).associateBy { it.id }
        assertEquals("Uno!", merged.getValue("p1").name)
        assertFalse("p2 se borró en el otro y aquí no cambió", "p2" in merged)
        assertTrue("p3" in merged)
    }

    @Test fun `folders merge by id`() {
        val base = FolderCatalog(listOf(Folder("f1", "Uno")))
        val bh = SyncMerge.folderHashes(base)
        val local = FolderCatalog(listOf(Folder("f1", "Uno"), Folder("f2", "Dos")))
        val remote = FolderCatalog(listOf(Folder("f1", "Uno renombrada"), Folder("f3", "Tres")))
        val merged = SyncMerge.folders(bh, local, remote).folders.associateBy { it.id }
        assertEquals(setOf("f1", "f2", "f3"), merged.keys)
        assertEquals("Uno renombrada", merged.getValue("f1").name)
    }

    // ------------------------------------------------------------------ canal

    private class Pipe {
        val aIn = PipedInputStream(1 shl 20)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 20)
        val aOut = PipedOutputStream(bIn)
    }

    private fun <T> async(block: () -> T): () -> T {
        val result = AtomicReference<Result<T>>()
        val t = Thread { result.set(runCatching(block)) }.apply { isDaemon = true; start() }
        return { t.join(20_000); result.get().getOrThrow() }
    }

    @Test fun `a shared key opens the channel both ways`() {
        val key = SecureChannel.newKey()
        val p = Pipe()
        val server = async {
            val ch = SecureChannel.server(p.bIn, p.bOut, "tablet") { mode, id -> if (mode == SecureChannel.Mode.SYNC && id == "phone") key else null }
            val got = ch.receive().toString(Charsets.UTF_8)
            ch.send("eco $got".toByteArray())
            ch.peerId
        }
        val ch = SecureChannel.client(p.aIn, p.aOut, "phone", SecureChannel.Mode.SYNC, key)
        ch.send("hola".toByteArray())
        assertEquals("eco hola", ch.receive().toString(Charsets.UTF_8))
        assertEquals("tablet", ch.peerId)
        assertEquals("phone", server())
    }

    @Test fun `a wrong key is rejected`() {
        val p = Pipe()
        val server = async {
            val ch = SecureChannel.server(p.bIn, p.bOut, "tablet") { _, _ -> SecureChannel.newKey() }
            ch.receive()
        }
        val ch = SecureChannel.client(p.aIn, p.aOut, "phone", SecureChannel.Mode.SYNC, SecureChannel.newKey())
        ch.send("hola".toByteArray())
        assertThrows(SyncAuthException::class.java) { server() }
    }

    @Test fun `an unknown device gets no answer`() {
        val p = Pipe()
        val server = async { SecureChannel.server(p.bIn, p.bOut, "tablet") { _, _ -> null } }
        runCatching { SecureChannel.client(p.aIn, p.aOut, "intruso", SecureChannel.Mode.SYNC, SecureChannel.newKey()) }
        assertThrows(SyncAuthException::class.java) { server() }
    }

    @Test fun `pairing codes`() {
        val code = PairingCode.generate()
        assertEquals(PairingCode.LENGTH, code.length)
        val pretty = PairingCode.pretty(code)
        assertEquals('-', pretty[5])
        assertEquals(code, PairingCode.normalize(" ${pretty.lowercase()} "))
        assertTrue(PairingCode.key(code).contentEquals(PairingCode.key(pretty)))
        assertNull(PairingCode.normalize("ABC"))
        assertNull(PairingCode.normalize("ABCDEFGHI0"))
    }

    // ------------------------------------------------------------------ una vuelta completa

    /** Un dispositivo en memoria con las mismas reglas que el repositorio de la app. */
    private class MemStore(override val deviceId: String) : SyncStore {
        val files = HashMap<String, String>()
        val blobs = HashMap<String, ByteArray>()
        val bases = HashMap<String, SyncBase>()
        val open = HashSet<String>()

        fun put(n: LazyNotebook) { files["nb:${n.id}"] = TrazoFormat.encodeLazy(n) }
        fun nb(id: String) = files["nb:$id"]?.let(TrazoFormat::decodeLazy)

        fun notebookIds() = files.keys.filter { it.startsWith("nb:") }.map { it.substring(3) }.toSet()
        override fun notebookPrints() = notebookIds().associateWith { SyncMerge.print(nb(it)!!) }
        override fun busy() = open.toSet()
        override fun read(key: SyncKey) = files[key.wire]
        override fun replace(key: SyncKey, expect: String, text: String?): Boolean {
            if (key is SyncKey.NotebookKey && key.id in open) return false
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

    private val key = SecureChannel.newKey()

    private fun sync(client: MemStore, server: MemStore): SyncReport {
        val p = Pipe()
        val s = async {
            val ch = SecureChannel.server(p.bIn, p.bOut, server.deviceId) { _, _ -> key }
            SyncEngine(server).serve(ch)
        }
        val ch = SecureChannel.client(p.aIn, p.aOut, client.deviceId, SecureChannel.Mode.SYNC, key)
        val report = SyncEngine(client).runClient(ch)
        s()
        return report
    }

    private fun png(seed: Int): Pair<String, ByteArray> {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { seed.toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "images/$sha.png" to bytes
    }

    @Test fun `first sync copies everything both ways`() {
        val phone = MemStore("phone")
        val tablet = MemStore("tablet")
        phone.put(nb("n1", page("a", 1f)))
        tablet.put(nb("n2", page("b", 2f)))
        val (name, bytes) = png(1)
        tablet.blobs[name] = bytes
        phone.files["folders"] = FolderCatalog.encode(FolderCatalog(listOf(Folder("f1", "Física"))))
        sync(phone, tablet)
        assertEquals(setOf("n1", "n2"), phone.notebookIds())
        assertEquals(setOf("n1", "n2"), tablet.notebookIds())
        assertTrue(phone.blobs.containsKey(name))
        assertEquals("Física", FolderCatalog.decode(tablet.files["folders"])["f1"]?.name)
        assertEquals(phone.bases["tablet"], tablet.bases["phone"])
    }

    @Test fun `edits, deletes and conflicts travel on later syncs`() {
        val phone = MemStore("phone")
        val tablet = MemStore("tablet")
        phone.put(nb("n1", page("a", 1f), page("b", 2f)))
        phone.put(nb("n2", page("c", 3f)))
        sync(phone, tablet)

        // En la tableta se edita la hoja b y se borra n2; en el teléfono se edita la hoja a.
        tablet.put(tablet.nb("n1")!!.withPage(1, page("b", 2f, 70f)))
        tablet.files.remove("nb:n2")
        phone.put(phone.nb("n1")!!.withPage(0, page("a", 1f, 30f)))
        sync(tablet, phone)
        for (s in listOf(phone, tablet)) {
            val n = s.nb("n1")!!
            assertEquals(listOf("a", "b"), n.slots.map { it.id })
            assertEquals(30f, n.page(0).strokes[1].points[0].x)
            assertEquals(70f, n.page(1).strokes[1].points[0].x)
            assertNull(s.nb("n2"))
        }

        // Las dos editan la misma hoja: quedan las dos versiones en los dos dispositivos.
        phone.put(phone.nb("n1")!!.withPage(0, page("a", 1f, 30f, 40f)))
        tablet.put(tablet.nb("n1")!!.withPage(0, page("a", 1f, 30f, 41f)))
        val r = sync(phone, tablet)
        assertEquals(1, r.conflicts)
        assertEquals(3, phone.nb("n1")!!.pageCount)
        assertEquals(SyncMerge.print(phone.nb("n1")!!), SyncMerge.print(tablet.nb("n1")!!))

        // Una vuelta más no cambia nada.
        val again = sync(tablet, phone)
        assertEquals(SyncReport(), again)
    }

    @Test fun `each device keeps its own last page`() {
        val phone = MemStore("phone")
        val tablet = MemStore("tablet")
        phone.put(nb("n1", page("a", 1f), page("b", 2f)))
        sync(phone, tablet)
        tablet.put(tablet.nb("n1")!!.copy(lastPage = 1))
        phone.put(phone.nb("n1")!!.withPage(0, page("a", 1f, 9f)))
        sync(phone, tablet)
        assertEquals(1, tablet.nb("n1")!!.lastPage)
        assertEquals(2, tablet.nb("n1")!!.page(0).strokes.size)
    }

    @Test fun `a notebook open in the editor waits for the next round`() {
        val phone = MemStore("phone")
        val tablet = MemStore("tablet")
        phone.put(nb("n1", page("a", 1f)))
        sync(phone, tablet)
        phone.put(phone.nb("n1")!!.withPage(0, page("a", 1f, 5f)))
        tablet.open += "n1"
        val r = sync(phone, tablet)
        assertEquals(1, r.deferred)
        assertEquals(1, tablet.nb("n1")!!.page(0).strokes.size)
        tablet.open.clear()
        sync(phone, tablet)
        assertEquals(2, tablet.nb("n1")!!.page(0).strokes.size)
    }

    @Test fun `patterns and the manual order sync`() {
        val phone = MemStore("phone")
        val tablet = MemStore("tablet")
        val p = InkPattern("p1", "alfa", listOf(listOf(stroke("g", 1f))), listOf(stroke("o", 2f)), updatedAt = 3)
        phone.files["patterns"] = TrazoFormat.encodePatterns(listOf(p))
        tablet.files["order"] = NotebookOrder.encode(listOf("n2", "n1"))
        sync(tablet, phone)
        assertEquals(listOf("p1"), TrazoFormat.decodePatterns(tablet.files["patterns"]!!).map { it.id })
        assertEquals(listOf("n2", "n1"), NotebookOrder.decode(phone.files["order"]))
    }

    @Test fun `blobs whose name does not match their content are dropped`() {
        val (name, bytes) = png(2)
        assertTrue(SyncEngine.blobMatches(name, bytes))
        assertFalse(SyncEngine.blobMatches(name, bytes + 1))
        assertFalse(SyncEngine.blobMatches("../" + name, bytes))
        assertFalse(SyncProtocol.isValidBlob("images/../../x.png"))
    }

    @Test fun `addresses outside the home network and Tailscale are refused`() {
        fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertTrue(SyncAddresses.isAllowed(ip(192, 168, 1, 20)))
        assertTrue(SyncAddresses.isAllowed(ip(10, 0, 0, 5)))
        assertTrue(SyncAddresses.isAllowed(ip(172, 20, 0, 1)))
        assertTrue(SyncAddresses.isAllowed(ip(100, 101, 7, 9)))
        assertTrue(SyncAddresses.isAllowed(ip(169, 254, 3, 3)))
        assertFalse(SyncAddresses.isAllowed(ip(8, 8, 8, 8)))
        assertFalse(SyncAddresses.isAllowed(ip(172, 32, 0, 1)))
        assertFalse(SyncAddresses.isAllowed(ip(100, 128, 0, 1)))
        assertFalse(SyncAddresses.isAllowed(ip(127, 0, 0, 1)))
        val tail6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[1] = 0x7a; it[2] = 0x11; it[3] = 0x5c; it[4] = 0xa1.toByte(); it[5] = 0xe0.toByte(); it[15] = 1 }
        assertTrue(SyncAddresses.isAllowed(tail6))
        val linkLocal6 = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        assertTrue(SyncAddresses.isAllowed(linkLocal6))
        val global6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }
        assertFalse(SyncAddresses.isAllowed(global6))
    }
}
