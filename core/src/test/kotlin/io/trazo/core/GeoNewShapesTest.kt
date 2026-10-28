package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresión de la primera tanda de geometría v4: contorno, ambigüedad, edición, historial y JSON. */
class GeoNewShapesTest {
    private val newKinds = linkedMapOf(
        "octagono" to GeoKind.OCTAGON,
        "trapecio" to GeoKind.TRAPEZOID,
        "estrella-5" to GeoKind.STAR_5,
        "semicirculo" to GeoKind.SEMICIRCLE,
        "arco" to GeoKind.ARC,
        "flecha-doble" to GeoKind.DOUBLE_ARROW,
    )

    private fun geo(slug: String) = GeoPatterns.all().single { it.id == GeoPatterns.PREFIX + slug }

    /** Ejemplo del contorno, deformado levemente para simular un trazo y no repetirlo literalmente. */
    private fun handOutline(p: InkPattern): List<InkStroke> = p.gestureExamples.single().mapIndexed { i, stroke ->
        stroke.copy(id = "mano-$i", points = stroke.points.mapIndexed { j, pt ->
            pt.copy(x = pt.x + ((j * 3) % 5 - 2) * .22f, y = pt.y + ((j * 7) % 11 - 5) * .18f)
        })
    }

    @Test fun `six v4 presets correspond to native geometric shapes`() {
        assertEquals(28, GeoPatterns.all().size)
        assertEquals(4, PresetPatterns.VERSION)
        newKinds.forEach { (slug, kind) ->
            val pattern = geo(slug)
            assertEquals(kind, ParametricTemplates.recommendedKindFor(pattern.id))
            assertTrue(pattern.outputInk.isNotEmpty())
            assertTrue(pattern.gestureExamples.single().isNotEmpty())
            assertEquals(PatternCategory.SHAPES, pattern.category)
            assertTrue(PresetPatterns.since(pattern.id) == 4)
        }
        assertEquals(3, PresetPatterns.since(GeoPatterns.PREFIX + "hexagono"))
    }

    @Test fun `hand drawn outlines identify their own shape`() {
        val matcher = GestureMatcher()
        newKinds.keys.forEach { slug ->
            val expected = geo(slug)
            val match = matcher.recognize(handOutline(expected), listOf(expected))
            assertTrue("No reconoce $slug: $match", match is MatchResult.Found)
            assertEquals(expected.id, (match as MatchResult.Found).match.pattern.id)
        }
    }

    @Test fun `each outline outranks its nearest similar contour`() {
        val similar = mapOf(
            "octagono" to "hexagono",
            "trapecio" to "paralelogramo",
            "estrella-5" to "pentagono",
            "semicirculo" to "arco",
            "arco" to "semicirculo",
            "flecha-doble" to "vector-2d",
        )
        val matcher = GestureMatcher()
        similar.forEach { (slug, otherSlug) ->
            val self = geo(slug)
            val gesture = handOutline(self)
            val mine = matcher.bestScore(gesture, self) ?: 0f
            val other = matcher.bestScore(gesture, geo(otherSlug)) ?: 0f
            assertTrue("$slug se confunde con $otherSlug: $mine vs $other", mine > other + .01f)
        }
    }

    @Test fun `shape controls remain independent and editable`() {
        newKinds.values.forEach { kind ->
            val s = ParametricTemplates.create("test-$kind", kind, GeoPoint(200f, 200f))
            val w = ParametricGeometry.setDimension(s, "w", 220f)
            val h = ParametricGeometry.setDimension(w, "h", 90f)
            assertEquals(220f, h.width)
            assertEquals(90f, h.height)
            assertTrue(h.render().isNotEmpty())
        }
        val star = ParametricTemplates.create("star", GeoKind.STAR_5, GeoPoint(0f, 0f))
        assertTrue(ParametricGeometry.setDimension(star, "r", 22f).starInnerRatio != star.starInnerRatio)
        val thinStar = ParametricGeometry.setDimension(ParametricGeometry.setDimension(star, "w", 80f), "h", 220f)
        val inner = ParametricGeometry.draw(thinStar).handles.first { it.id == "r" }.at
        assertEquals(thinStar.starInnerRatio, ParametricGeometry.editHandle(thinStar, "r", inner).starInnerRatio, .0001f)
        val trap = ParametricTemplates.create("trap", GeoKind.TRAPEZOID, GeoPoint(0f, 0f))
        assertTrue(ParametricGeometry.setDimension(trap, "top", 50f).trapezoidTopRatio != trap.trapezoidTopRatio)
        val arc = ParametricTemplates.create("arc", GeoKind.ARC, GeoPoint(0f, 0f))
        assertEquals(120f, ParametricGeometry.setDimension(arc, "angle", 120f).arcSweepDegrees)
        assertTrue(ParametricGeometry.draw(arc).paths.all { !it.closed })
        assertTrue(ParametricGeometry.draw(ParametricTemplates.create("semi", GeoKind.SEMICIRCLE, GeoPoint(0f, 0f))).paths.all { it.closed })
    }

    @Test fun `one undo reverses each conversion without losing the original gesture`() {
        newKinds.forEach { (slug, kind) ->
            val c = InkController(patterns = { DefaultPatterns.all() + GeoPatterns.all() }, ids = IdGenerator.sequential())
            val drawn = c.insertPattern(DefaultPatterns.root(), 400f, 400f, 120f)
            assertNotNull(c.convertWith(geo(slug), drawn.map { it.id }))
            assertEquals(kind, c.page.shapes.single().kind)
            assertTrue(c.page.strokes.isEmpty())
            assertTrue(c.undo())
            assertTrue(c.page.shapes.isEmpty())
            assertEquals(drawn, c.page.strokes)
        }
    }

    @Test fun `new kinds survive notebook json round trip and old documents remain readable`() {
        val shapes = newKinds.map { (slug, kind) ->
            ParametricTemplates.create(slug, kind, GeoPoint(100f, 200f)).let { s ->
                when (kind) {
                    GeoKind.ARC -> ParametricGeometry.setDimension(s, "angle", 225f)
                    GeoKind.STAR_5 -> ParametricGeometry.setDimension(s, "r", 35f)
                    GeoKind.TRAPEZOID -> ParametricGeometry.setDimension(s, "top", 75f)
                    else -> s
                }
            }
        }
        val original = Notebook("nb", "V4", listOf(Page("p1", emptyList(), shapes)), Paper.GRID, 1L, 2L)
        assertEquals(original, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(original)))
        val older = Notebook("old", "V3", listOf(Page("p0")), Paper.GRID, 1L, 2L)
        val legacyJson = TrazoFormat.encodeNotebook(older).replace(",\"parametricShapes\":[]", "")
        assertEquals(older, TrazoFormat.decodeNotebook(legacyJson))
    }

    @Test fun `v3 users only receive the six new shapes and keep their previous examples`() {
        val old = GeoPatterns.all().filterNot { it.id in GeoPatterns.V4_IDS }
        val installed = PresetPatterns.install(old, 3)
        val geo = installed.filter { GeoPatterns.isGeo(it) }
        assertEquals(old.size + 6, geo.size)
        assertEquals(old.map { it.id }, geo.take(old.size).map { it.id })
    }
}
