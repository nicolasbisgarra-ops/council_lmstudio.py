package io.trazo.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private val pageWidth = 1080f
    private val pageHeight = 1414f
    private val bounds = CanvasBounds.aroundPage(pageWidth, pageHeight)

    @Test fun initialFitKeepsTheOriginalPageAtTheOrigin() {
        val v = Viewport()
        v.resizeNotebook(709, 1467, pageWidth, pageHeight)
        assertEquals(709f / pageWidth, v.scale, 0.001f)
        assertEquals(0f, v.tx, 0.001f)
        assertEquals(0f, v.ty, 0.001f)
        assertEquals(-pageWidth, bounds.left, 0f)
        assertEquals(-pageHeight, bounds.top, 0f)
        assertEquals(2f * pageWidth, bounds.right, 0f)
        assertEquals(2f * pageHeight, bounds.bottom, 0f)
    }

    @Test fun fittedPageFillsLandscapeAfterPortrait() {
        val v = Viewport()
        v.resizeNotebook(709, 1467, pageWidth, pageHeight)
        v.resizeNotebook(1467, 709, pageWidth, pageHeight)
        assertEquals(1467f, pageWidth * v.scale, 0.001f)
        assertEquals(0f, v.tx, 0.001f)
        assertEquals(0f, v.ty, 0.001f)
    }

    @Test fun minimumZoomFitsTheEntireThreeByThreeWorkspace() {
        val v = Viewport()
        val fit = v.workspaceFit(900, 700, bounds)
        assertEquals(minOf(900f / (3f * pageWidth), 700f / (3f * pageHeight)), fit, 0.0001f)
        assertTrue(bounds.width * fit <= 900.001f)
        assertTrue(bounds.height * fit <= 700.001f)
    }

    @Test fun panCanRevealEachOuterEdgeWithoutLeavingTheWorkspace() {
        val v = Viewport()
        v.resizeNotebook(1000, 800, pageWidth, pageHeight)
        v.scale = v.workspaceFit(1000, 800, bounds) * 2f

        v.tx = 99999f; v.ty = 99999f
        v.clampToCanvas(1000, 800, bounds)
        assertEquals(-bounds.left * v.scale, v.tx, 0.001f)
        assertEquals(-bounds.top * v.scale, v.ty, 0.001f)

        v.tx = -99999f; v.ty = -99999f
        v.clampToCanvas(1000, 800, bounds)
        assertEquals(1000f - bounds.right * v.scale, v.tx, 0.001f)
        assertEquals(800f - bounds.bottom * v.scale, v.ty, 0.001f)

        val visibleLeft = -v.tx / v.scale
        val visibleTop = -v.ty / v.scale
        val visibleRight = (1000f - v.tx) / v.scale
        val visibleBottom = (800f - v.ty) / v.scale
        assertTrue(visibleLeft >= bounds.left - 0.001f)
        assertTrue(visibleTop >= bounds.top - 0.001f)
        assertTrue(visibleRight <= bounds.right + 0.001f)
        assertTrue(visibleBottom <= bounds.bottom + 0.001f)
    }

    @Test fun zoomAndReadingPositionRemainCoherentAcrossRotation() {
        val v = Viewport()
        v.resizeNotebook(709, 1467, pageWidth, pageHeight)
        val oldFit = 709f / pageWidth
        v.scale = oldFit * 1.91f
        v.tx = 709f / 2f - 756f * v.scale
        v.ty = -250f * v.scale

        v.resizeNotebook(1467, 709, pageWidth, pageHeight)
        val rotatedFit = 1467f / pageWidth
        assertEquals(1.91f, v.scale / rotatedFit, 0.001f)
        assertEquals(756f, (1467f / 2f - v.tx) / v.scale, 0.001f)
        assertEquals(250f, -v.ty / v.scale, 0.001f)

        v.resizeNotebook(709, 1467, pageWidth, pageHeight)
        assertEquals(1.91f, v.scale / oldFit, 0.001f)
        assertEquals(250f, -v.ty / v.scale, 0.001f)
    }

    @Test fun reattachingAtSameSizeKeepsAValidTransform() {
        val v = Viewport()
        v.resizeNotebook(1000, 800, pageWidth, pageHeight)
        v.scale = v.workspaceFit(1000, 800, bounds) * 2f
        v.tx = 200f; v.ty = -100f
        v.resizeNotebook(1000, 800, pageWidth, pageHeight)
        assertEquals(200f, v.tx, 0.001f)
        assertEquals(-100f, v.ty, 0.001f)
    }

    @Test fun zeroSizeDoesNotDestroyRememberedTransform() {
        val v = Viewport()
        v.resizeNotebook(1000, 800, pageWidth, pageHeight)
        val original = v.scale
        v.resizeNotebook(0, 0, pageWidth, pageHeight)
        assertEquals(original, v.scale, 0f)
        v.resizeNotebook(700, 1000, pageWidth, pageHeight)
        assertEquals(700f / pageWidth, v.scale, 0.001f)
    }
}
