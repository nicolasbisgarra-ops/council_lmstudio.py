package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NestedFoldersTest {

    @Test fun `legacy json without parentId decodes as root and nested catalog round trips`() {
        val legacy = """{"format":"trazo-folders","schemaVersion":1,"folders":[{"id":"a","name":"Álgebra","colorArgb":4284513675,"orderKey":0}]}"""
        val decoded = FolderCatalog.decode(legacy)
        assertEquals(1, decoded.folders.size)
        assertNull(decoded["a"]!!.parentId)

        val ids = IdGenerator.sequential("f")
        val (c1, root) = FolderCatalog().add("Matemática", ids = ids)
        val (c2, child) = c1.add("Cálculo", ids = ids, parentId = root.id)
        assertEquals(root.id, child.parentId)
        assertEquals(c2, FolderCatalog.decode(FolderCatalog.encode(c2)))
    }

    @Test fun `children path descendants and treeOrder`() {
        val c = FolderCatalog(
            listOf(
                Folder("r2", "Raíz 2", orderKey = 2),
                Folder("r1", "Raíz 1", orderKey = 1),
                Folder("c2", "Hijo 2", orderKey = 4, parentId = "r1"),
                Folder("c1", "Hijo 1", orderKey = 3, parentId = "r1"),
                Folder("gc1", "Nieto", orderKey = 5, parentId = "c1"),
            ),
        )
        assertEquals(listOf("r1", "r2"), c.children(null).map { it.id })
        assertEquals(listOf("c1", "c2"), c.children("r1").map { it.id })
        assertEquals(emptyList<Folder>(), c.children("r2"))

        assertEquals(listOf("r1", "c1", "gc1"), c.path("gc1").map { it.id })
        assertEquals(listOf("r1"), c.path("r1").map { it.id })
        assertEquals(emptyList<Folder>(), c.path("inexistente"))

        assertEquals(setOf("r1", "c1", "gc1", "c2"), c.descendants("r1"))
        assertEquals(setOf("c1", "gc1"), c.descendants("c1"))
        assertEquals(setOf("r2"), c.descendants("r2"))
        assertEquals(emptySet<String>(), c.descendants("inexistente"))

        assertEquals(listOf("r1", "c1", "gc1", "c2", "r2"), c.treeOrder().map { it.id })
    }

    @Test fun `add falls back to null when parentId does not exist and enforces MAX_DEPTH`() {
        val ids = IdGenerator.sequential("f")
        val (c1, orphan) = FolderCatalog().add("Suelta", ids = ids, parentId = "no-existe")
        assertNull(orphan.parentId)

        var catalog = FolderCatalog()
        var parentId: String? = null
        repeat(FolderCatalog.MAX_DEPTH) { level ->
            val (next, f) = catalog.add("Nivel ${level + 1}", ids = ids, parentId = parentId)
            catalog = next
            parentId = f.id
        }
        assertEquals(FolderCatalog.MAX_DEPTH, catalog.path(parentId).size)
        assertThrows(IllegalArgumentException::class.java) {
            catalog.add("Exceso", ids = ids, parentId = parentId)
        }
    }

    @Test fun `moved updates parent rejects cycles missing folders and exceeding MAX_DEPTH`() {
        val ids = IdGenerator.sequential("f")
        val (c1, a) = FolderCatalog().add("A", ids = ids)
        val (c2, b) = c1.add("B", ids = ids, parentId = a.id)
        val (c3, c) = c2.add("C", ids = ids, parentId = b.id)
        val (c4, d) = c3.add("D", ids = ids)

        // Rechaza ciclos y padres/carpetas inexistentes.
        assertThrows(IllegalArgumentException::class.java) { c4.moved(a.id, a.id) }
        assertThrows(IllegalArgumentException::class.java) { c4.moved(a.id, b.id) }
        assertThrows(IllegalArgumentException::class.java) { c4.moved(a.id, c.id) }
        assertThrows(IllegalArgumentException::class.java) { c4.moved(a.id, "no-existe") }
        assertThrows(IllegalArgumentException::class.java) { c4.moved("no-existe", a.id) }

        // Mover subárbol B -> C debajo de D y luego a la raíz.
        val movedToD = c4.moved(b.id, d.id)
        assertEquals(listOf(d.id, b.id, c.id), movedToD.path(c.id).map { it.id })
        val movedToRoot = movedToD.moved(b.id, null)
        assertNull(movedToRoot[b.id]!!.parentId)
        assertEquals(listOf(b.id, c.id), movedToRoot.path(c.id).map { it.id })

        // Armar cadena de 4 niveles bajo A y verificar que B (altura 2) no cabe en el nivel 4 pero sí en el 3.
        val (c5, a2) = movedToRoot.add("A2", ids = ids, parentId = a.id)
        val (c6, a3) = c5.add("A3", ids = ids, parentId = a2.id)
        val (c7, a4) = c6.add("A4", ids = ids, parentId = a3.id)
        assertThrows(IllegalArgumentException::class.java) { c7.moved(b.id, a4.id) }
        val atMax = c7.moved(b.id, a3.id)
        assertEquals(FolderCatalog.MAX_DEPTH, atMax.path(c.id).size)
    }

    @Test fun `removed rejects folder with subfolders until emptied`() {
        val c = FolderCatalog(
            listOf(
                Folder("p", "Padre"),
                Folder("s1", "Sub 1", parentId = "p"),
                Folder("s2", "Sub 2", parentId = "p"),
            ),
        )
        val e1 = assertThrows(FolderNotEmptyException::class.java) { c.removed("p", emptyList()) }
        assertEquals(2, e1.count)

        val e2 = assertThrows(FolderNotEmptyException::class.java) { c.removed("p", listOf("p")) }
        assertEquals(3, e2.count)

        val afterSubs = c.removed("s1", emptyList()).removed("s2", emptyList())
        val afterParent = afterSubs.removed("p", emptyList())
        assertEquals(emptyList<Folder>(), afterParent.folders)
    }

    @Test fun `decode clears nonexistent parentId and cycles`() {
        val text = """
            {"format":"trazo-folders","schemaVersion":1,"folders":[
                {"id":"ok","name":"Raíz"},
                {"id":"child","name":"Hija","parentId":"ok"},
                {"id":"ghost","name":"Huérfana","parentId":"missing"},
                {"id":"self","name":"Auto","parentId":"self"},
                {"id":"c1","name":"Ciclo 1","parentId":"c2"},
                {"id":"c2","name":"Ciclo 2","parentId":"c1"}
            ]}
        """.trimIndent()
        val c = FolderCatalog.decode(text)
        assertNull(c["ok"]!!.parentId)
        assertEquals("ok", c["child"]!!.parentId)
        assertNull(c["ghost"]!!.parentId)
        assertNull(c["self"]!!.parentId)
        assertNull(c["c1"]!!.parentId)
        assertNull(c["c2"]!!.parentId)
    }
}
