package io.trazo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletLayoutPolicyTest {
    @Test fun tabletBoundaryAndPhoneFallback() {
        for (width in listOf(360, 599)) {
            val result = TabletLayoutPolicy.decide(TabletLayoutPolicy.Input(width))
            assertFalse(result.tabletWindow)
            assertFalse(result.showFixedPages)
            assertEquals(width, result.remainingCanvasWidthDp)
        }
        for (width in listOf(600, 601, 800, 1200)) {
            val result = TabletLayoutPolicy.decide(TabletLayoutPolicy.Input(width))
            assertTrue(result.tabletWindow)
            assertTrue(result.showFixedPages)
            assertEquals(width - 200, result.remainingCanvasWidthDp)
        }
    }

    @Test fun preservesCanvasWhenDockAndRailCompete() {
        val narrow = TabletLayoutPolicy.decide(
            TabletLayoutPolicy.Input(600, sideToolbarWidthDp = 112, patternRailRequested = true, patternRailWidthDp = 100),
        )
        assertFalse(narrow.showFixedPages)
        assertTrue(narrow.showPatternRail)
        assertEquals(388, narrow.remainingCanvasWidthDp)

        val wide = TabletLayoutPolicy.decide(
            TabletLayoutPolicy.Input(1200, sideToolbarWidthDp = 112, patternRailRequested = true, patternRailWidthDp = 144),
        )
        assertTrue(wide.showFixedPages)
        assertTrue(wide.showPatternRail)
        assertEquals(744, wide.remainingCanvasWidthDp)
    }

    @Test fun largerFontReservesSpaceForControlsAndCanvas() {
        val scale15 = TabletLayoutPolicy.decide(TabletLayoutPolicy.Input(800, fontScale = 1.5f))
        assertTrue(scale15.showFixedPages)
        assertEquals(480, scale15.requiredCanvasWidthDp)

        val scale20 = TabletLayoutPolicy.decide(TabletLayoutPolicy.Input(600, fontScale = 2f))
        assertFalse(scale20.showFixedPages)
        assertEquals(640, scale20.requiredCanvasWidthDp)
        assertEquals(600, scale20.remainingCanvasWidthDp)
    }

    @Test fun noNegativeOrNaNWidthsAndExplicitCollapsingWorks() {
        val malformed = TabletLayoutPolicy.decide(
            TabletLayoutPolicy.Input(-4, fontScale = Float.NaN, sideToolbarWidthDp = -90, pagesPanelWidthDp = -200),
        )
        assertEquals(0, malformed.remainingCanvasWidthDp)
        assertEquals(320, malformed.requiredCanvasWidthDp)
        val collapsed = TabletLayoutPolicy.decide(TabletLayoutPolicy.Input(1200, pagesRequested = false))
        assertFalse(collapsed.showFixedPages)
        assertEquals(1200, collapsed.remainingCanvasWidthDp)
    }

    @Test fun windowsIncludingSplitAndOrientationObeySameInvariant() {
        for (width in listOf(360, 599, 600, 601, 800, 1200)) {
            for (scale in listOf(1f, 1.5f, 2f)) {
                for (side in listOf(0, 112)) {
                    for (pages in listOf(false, true)) {
                        val result = TabletLayoutPolicy.decide(
                            TabletLayoutPolicy.Input(width, fontScale = scale, sideToolbarWidthDp = side,
                                pagesRequested = pages, patternRailRequested = true, patternRailWidthDp = 96),
                        )
                        assertTrue("No canvas negative for $width/$scale/$side", result.remainingCanvasWidthDp >= 0)
                        assertTrue(result.remainingCanvasWidthDp <= result.usableWidthDp)
                        if (result.showFixedPages || result.showPatternRail) {
                            assertTrue("Panel must not steal minimum canvas width", result.remainingCanvasWidthDp >= result.requiredCanvasWidthDp)
                        }
                    }
                }
            }
        }
    }
}
