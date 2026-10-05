// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSizeScaleTest {
    @Test
    fun theSliderRunsFrom90To110Percent() {
        assertEquals(0.90f, KeyboardSizeScale.toScale(0), 0f)
        assertEquals(1.10f, KeyboardSizeScale.toScale(KeyboardSizeScale.STEPS), 0f)
        assertEquals(0.90f, KeyboardSizeScale.MIN, 0f)
        assertEquals(1.10f, KeyboardSizeScale.MAX, 0f)
    }

    @Test
    fun theStockSizeIsExactlyInTheMiddleOfTheSlider() {
        assertEquals(KeyboardSizeScale.STEPS / 2, KeyboardSizeScale.MIDDLE)
        assertEquals(1.0f, KeyboardSizeScale.toScale(KeyboardSizeScale.MIDDLE), 0f)
        assertEquals(KeyboardSizeScale.MIDDLE, KeyboardSizeScale.toProgress(1.0f))
    }

    /** "Seamless, not 7 steps": neighbouring positions are a tiny fraction of a percent apart, on both sides of 100%. */
    @Test
    fun theSliderIsContinuousNotAFewSteps() {
        assertTrue(KeyboardSizeScale.STEPS >= 500)
        var previous = KeyboardSizeScale.toScale(0)
        for (progress in 1..KeyboardSizeScale.STEPS) {
            val scale = KeyboardSizeScale.toScale(progress)
            assertTrue("not increasing at $progress", scale > previous)
            assertTrue("gap too coarse at $progress", scale - previous < 0.0005f)
            previous = scale
        }
    }

    @Test
    fun theLeftHalfIsShorterThanStockAndTheRightHalfIsTaller() {
        for (progress in 0 until KeyboardSizeScale.MIDDLE) assertTrue(KeyboardSizeScale.toScale(progress) < 1.0f)
        for (progress in KeyboardSizeScale.MIDDLE + 1..KeyboardSizeScale.STEPS) assertTrue(KeyboardSizeScale.toScale(progress) > 1.0f)
    }

    /** Both halves are the same speed: 10% shorter at one end, 10% taller at the other. */
    @Test
    fun theTwoHalvesAreSymmetricAroundTheStockSize() {
        for (offset in 0..KeyboardSizeScale.MIDDLE) {
            val below = 1.0f - KeyboardSizeScale.toScale(KeyboardSizeScale.MIDDLE - offset)
            val above = KeyboardSizeScale.toScale(KeyboardSizeScale.MIDDLE + offset) - 1.0f
            assertEquals("offset $offset", below, above, 0.0001f)
        }
    }

    @Test
    fun everyPositionRoundTripsThroughItsOwnScale() {
        for (progress in 0..KeyboardSizeScale.STEPS) {
            assertEquals(progress, KeyboardSizeScale.toProgress(KeyboardSizeScale.toScale(progress)))
        }
    }

    @Test
    fun aStoredValueSnapsToTheNearestPosition() {
        assertEquals(0, KeyboardSizeScale.toProgress(0.90f))
        assertEquals(KeyboardSizeScale.STEPS, KeyboardSizeScale.toProgress(1.10f))
        // Halfway between 90% and 100% is a quarter of the slider, halfway between 100% and 110% three quarters.
        assertEquals(KeyboardSizeScale.STEPS / 4, KeyboardSizeScale.toProgress(0.95f))
        assertEquals(KeyboardSizeScale.STEPS * 3 / 4, KeyboardSizeScale.toProgress(1.05f))
    }

    @Test
    fun aStoredValueOutsideTheRangeIsClampedToTheNearestEnd() {
        // Earlier versions allowed 70%..130%, 85%..115%, 85%..100% and 85%..110%; the stock settings screen 30%..150%.
        assertEquals(0, KeyboardSizeScale.toProgress(0.30f))
        assertEquals(0, KeyboardSizeScale.toProgress(0.85f))
        assertEquals(KeyboardSizeScale.STEPS, KeyboardSizeScale.toProgress(1.15f))
        assertEquals(KeyboardSizeScale.STEPS, KeyboardSizeScale.toProgress(1.50f))
        assertEquals(0.90f, KeyboardSizeScale.clamp(0.85f), 0f)
        assertEquals(1.10f, KeyboardSizeScale.clamp(1.30f), 0f)
        assertEquals(1.05f, KeyboardSizeScale.clamp(1.05f), 0f)
        assertEquals(0.95f, KeyboardSizeScale.clamp(0.95f), 0f)
    }

    @Test
    fun anOutOfRangePositionIsClampedToo() {
        assertEquals(0.90f, KeyboardSizeScale.toScale(-3), 0f)
        assertEquals(1.10f, KeyboardSizeScale.toScale(99_999), 0f)
    }

    @Test
    fun prefKeysMatchTheOnesTheStockHeightScaleReads() {
        // Settings.readHeightScale builds these exact keys (index = landscape + 2 * folded, 2 conditions).
        assertEquals("keyboard_height_scale_false_false", KeyboardSizeScale.prefKey(isLandscape = false, isFolded = false))
        assertEquals("keyboard_height_scale_true_false", KeyboardSizeScale.prefKey(isLandscape = true, isFolded = false))
        assertEquals("keyboard_height_scale_false_true", KeyboardSizeScale.prefKey(isLandscape = false, isFolded = true))
        assertEquals("keyboard_height_scale_true_true", KeyboardSizeScale.prefKey(isLandscape = true, isFolded = true))
    }

    @Test
    fun portraitAndLandscapeKeepSeparateSizes() {
        assertNotEquals(
            KeyboardSizeScale.prefKey(isLandscape = false, isFolded = false),
            KeyboardSizeScale.prefKey(isLandscape = true, isFolded = false),
        )
    }
}
