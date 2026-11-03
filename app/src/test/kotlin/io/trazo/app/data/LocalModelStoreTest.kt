package io.trazo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelStoreTest {
    @Test fun acceptsOnlySupportedModelContainers() {
        assertEquals("task", localModelExtension("gemma.task"))
        assertEquals("task", localModelExtension("GEMMA.TASK"))
        assertNull(localModelExtension("gemma.litertlm"))
        assertEquals("gguf", localModelExtension("model.gguf"))
        assertEquals("mmproj", localModelExtension("mmproj-model-f16.gguf.mmproj"))
        assertNull(localModelExtension("model.bin"))
        assertNull(localModelExtension("model.task.exe"))
    }

    @Test fun validatesKnownModelContainerHeadersWithoutClaimingCompatibility() {
        assertTrue(hasValidModelHeader("gguf", byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x00)))
        assertTrue(hasValidModelHeader("mmproj", byteArrayOf(0x47, 0x47, 0x55, 0x46)))
        assertTrue(hasValidModelHeader("task", byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertTrue(hasValidModelHeader("task", byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x54, 0x46, 0x4c, 0x33)))
        assertFalse(hasValidModelHeader("gguf", byteArrayOf(1)))
    }

    @Test fun importedNamesCannotEscapeThePrivateModelDirectory() {
        val stem = safeModelStem("../../private model:latest")
        assertTrue(stem.none { it == '/' || it == '\\' || it == ':' })
        assertTrue(stem.isNotBlank())
        assertEquals("local-model", safeModelStem("../..."))
    }

    @Test fun longNamesAreBounded() {
        assertEquals(64, safeModelStem("x".repeat(100)).length)
    }

    @Test fun copyBudgetChecksTheDeclaredSizeAndKeepsFreeSpaceReserve() {
        assertTrue(hasImportBudget(80L, availableBytes = 120L, maxBytes = 100L, reserveBytes = 32L))
        assertFalse(hasImportBudget(80L, availableBytes = 111L, maxBytes = 100L, reserveBytes = 32L))
        assertFalse(hasImportBudget(101L, availableBytes = 1000L, maxBytes = 100L, reserveBytes = 0L))
        assertTrue(hasImportBudget(null, availableBytes = 100L, maxBytes = 100L, reserveBytes = 32L))
    }

    @Test fun eachCopyChunkEnforcesByteAndSpaceLimits() {
        assertTrue(canContinueModelCopy(50L, 10, availableBytes = 50L, maxBytes = 100L, reserveBytes = 32L))
        assertFalse(canContinueModelCopy(95L, 10, availableBytes = 1000L, maxBytes = 100L, reserveBytes = 0L))
        assertFalse(canContinueModelCopy(50L, 10, availableBytes = 41L, maxBytes = 100L, reserveBytes = 32L))
        assertTrue(canContinueModelCopy(50L, 10, availableBytes = 0L, maxBytes = 100L, reserveBytes = 32L))
    }
}
