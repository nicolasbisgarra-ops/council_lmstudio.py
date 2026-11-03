package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternLibraryImportMergeTest {
    private fun stroke(
        id: String,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        groupId: String? = null,
        startTime: Long = 0L,
        pointTime: Long = 0L,
        pressure: Float = 0.7f,
        color: Int = 0xFF253B34.toInt(),
        width: Float = 3f,
        brush: Brush = Brush.PEN,
    ) = InkStroke(
        id = id,
        points = listOf(InkPoint(a.first, a.second, pressure, pointTime), InkPoint(b.first, b.second, pressure, pointTime + 9)),
        color = color,
        width = width,
        startTime = startTime,
        groupId = groupId,
        brush = brush,
    )

    private fun pattern(
        id: String,
        name: String,
        gesture: List<InkStroke> = listOf(stroke("g-$id", 0f to 0f, 8f to 12f)),
        output: List<InkStroke> = listOf(stroke("o-$id", 0f to 10f, 16f to 0f, color = 0xFF00A000.toInt())),
    ) = InkPattern(id, name, listOf(gesture), output)

    @Test
    fun `reuses exact behavior despite IDs timing and local metadata, preserving local preferences`() {
        val local = pattern(
            id = "local-k",
            name = "Mi K",
            gesture = listOf(
                stroke("local-g1", 0f to 0f, 8f to 12f, groupId = "local-group", startTime = 400L, pointTime = 10L),
                stroke("local-g2", 8f to 12f, 10f to 0f, groupId = "local-group", startTime = 900L, pointTime = 30L),
            ),
        ).copy(
            enabled = false,
            updatedAt = 70L,
            category = PatternCategory.MINE,
            outputInk = localOutputWithCalc("local-calc"),
            unknown = "k",
        )
        val incoming = pattern(
            id = "foreign-k",
            name = "K (importado)",
            gesture = listOf(
                stroke("other-g1", 0f to 0f, 8f to 12f, groupId = "g-a", startTime = 0L, pointTime = 0L),
                stroke("other-g2", 8f to 12f, 10f to 0f, groupId = "g-b", startTime = 12L, pointTime = 18L),
            ),
        )
        val imported = incoming.copy(
            enabled = true,
            builtIn = true,
            updatedAt = 999L,
            category = PatternCategory.LETTERS,
            outputInk = incoming.outputInk.map { it.copy(calc = CalcTag("foreign-calc", result = true, inputs = 1, steps = listOf("x"))) },
            unknown = "k",
        )

        val merged = PatternLibrary.mergeWithRemapping(listOf(local), listOf(imported), IdGenerator.sequential("new-"))

        assertEquals(listOf(local), merged.patterns)
        assertEquals(mapOf("foreign-k" to "local-k"), merged.idRemap)
        assertEquals("Mi K", merged.patterns.single().name)
        assertEquals(false, merged.patterns.single().enabled)
    }

    @Test
    fun `same name or output geometry alone does not make patterns duplicates`() {
        val original = pattern("original", "Símbolo")
        val changedGesture = pattern(
            "changed-gesture", "Símbolo",
            gesture = listOf(stroke("g", 0f to 0f, 8f to 14f)),
            output = original.outputInk.map { it.copy(id = "same-output") },
        )
        val changedOutput = pattern(
            "changed-output", "Símbolo",
            gesture = original.gestureExamples.single().map { it.copy(id = "same-gesture") },
            output = listOf(stroke("different", 0f to 10f, 20f to 0f, color = 0xFF00A000.toInt())),
        )
        val changedPressure = pattern(
            "changed-pressure", "Símbolo",
            gesture = original.gestureExamples.single().map { s -> s.copy(points = s.points.map { it.copy(pressure = 0.9f) }) },
            output = original.outputInk.map { it.copy(id = "same-output") },
        )
        val merged = PatternLibrary.merge(listOf(original), listOf(changedGesture, changedOutput, changedPressure), IdGenerator.sequential("copy-"))

        assertEquals(listOf("original", "changed-gesture", "changed-output", "changed-pressure"), merged.map { it.id })
    }

    @Test
    fun `same drawing with different effective math symbols remains distinct`() {
        val equals = pattern("custom-equals", "Igual")
        val approximately = pattern("custom-approx", "Aproximadamente igual")
        val firstAutoLetter = pattern("auto-letter-one", "Símbolo propio")
        val secondAutoLetter = pattern("auto-letter-two", "Símbolo propio")

        assertNotEquals(MathNotes.patternSymbol(equals), MathNotes.patternSymbol(approximately))
        assertNotEquals(MathNotes.patternSymbol(firstAutoLetter), MathNotes.patternSymbol(secondAutoLetter))
        val merged = PatternLibrary.merge(
            emptyList(),
            listOf(equals, approximately, firstAutoLetter, secondAutoLetter),
            IdGenerator.sequential("unused-"),
        )

        assertEquals(4, merged.size)
    }

    @Test
    fun `functional recognition and rendering settings remain part of pattern identity`() {
        val base = pattern("base", "same")
        val variants = listOf(
            base.copy(id = "score", minScore = 0.91f),
            base.copy(id = "hold", holdMillis = 700L),
            base.copy(id = "unknown", unknown = "x"),
            base.copy(id = "sizing", sizing = OutputSizing.FIXED),
            base.copy(id = "scale", outputScale = 1.2f),
            base.copy(id = "stretch", stretch = Stretch(Axis.HORIZONTAL, from = 0.5f)),
            base.copy(id = "brush", outputInk = base.outputInk.map { it.copy(brush = Brush.BALLPOINT) }),
            base.copy(id = "color", outputInk = base.outputInk.map { it.copy(color = 0xFF112233.toInt()) }),
            base.copy(id = "width", outputInk = base.outputInk.map { it.copy(width = it.width + 1f) }),
        )

        val merged = PatternLibrary.merge(listOf(base), variants, IdGenerator.sequential("copy-"))

        assertEquals(1 + variants.size, merged.size)
    }

    @Test
    fun `different content with colliding ID gets a stable new ID and remaps note references`() {
        val local = pattern("shared-id", "Local", output = listOf(stroke("local-out", 0f to 10f, 16f to 0f)))
        val imported = pattern("shared-id", "Imported", output = listOf(stroke("other-out", 0f to 10f, 24f to 0f)))
        val first = PatternLibrary.mergeWithRemapping(listOf(local), listOf(imported), IdGenerator.sequential("import-"))

        assertEquals(listOf("shared-id", "import-1"), first.patterns.map { it.id })
        assertEquals("Imported (importado)", first.patterns.last().name)
        assertEquals(mapOf("shared-id" to "import-1"), first.idRemap)
        assertEquals("automatic math meaning survives the ID remap", MathNotes.patternSymbol(imported), MathNotes.patternSymbol(first.patterns.last()))

        val second = PatternLibrary.mergeWithRemapping(first.patterns, listOf(imported), IdGenerator.sequential("unused-"))
        assertEquals(first.patterns, second.patterns)
        assertEquals(mapOf("shared-id" to "import-1"), second.idRemap)

        val calc = CalcTag(id = "calc-a", result = true, inputs = 2, steps = listOf("1+1", "2"))
        val shape = ParametricShape(
            id = "shape-a", kind = GeoKind.RECTANGLE, origin = GeoPoint(10f, 20f), sourcePatternId = "shared-id",
        )
        val ai = PageAi()
        val firstPage = Page(
            id = "page-a",
            strokes = listOf(
                stroke("stroke-a", 0f to 0f, 1f to 1f, groupId = "group-a").copy(patternId = "shared-id", calc = calc),
                stroke("stroke-b", 2f to 2f, 3f to 3f, groupId = "group-a").copy(patternId = "shared-id", calc = calc.copy(result = false)),
            ),
            parametricShapes = listOf(shape),
            ai = ai,
        )
        val secondPage = Page("page-b", listOf(stroke("stroke-c", 4f to 4f, 5f to 5f).copy(patternId = "not-in-archive")))
        val notebook = Notebook("note", "Nota", listOf(firstPage, secondPage))
        val remapped = notebook.remapPatternIds(first.idRemap)

        assertEquals(listOf("import-1", "import-1"), remapped.pages[0].strokes.map { it.patternId })
        assertEquals("not-in-archive", remapped.pages[1].strokes.single().patternId)
        assertEquals(firstPage.strokes.map { it.id }, remapped.pages[0].strokes.map { it.id })
        assertEquals(firstPage.strokes.map { it.groupId }, remapped.pages[0].strokes.map { it.groupId })
        assertEquals(firstPage.strokes.map { it.calc }, remapped.pages[0].strokes.map { it.calc })
        assertEquals("import-1", remapped.pages[0].parametricShapes.single().sourcePatternId)
        assertEquals(shape.copy(sourcePatternId = "import-1"), remapped.pages[0].parametricShapes.single())
        assertSame(ai, remapped.pages[0].ai)

        // Las páginas fijas conservan las referencias remapeadas al pasar al formato de carga diferida.
        val lazy = TrazoFormat.decodeLazy(TrazoFormat.encodeNotebook(remapped))
        assertTrue(lazy.slots.none { it.isLoaded })
        assertEquals("import-1", lazy.page(0).strokes.first().patternId)
        assertEquals("not-in-archive", lazy.page(1).strokes.single().patternId)
        assertEquals("group-a", lazy.page(0).strokes.first().groupId)
        assertEquals("calc-a", lazy.page(0).strokes.first().calc?.id)
        assertEquals("import-1", lazy.page(0).parametricShapes.single().sourcePatternId)
    }

    private fun localOutputWithCalc(id: String) = listOf(
        stroke("local-output", 0f to 10f, 16f to 0f, color = 0xFF00A000.toInt())
            .copy(calc = CalcTag(id, result = true, inputs = 1, steps = listOf("x"))),
    )
}
