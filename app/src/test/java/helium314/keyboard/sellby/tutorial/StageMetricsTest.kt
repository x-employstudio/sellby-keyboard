// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.tutorial

import helium314.keyboard.sellby.companion.tutorial.StageMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageMetricsTest {
    private val fixed = StageMetrics.OUTSIDE + StageMetrics.CHAT_MIN + StageMetrics.TOOLBAR

    @Test
    fun tallScreenGetsTheFullPanel() {
        val m = StageMetrics.compute(totalHeight = 900f)
        assertTrue(m.fits)
        assertEquals(StageMetrics.PANEL_WANT, m.panelHeight, 0.01f)
    }

    @Test
    fun shortScreenShrinksThePanel() {
        val m = StageMetrics.compute(totalHeight = 640f)
        assertTrue(m.fits)
        assertTrue(m.panelHeight < StageMetrics.PANEL_WANT)
        assertTrue(m.panelHeight >= StageMetrics.PANEL_MIN)
    }

    @Test
    fun tooShortScreenDoesNotFit() {
        assertFalse(StageMetrics.compute(totalHeight = 560f).fits)
    }

    @Test
    fun budgetNeverExceedsTheScreen() {
        for (h in 500..1100 step 20) {
            val m = StageMetrics.compute(h.toFloat())
            if (m.fits) {
                assertTrue("h=$h", fixed + m.panelHeight <= h)
            }
        }
    }
}
