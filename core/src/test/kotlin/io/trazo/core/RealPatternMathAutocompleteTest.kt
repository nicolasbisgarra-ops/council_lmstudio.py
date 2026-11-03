package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression against the user's exported pattern records (selected verbatim by id). */
class RealPatternMathAutocompleteTest {
    private val patterns = RealPatternMathAutocompleteTest::class.java.getResourceAsStream(
        "/io/trazo/core/real-patterns-equals-autocomplete.json"
    )!!.bufferedReader().use { TrazoFormat.decodePatterns(it.readText()) }
    private val byId = patterns.associateBy { it.id }
    private val ids = IdGenerator.sequential("real-pattern")

    private fun rowOf(vararg symbols: Pair<String, Boolean>): List<InkStroke> {
        var x = 0f
        return symbols.flatMap { (id, flat) ->
            val pattern = byId.getValue(id)
            val anchor = if (flat) Bounds(x, 15f, x + 30f, 25f) else Bounds(x, 0f, x + 25f, 40f)
            InkReplacement.place(pattern, anchor, ids).also { x = it.bounds().right + 12f }
        }
    }

    @Test fun `decoded user K and both real Igual patterns produce the same autocomplete`() {
        assertEquals(10, patterns.size)
        val userEquals = byId.getValue("10d32576-c220-412c-bf98-486b1af12cce")
        assertFalse(userEquals.enabled) // the active built-in sign has the same name
        assertEquals("K", byId.getValue("8d28a4ab-5531-4b35-b6c9-1efceb018edf").unknown)

        for (equalsId in listOf(userEquals.id, "preset-igual")) {
            val ink = rowOf(
                "8d28a4ab-5531-4b35-b6c9-1efceb018edf" to false, // K, unknown = K
                "preset-mas" to true,
                "8d28a4ab-5531-4b35-b6c9-1efceb018edf" to false,
                equalsId to true,
            )
            assertEquals("K+K=", MathNotes.reviewLines(ink, patterns).single().text)

            val result = MathNotes.results(ink, patterns)
            val resultPatternIds = result.mapNotNull { it.patternId }.toSet()
            assertTrue("the result uses the user's K ink", "8d28a4ab-5531-4b35-b6c9-1efceb018edf" in resultPatternIds)
            assertTrue("the result writes the coefficient 2", "preset-2" in resultPatternIds)
            assertTrue(result.all { it.color == MathNotes.DEFAULT_RESULT_COLOR })
            assertEquals(result, MathNotes.results(ink, patterns))
        }
    }

    @Test fun `lasso autocomplete inserts the real Igual sign and preserves the custom K`() {
        val ink = rowOf(
            "8d28a4ab-5531-4b35-b6c9-1efceb018edf" to false,
            "preset-mas" to true,
            "8d28a4ab-5531-4b35-b6c9-1efceb018edf" to false,
        )
        val completed = MathNotes.materialize(
            ink,
            ink.mapTo(mutableSetOf()) { it.id },
            patterns,
            ids = IdGenerator.sequential("materialized-real-pattern"),
        )
        val patternIds = completed.mapNotNull { it.patternId }.toSet()
        assertTrue("the missing equal is inserted with the active built-in pattern", "preset-igual" in patternIds)
        assertTrue("the result uses the user's K ink", "8d28a4ab-5531-4b35-b6c9-1efceb018edf" in patternIds)
        assertTrue("the result writes the coefficient 2", "preset-2" in patternIds)
        assertEquals(completed, MathNotes.materialize(
            ink,
            ink.mapTo(mutableSetOf()) { it.id },
            patterns,
            ids = IdGenerator.sequential("materialized-real-pattern"),
        ))
    }

    @Test fun `real duplicate disabled digits and operator still read and autocomplete`() {
        val ink = rowOf(
            "e021ae57-0d65-4f8d-abcd-3870822ba1db" to false, // disabled user 2
            "32c18bce-9ab7-4b38-8b60-9851a4f68221" to true, // disabled user Más
            "eb5f38e5-f13a-426e-be0a-976436e380df" to false, // disabled user 3
            "10d32576-c220-412c-bf98-486b1af12cce" to true, // disabled user Igual
        )
        assertEquals("2+3=", MathNotes.reviewLines(ink, patterns).single().text)
        val result = MathNotes.results(ink, patterns)
        assertTrue("the imported active preset 5 is drawn", result.any { it.patternId == "preset-5" })
        assertTrue(result.all { it.color == MathNotes.DEFAULT_RESULT_COLOR })

        val noEquals = ink.dropLast(2)
        val completed = MathNotes.materialize(
            noEquals,
            noEquals.mapTo(mutableSetOf()) { it.id },
            patterns,
            ids = IdGenerator.sequential("materialized-duplicate-pattern"),
        )
        assertTrue(completed.any { it.patternId == "preset-igual" })
        assertTrue(completed.any { it.patternId == "preset-5" })
    }
}
