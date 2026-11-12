package io.trazo.app.data

import io.trazo.ai.TutorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiChatStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun saveAndLoad() {
        val store = AiChatStore(temp.root)
        val msgs = listOf(
            AiChatMessage(TutorRole.STUDENT, "hola", 1000L, image = "foto.jpg"),
            AiChatMessage(TutorRole.TUTOR, "chau", 2000L, line = 2, correct = true, verdict = "bien"),
        )
        store.save("nb1", "p1", msgs)
        val loaded = store.load("nb1", "p1")
        assertEquals(2, loaded.size)
        assertEquals(TutorRole.STUDENT, loaded[0].role)
        assertEquals("hola", loaded[0].text)
        assertEquals("foto.jpg", loaded[0].image)
        assertEquals(TutorRole.TUTOR, loaded[1].role)
        assertEquals(2, loaded[1].line)
        assertEquals(true, loaded[1].correct)
        assertEquals("bien", loaded[1].verdict)
    }

    @Test fun archiveAndListArchives() {
        val store = AiChatStore(temp.root)
        // Vacío no archiva nada
        store.archive("nb1", "p1", emptyList())
        assertTrue(store.archives("nb1", "p1").isEmpty())

        val chat1 = listOf(
            AiChatMessage(TutorRole.STUDENT, "¿cómo sigo?", 1000L),
            AiChatMessage(TutorRole.TUTOR, "Tu idea de descomponer en fracciones simples sí es adecuada...", 2000L),
        )
        val chat2 = listOf(
            AiChatMessage(TutorRole.STUDENT, "otra duda", 3000L),
            AiChatMessage(TutorRole.TUTOR, "El método de partes conviene acá.", 4000L),
        )
        store.archive("nb1", "p1", chat1)
        store.archive("nb1", "p1", chat2)

        val archives = store.archives("nb1", "p1")
        assertEquals(2, archives.size)
        // La más nueva primero
        assertEquals("3000", archives[0].id)
        assertEquals(3000L, archives[0].at)
        assertEquals(2, archives[0].count)
        assertEquals("El método de partes conviene acá.", archives[0].preview)

        assertEquals("1000", archives[1].id)
        assertEquals(1000L, archives[1].at)
        assertEquals(2, archives[1].count)
        assertEquals("Tu idea de descomponer en fracciones simples sí es adecuada...", archives[1].preview)
    }

    @Test fun maxArchivesCappedAt30() {
        val store = AiChatStore(temp.root)
        for (i in 1..35) {
            val msgs = listOf(AiChatMessage(TutorRole.STUDENT, "pregunta $i", 1000L + i * 1000L))
            store.archive("nb1", "p1", msgs)
        }
        val archives = store.archives("nb1", "p1")
        assertEquals(30, archives.size)
        // Las más nuevas se conservan
        assertEquals("36000", archives.first().id)
        assertEquals("7000", archives.last().id)
    }

    @Test fun loadArchiveAndDeleteArchive() {
        val store = AiChatStore(temp.root)
        val msgs = listOf(AiChatMessage(TutorRole.STUDENT, "pregunta", 5000L))
        store.archive("nb1", "p1", msgs)

        val loaded = store.loadArchive("nb1", "p1", "5000")
        assertNotNull(loaded)
        assertEquals(1, loaded!!.size)
        assertEquals("pregunta", loaded[0].text)

        assertNull(store.loadArchive("nb1", "p1", "9999"))
        assertNull(store.loadArchive("nb1", "p1", "bad_id"))

        assertTrue(store.deleteArchive("nb1", "p1", "5000"))
        assertFalse(store.deleteArchive("nb1", "p1", "5000"))
        assertNull(store.loadArchive("nb1", "p1", "5000"))
    }

    @Test fun restore() {
        val store = AiChatStore(temp.root)
        val chat1 = listOf(AiChatMessage(TutorRole.STUDENT, "charla actual", 1000L))
        val chat2 = listOf(AiChatMessage(TutorRole.STUDENT, "charla vieja", 2000L))
        store.save("nb1", "p1", chat1)
        store.archive("nb1", "p1", chat2)

        val restored = store.restore("nb1", "p1", "2000")
        assertNotNull(restored)
        assertEquals(1, restored!!.size)
        assertEquals("charla vieja", restored[0].text)

        // La actual ahora es chat2
        assertEquals("charla vieja", store.load("nb1", "p1")[0].text)

        // En historial ahora está chat1 y no chat2
        val archives = store.archives("nb1", "p1")
        assertEquals(1, archives.size)
        assertEquals("1000", archives[0].id)
    }

    @Test fun deleteRemovesHistory() {
        val store = AiChatStore(temp.root)
        store.save("nb1", "p1", listOf(AiChatMessage(TutorRole.STUDENT, "c", 1000L)))
        store.archive("nb1", "p1", listOf(AiChatMessage(TutorRole.STUDENT, "a", 2000L)))
        assertEquals(1, store.archives("nb1", "p1").size)

        store.delete("nb1", "p1")
        assertTrue(store.load("nb1", "p1").isEmpty())
        assertTrue(store.archives("nb1", "p1").isEmpty())
    }

    @Test fun hasImage() {
        val store = AiChatStore(temp.root)
        val chatCurrent = listOf(AiChatMessage(TutorRole.STUDENT, "m", 1000L, image = "img_actual.png"))
        val chatOld = listOf(AiChatMessage(TutorRole.STUDENT, "m", 2000L, image = "img_vieja.png"))
        store.save("nb1", "p1", chatCurrent)
        store.archive("nb1", "p1", chatOld)

        assertTrue(store.hasImage("nb1", "p1", "img_actual.png"))
        assertTrue(store.hasImage("nb1", "p1", "img_vieja.png"))
        assertFalse(store.hasImage("nb1", "p1", "img_otra.png"))
        assertFalse(store.hasImage("nb1", "p1", ""))
    }
}
