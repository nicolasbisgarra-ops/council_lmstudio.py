package io.trazo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReviewParserTest {
    @Test fun readsTheRequestedJsonInsideACodeFence() {
        val reply = """
            Aquí está la revisión:
            ```json
            {"correcto": false, "puntaje": 7, "marcas": [
              {"renglon": 3, "tipo": "cuenta", "breve": "Signo invertido al pasar restando", "detalle": "x - 2 = 5 implica x = 7", "correccion": "x = 7"},
              {"renglón": "5", "tipo": "paso faltante", "breve": "Falta justificar la división"}
            ]}
            ```
        """.trimIndent()
        val r = AiReviewParser.parse(reply)
        assertFalse(r.correct)
        assertEquals(7.0, r.score!!, 1e-9)
        assertEquals(listOf(3, 5), r.marks.map { it.line })
        assertEquals(listOf(MarkKind.CALCULATION, MarkKind.MISSING_STEP), r.marks.map { it.kind })
        assertEquals("x = 7", r.marks[0].fix)
        assertFalse(r.fromFreeText)
    }

    @Test fun noMarksMeansCorrect() {
        val r = AiReviewParser.parse("""{"correcto": true, "puntaje": 10, "marcas": []}""")
        assertTrue(r.correct)
        assertTrue(r.marks.isEmpty())
    }

    @Test fun claimingCorrectWithMarksIsNotCorrect() {
        val r = AiReviewParser.parse("""{"correcto": true, "marcas": [{"renglon": 1, "tipo": "concepto", "breve": "b"}]}""")
        assertFalse(r.correct)
        assertEquals(MarkKind.CONCEPT, r.marks.single().kind)
    }

    @Test fun invalidJsonIsRepairedOnce() {
        var asked = 0
        val r = AiReviewParser.parse("""{"correcto": false, "marcas": [{"renglon": 2,""") { prompt ->
            asked++
            assertTrue(prompt.contains("no es JSON válido"))
            """{"correcto": false, "marcas": [{"renglon": 2, "tipo": "notacion", "breve": "Falta el dx"}]}"""
        }
        assertEquals(1, asked)
        assertEquals(MarkKind.NOTATION, r.marks.single().kind)
        assertFalse(r.fromFreeText)
    }

    @Test fun whenRepairFailsFreeTextIsReadWithRegex() {
        val reply = """
            Revisé la hoja.
            - **Renglón 4** (signo): se perdió el signo menos al distribuir.
            Línea 6 – falta el paso de factorizar antes de simplificar.
        """.trimIndent()
        var asked = 0
        val r = AiReviewParser.parse(reply) { asked++; "tampoco es JSON" }
        assertEquals(1, asked)
        assertTrue(r.fromFreeText)
        assertEquals(listOf(4, 6), r.marks.map { it.line })
        assertEquals(MarkKind.CALCULATION, r.marks[0].kind)
        assertEquals(MarkKind.MISSING_STEP, r.marks[1].kind)
        assertFalse(r.correct)
    }

    @Test fun freeTextWithoutErrors() {
        val r = AiReviewParser.parseFreeText("La resolución es correcta, sin errores.")
        assertTrue(r.correct)
        assertNull(r.score)
    }

    @Test fun promptNumbersLinesFromOne() {
        val text = AiReviewPrompt.userText(listOf("x^2 = 4", "", "x = 2"), Rigor.STRICT)
        assertTrue(text.contains("1: x^2 = 4"))
        assertTrue(text.contains("2: ?"))
        assertTrue(text.contains("3: x = 2"))
        assertTrue(text.contains(Rigor.STRICT.instruction))
        assertTrue(text.contains("Etiquetas crudas reconocidas originalmente por Trazo"))
        assertTrue(text.contains("box=[izquierda,arriba,derecha,abajo]"))
        assertTrue(text.contains("labels=[[etiqueta original,"))
        assertTrue(text.contains("No es una expresión reconstruida"))
        assertTrue(text.contains("identifíquela en la imagen"))
    }

    @Test fun rawLinePayloadIsPassedThroughWithoutCallingItAnInterpretation() {
        val raw = """{"labels":[{"text":"x","box":[12,4,28,19]},{"text":"?","box":[31,4,42,19]}]}"""
        val text = AiReviewPrompt.userText(listOf(raw), Rigor.NORMAL)

        assertTrue(text.contains("1: $raw"))
        assertTrue(text.contains("ni un resultado o veredicto del motor"))
        assertTrue(text.contains("No se incluye ninguna interpretación matemática del motor"))
    }

    @Test fun mandatoryReviewGuidePrecedesAndPreservesSavedProfileInstructions() {
        val oldSavedInstructions = "Instrucción guardada antigua: revise la cuenta."
        val system = AiReviewPrompt.systemInstructions(oldSavedInstructions)

        assertTrue(system.startsWith(AiReviewPrompt.MANDATORY_REVIEW_GUIDANCE))
        assertTrue(system.contains(oldSavedInstructions))
        assertTrue(system.indexOf(AiReviewPrompt.MANDATORY_REVIEW_GUIDANCE) < system.indexOf(oldSavedInstructions))
    }

    @Test fun repairRequestsFormattingOnlyAndForbidsReviewingAgain() {
        val broken = "La cuenta parece correcta."
        val prompt = AiReviewPrompt.repairText(broken)
        val system = AiReviewPrompt.repairSystemInstructions("Instrucciones guardadas.")

        assertTrue(prompt.contains("Reformatee únicamente esa respuesta"))
        assertTrue(prompt.contains("no vuelva a revisar ni resolver el ejercicio"))
        assertTrue(prompt.contains(broken))
        assertTrue(system.contains("exclusivamente para reparar el formato"))
        assertTrue(system.contains("no resuelva el ejercicio"))
        assertTrue(system.contains("Instrucciones guardadas."))
    }
}
