package io.trazo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarPreferencesTest {
    @Test fun phoneWidthsUseCompactScrollableLayout() {
        assertEquals(ToolbarLayoutMode.PHONE_COMPACT, ToolbarPreferences.layoutForWidth(360f))
        assertEquals(ToolbarLayoutMode.PHONE_COMPACT, ToolbarPreferences.layoutForWidth(599f))
    }

    @Test fun tabletWidthsKeepNavigationAndToolsOnSeparateRows() {
        assertEquals(ToolbarLayoutMode.TABLET_COMPACT, ToolbarPreferences.layoutForWidth(600f))
        assertEquals(ToolbarLayoutMode.TABLET_COMPACT, ToolbarPreferences.layoutForWidth(839f))
        assertEquals(ToolbarLayoutMode.TABLET_COMPACT, ToolbarPreferences.layoutForWidth(1099f))
    }

    @Test fun wideTabletUsesSingleRowAtSpecifiedBreakpoint() {
        assertEquals(ToolbarLayoutMode.WIDE_SINGLE_ROW, ToolbarPreferences.layoutForWidth(1100f))
        assertEquals(ToolbarLayoutMode.WIDE_SINGLE_ROW, ToolbarPreferences.layoutForWidth(1280f))
    }

    @Test fun tabletChromeUsesLargerTouchTargetsWithoutChangingPhoneSize() {
        assertEquals(40, ToolbarPreferences.chromeButtonSizeDp(ToolbarLayoutMode.PHONE_COMPACT))
        assertEquals(48, ToolbarPreferences.chromeButtonSizeDp(ToolbarLayoutMode.TABLET_COMPACT))
        assertEquals(48, ToolbarPreferences.chromeButtonSizeDp(ToolbarLayoutMode.WIDE_SINGLE_ROW))
    }

    @Test fun fontScaleAvoidsPrematureSingleRowAtBoundary() {
        assertEquals(ToolbarLayoutMode.TABLET_COMPACT, ToolbarPreferences.layoutForWidth(1200f, fontScale = 1.5f))
        assertEquals(ToolbarLayoutMode.TABLET_COMPACT, ToolbarPreferences.layoutForWidth(1200f, fontScale = 2f))
        assertEquals(ToolbarLayoutMode.WIDE_SINGLE_ROW, ToolbarPreferences.layoutForWidth(2200f, fontScale = 2f))
    }

    @Test fun invalidWidthsFallBackToSafeCompactLayout() {
        assertEquals(ToolbarLayoutMode.PHONE_COMPACT, ToolbarPreferences.layoutForWidth(Float.NaN))
        assertEquals(ToolbarLayoutMode.PHONE_COMPACT, ToolbarPreferences.layoutForWidth(Float.NEGATIVE_INFINITY))
    }
}
