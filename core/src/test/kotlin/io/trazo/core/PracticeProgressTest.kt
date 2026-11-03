package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PracticeProgressTest {
    private val sign = PracticeSkill.SIGN_AND_ARITHMETIC
    private val equation = PracticeSkill.ONE_STEP_EQUATION

    @Test fun `one source attempt per skill regardless of review repetition`() {
        val p = PracticeProgressEngine.observeError(PracticeProgress(), "page1:attempt-7", sign)
        val duplicate = PracticeProgressEngine.observeError(p, "page1:attempt-7", sign)
        assertEquals(p, duplicate)
        val next = PracticeProgressEngine.observeError(duplicate, "page1:attempt-7", equation)
        assertEquals(1, PracticeProgressEngine.sourceFrequencies(next)[sign])
        assertEquals(1, PracticeProgressEngine.sourceFrequencies(next)[equation])
    }

    @Test fun `replaying same submission is idempotent and correctness updates due`() {
        val event = PracticeSubmission("session1/ex1/try1", sign, false, 1_000L)
        val p = PracticeProgressEngine.submit(PracticeProgress(), event)
        assertEquals(p, PracticeProgressEngine.submit(p, event))
        assertEquals(301_000L, p.skills.getValue(sign).dueAtMillis)
        val q = PracticeProgressEngine.submit(p, PracticeSubmission("session1/ex1/try2", sign, true, 2_000L))
        assertEquals(2, q.skills.getValue(sign).attempts)
        assertEquals(1, q.skills.getValue(sign).correct)
        assertEquals(602_000L, q.skills.getValue(sign).dueAtMillis)
    }

    @Test fun `streak advances spaced boxes and wrong answer resets`() {
        var p = PracticeProgress()
        repeat(8) { i -> p = PracticeProgressEngine.submit(p, PracticeSubmission("id-$i", sign, true, (i+1).toLong()*1000)) }
        assertEquals(5, p.skills.getValue(sign).box)
        assertEquals(8, p.skills.getValue(sign).streak)
        p = PracticeProgressEngine.submit(p, PracticeSubmission("wrong", sign, false, 9000L))
        assertEquals(0, p.skills.getValue(sign).box)
        assertEquals(0, p.skills.getValue(sign).streak)
        assertEquals(309000L, p.skills.getValue(sign).dueAtMillis)
    }

    @Test fun `clock rollback does not move schedule backward and saturation is safe`() {
        val a = PracticeProgressEngine.submit(PracticeProgress(), PracticeSubmission("1", sign, true, 10_000L))
        val b = PracticeProgressEngine.submit(a, PracticeSubmission("2", sign, true, 1L))
        assertEquals(10_000L, b.skills.getValue(sign).lastAtMillis)
        val huge = PracticeProgressEngine.submit(PracticeProgress(), PracticeSubmission("huge", sign, true, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, huge.skills.getValue(sign).dueAtMillis)
    }

    @Test fun `selection honors due and frequencies and has no-history case`() {
        assertNull(PracticeProgressEngine.selectSkill(emptyMap(), PracticeProgress(), 1L, 0L))
        val freq = mapOf(sign to 4, equation to 1)
        val after = PracticeProgressEngine.submit(PracticeProgress(), PracticeSubmission("1", sign, true, 1L))
        assertEquals(equation, PracticeProgressEngine.selectSkill(freq, after, 1L, 0L))
        assertEquals(sign, PracticeProgressEngine.selectSkill(mapOf(sign to 1), after, 1L, 0L))
        assertEquals(sign, PracticeProgressEngine.selectSkill(freq, after, 1_000_000L, 0L))
    }

    @Test fun `codec roundtrip and idempotency survive restart`() {
        var p = PracticeProgressEngine.observeError(PracticeProgress(), "hoja:α-1", sign)
        p = PracticeProgressEngine.submit(p, PracticeSubmission("intento:ñ", sign, true, 100L))
        val encoded = PracticeProgressCodec.encode(p)
        val decoded = PracticeProgressCodec.decode(encoded)
        assertEquals(0, decoded.skippedRows)
        assertEquals(p, decoded.progress)
        assertEquals(p, PracticeProgressEngine.submit(decoded.progress, PracticeSubmission("intento:ñ", sign, true, 100L)))
        assertEquals(encoded, PracticeProgressCodec.encode(decoded.progress))
    }

    @Test fun `corrupt row is isolated and valid records survive`() {
        var p = PracticeProgressEngine.observeError(PracticeProgress(), "source", sign)
        p = PracticeProgressEngine.submit(p, PracticeSubmission("submission", sign, true, 10L))
        val encoded = PracticeProgressCodec.encode(p)
        val lines = encoded.lines().filter { it.isNotBlank() }.toMutableList()
        lines[1] = lines[1].replace("\t1\t", "\t9\t") // invalid checksum in S row
        val result = PracticeProgressCodec.decode(lines.joinToString("\n"))
        assertEquals(1, result.skippedRows)
        assertTrue(result.progress.skills.isEmpty())
        assertTrue("submission" in result.progress.submissionIds)
        assertEquals(1, result.progress.sourceKeys.size)
    }

    @Test fun `truncated file is flagged even when all surviving rows have valid checksums`() {
        val p = PracticeProgressEngine.submit(PracticeProgress(), PracticeSubmission("id", sign, true, 10L))
        val truncated = PracticeProgressCodec.encode(p).lines().filter { it.isNotBlank() && !it.startsWith("END\t") }.joinToString("\n")
        assertEquals(1, PracticeProgressCodec.decode(truncated).skippedRows)
    }

    @Test fun `v1 prototype migration and unknown future schema rejected`() {
        val legacy = "PRACTICE_V1\nS\tSIGN_AND_ARITHMETIC\t3\t2\t1\t1000\n"
        val migrated = PracticeProgressCodec.decode(legacy)
        assertTrue(migrated.migratedV1)
        assertEquals(3, migrated.progress.skills.getValue(sign).attempts)
        assertFalse(PracticeProgressCodec.decode(PracticeProgressCodec.encode(migrated.progress)).migratedV1)
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressCodec.decode("PRACTICE_V999\n") }
    }

    @Test fun `history remains writable after rolling idempotency window fills`() {
        var p = PracticeProgress()
        repeat(1030) { i ->
            p = PracticeProgressEngine.submit(p, PracticeSubmission("attempt-$i", sign, true, i.toLong()))
            p = PracticeProgressEngine.observeError(p, "source-$i", sign)
        }
        assertEquals(1030, p.skills.getValue(sign).attempts)
        assertEquals(1024, p.submissionIds.size)
        assertEquals(1024, p.sourceKeys.size)
        assertEquals(p, PracticeProgressCodec.decode(PracticeProgressCodec.encode(p)).progress)
    }

    @Test fun `bounded size and invalid keys rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PracticeSubmission("", sign, true, 0) }
        assertThrows(IllegalArgumentException::class.java) { PracticeSourceKey("", sign) }
        assertThrows(IllegalArgumentException::class.java) { PracticeProgressCodec.decode("x".repeat(600_000)) }
    }
}
