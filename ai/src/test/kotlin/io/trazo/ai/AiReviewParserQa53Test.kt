package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA53: el parser no da por bien una hoja cuando la respuesta dice lo contrario o vino dañada. */
class AiReviewParserQa53Test {
    @Test fun negatedFreeTextIsNotCorrect() {
        for (t in listOf(
            "La resolución no es correcta.",
            "El resultado no está correcto.",
            "La respuesta es incorrecta.",
            "Hay errores en el desarrollo, no es correcto.",
            "Es correcto el planteo, pero hay un error en la cuenta.",
        )) assertFalse(t, AiReviewParser.parseFreeText(t).correct)
    }

    @Test fun positiveFreeTextStillCorrect() {
        for (t in listOf("No hay errores.", "Está correcta.", "Todo bien, sin errores."))
            assertTrue(t, AiReviewParser.parseFreeText(t).correct)
    }

    @Test fun unreadableMarksAskForRepair() {
        var asked = 0
        val r = AiReviewParser.parse("""{"correcto": true, "marcas": [{"tipo": "cuenta", "breve": "sin renglón"}]}""") {
            asked++
            """{"correcto": false, "marcas": [{"renglon": 2, "tipo": "cuenta", "breve": "b"}]}"""
        }
        assertEquals(1, asked)
        assertFalse(r.correct)
        assertEquals(2, r.marks.single().line)
    }

    @Test fun unreadableMarksWithoutRepairAreNotCorrect() {
        assertFalse(AiReviewParser.parse("""{"correcto": true, "marcas": [{"tipo": "cuenta"}]}""").correct)
    }

    @Test fun partlyUnreadableMarksKeepTheGoodOnesAndAreNotCorrect() {
        val r = AiReviewParser.parseJson("""{"correcto": true, "marcas": [{"renglon": 1, "breve": "b"}, {"breve": "x"}, 7]}""")!!
        assertFalse(r.correct)
        assertEquals(1, r.marks.single().line)
    }

    @Test fun nonBooleanCorrectIsNotCorrect() {
        assertFalse(AiReviewParser.parseJson("""{"correcto": "quizás", "marcas": []}""")!!.correct)
        assertFalse(AiReviewParser.parseJson("""{"correcto": 1, "marcas": []}""")!!.correct)
        assertTrue(AiReviewParser.parseJson("""{"correcto": "true", "marcas": []}""")!!.correct)
    }
}
