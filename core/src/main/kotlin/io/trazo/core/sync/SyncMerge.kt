package io.trazo.core.sync

import io.trazo.core.Folder
import io.trazo.core.FolderCatalog
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.LazyNotebook
import io.trazo.core.Page
import io.trazo.core.PageSlot
import io.trazo.core.copiedWithNewIds

/**
 * Fusión de tres vías entre lo de este dispositivo, lo del otro y la base común ([SyncBase]).
 * Nunca se pierde trabajo: si las dos copias de una hoja cambiaron, quedan las dos (la del otro
 * dispositivo como una hoja nueva justo después), y borrar algo que el otro editó lo conserva.
 */
object SyncMerge {

    fun print(n: LazyNotebook): NotebookPrint = NotebookPrint(
        n.id, n.name, n.paper, n.coverKind, n.coverImage, n.folderId,
        n.slots.map { PagePrint(it.id, SyncHash.short(it.json())) },
    )

    /** Resultado de fusionar un cuaderno; [conflicts] cuenta las hojas que quedaron duplicadas. */
    data class NotebookResult(val notebook: LazyNotebook?, val conflicts: Int = 0)

    fun notebook(
        base: NotebookPrint?,
        local: LazyNotebook?,
        remote: LazyNotebook?,
        ids: IdGenerator = IdGenerator.RANDOM,
    ): NotebookResult {
        if (local == null && remote == null) return NotebookResult(null)
        if (local == null) {
            // Borrado aquí: se respeta salvo que el otro lo haya cambiado desde la base.
            return NotebookResult(if (base != null && print(remote!!) == base) null else remote)
        }
        if (remote == null) {
            return NotebookResult(if (base != null && print(local) == base) null else local)
        }
        val lp = print(local)
        val rp = print(remote)
        if (lp == rp) return NotebookResult(local)

        val basePages = base?.pages.orEmpty().associate { it.id to it.hash }
        val lHash = lp.pages.associate { it.id to it.hash }
        val rHash = rp.pages.associate { it.id to it.hash }
        val lSlots = local.slots.associateBy { it.id }
        val rSlots = remote.slots.associateBy { it.id }
        var conflicts = 0

        // Qué queda de cada hoja: la elegida y, en conflicto, una copia de la del otro dispositivo.
        val decided = HashMap<String, List<PageSlot>>()
        for (id in (lHash.keys + rHash.keys)) {
            val hl = lHash[id]
            val hr = rHash[id]
            val hb = basePages[id]
            decided[id] = when {
                hl != null && hr != null -> when {
                    hl == hr || hr == hb -> listOf(lSlots.getValue(id))
                    hl == hb -> listOf(rSlots.getValue(id))
                    else -> {
                        val l = lSlots.getValue(id).page()
                        val r = rSlots.getValue(id).page()
                        if (l == r) listOf(lSlots.getValue(id)) else {
                            conflicts++
                            listOf(lSlots.getValue(id), PageSlot.of(conflictCopy(r, ids)))
                        }
                    }
                }
                hl != null -> if (hb != null && hb == hl) emptyList() else listOf(lSlots.getValue(id))
                else -> if (hb != null && hb == hr) emptyList() else listOf(rSlots.getValue(id))
            }
        }

        // Orden: el del lado que reordenó (o agregó/quitó); si los dos, el de aquí.
        val baseOrder = base?.pages.orEmpty().map { it.id }
        val lOrder = lp.pages.map { it.id }
        val rOrder = rp.pages.map { it.id }
        val (primary, secondary) = if (lOrder == baseOrder && base != null) rOrder to lOrder else lOrder to rOrder
        val order = ArrayList<String>(primary)
        val inOrder = primary.toHashSet()
        for ((i, id) in secondary.withIndex()) {
            if (id in inOrder) continue
            // Después de la hoja que la precede en el otro orden, o al principio.
            val prev = (i - 1 downTo 0).map { secondary[it] }.firstOrNull { it in inOrder }
            order.add(if (prev == null) 0 else order.indexOf(prev) + 1, id)
            inOrder += id
        }
        var slots = order.flatMap { decided[it].orEmpty() }
        if (slots.isEmpty()) slots = listOf(PageSlot.of(Page(ids.next())))

        fun <T> field(b: T?, l: T, r: T): T = if (base != null && l == b) r else l
        val cover = field(base?.let { it.coverKind to it.coverImage }, local.coverKind to local.coverImage, remote.coverKind to remote.coverImage)
        val merged = local.copy(
            name = field(base?.name, local.name, remote.name),
            paper = field(base?.paper, local.paper, remote.paper),
            coverKind = cover.first,
            coverImage = cover.second,
            folderId = field(base?.folderId, local.folderId, remote.folderId),
            slots = slots,
            createdAt = minOf(local.createdAt, remote.createdAt).takeIf { it > 0 } ?: maxOf(local.createdAt, remote.createdAt),
            updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        )
        return NotebookResult(merged, conflicts)
    }

    /** La hoja del otro dispositivo como hoja nueva: ids nuevos y sin la revisión de la IA. */
    private fun conflictCopy(p: Page, ids: IdGenerator) = Page(
        ids.next(), p.strokes.copiedWithNewIds(ids), p.parametricShapes.map { it.copy(id = ids.next()) },
        images = p.images.map { it.copy(id = ids.next()) },
    )

    fun patternHashes(list: List<InkPattern>): Map<String, String> =
        list.associate { it.id to SyncHash.json(InkPattern.serializer(), it) }

    /** Patrones por id; si los dos cambiaron el mismo, gana el editado más tarde. */
    fun patterns(base: Map<String, String>, local: List<InkPattern>, remote: List<InkPattern>): List<InkPattern> =
        byId(base, local, remote, { it.id }, { SyncHash.json(InkPattern.serializer(), it) }) { l, r ->
            if (r.updatedAt > l.updatedAt) r else l
        }

    fun folderHashes(c: FolderCatalog): Map<String, String> =
        c.folders.associate { it.id to SyncHash.json(Folder.serializer(), it) }

    fun folders(base: Map<String, String>, local: FolderCatalog, remote: FolderCatalog): FolderCatalog =
        FolderCatalog(byId(base, local.folders, remote.folders, { it.id }, { SyncHash.json(Folder.serializer(), it) }) { l, _ -> l })

    /** Orden manual: el del lado que lo cambió; si los dos, el de aquí con lo nuevo del otro al final. */
    fun order(base: String?, local: List<String>, remote: List<String>): List<String> {
        val lh = SyncHash.short(local.joinToString("\n"))
        val rh = SyncHash.short(remote.joinToString("\n"))
        return when {
            lh == rh || rh == base -> local
            lh == base -> remote
            else -> local + remote.filterNot { it in local.toSet() }
        }
    }

    fun orderHash(ids: List<String>): String = SyncHash.short(ids.joinToString("\n"))

    private fun <T> byId(
        base: Map<String, String>,
        local: List<T>,
        remote: List<T>,
        id: (T) -> String,
        hash: (T) -> String,
        both: (T, T) -> T,
    ): List<T> {
        val r = remote.associateBy(id)
        val lIds = local.map(id).toHashSet()
        val out = ArrayList<T>(local.size + remote.size)
        for (l in local) {
            val key = id(l)
            val other = r[key]
            val hb = base[key]
            if (other == null) {
                // El otro lo borró: se va si aquí no cambió desde la base.
                if (hb == null || hb != hash(l)) out += l
                continue
            }
            val hl = hash(l)
            val hr = hash(other)
            out += when {
                hl == hr || hr == hb -> l
                hl == hb -> other
                else -> both(l, other)
            }
        }
        for (o in remote) {
            val key = id(o)
            if (key in lIds) continue
            val hb = base[key]
            if (hb == null || hb != hash(o)) out += o
        }
        return out
    }
}
