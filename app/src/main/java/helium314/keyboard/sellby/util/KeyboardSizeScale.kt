// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import kotlin.math.roundToInt

/**
 * Settings -> Atur Keyboard -> "Ukuran keyboard": maps the slider onto HeliBoard's own keyboard height
 * scale (pref keyboard_height_scale, 1.0 = the stock 205.6dp keyboard).
 *
 * The slider is CONTINUOUS - [STEPS] positions are only the SeekBar's integer resolution (well below one
 * pixel of keyboard height per position), so dragging feels like sliding, not like picking one of a few
 * sizes. It runs from 90% ("Pendek") to 110% ("Tinggi"), so the stock size (100%) sits exactly in the
 * MIDDLE of the slider, and both halves are the same speed. History of the range, each one reported as
 * too extreme or too coarse: 70%..130% in 5% steps, then 85%..115% in 5% steps, then 85%..100%, then
 * 85%..110% with 100% in the middle (two different speeds). The stock settings screen's 30%..150% range
 * is deliberately not exposed here.
 *
 * The value is stored in the SAME per-orientation pref the stock settings screen and
 * [Settings.readHeightScale] use, so the keyboard picks it up exactly as it always did - only the way
 * a change is applied differs (see KeyboardSwitcher.applyKeyboardHeightLive: no hide/show of the window).
 */
object KeyboardSizeScale {
    /** The slider's integer resolution: positions 0..[STEPS]. */
    const val STEPS = 1000

    /** The position of the stock size: exactly the middle. */
    const val MIDDLE = STEPS / 2

    const val MIN = 0.90f
    const val MAX = 1.10f

    // Integer math over a common denominator keeps the landmarks exact (position 0 is the float closest to
    // 0.90, MIDDLE is exactly 1.0, STEPS is the float closest to 1.10): scale = (9000 + 2 * progress) / 10000.
    private const val DENOMINATOR = 10000f
    private const val BASE = 9000f
    private const val PER_STEP = 2f

    fun toScale(progress: Int): Float = (BASE + PER_STEP * progress.coerceIn(0, STEPS)) / DENOMINATOR

    /** Nearest slider position for [scale]; a stored value outside [MIN]..[MAX] (e.g. from an earlier,
     *  wider version of this slider, or the stock settings screen) is clamped to the nearest end. */
    fun toProgress(scale: Float): Int =
        ((scale * DENOMINATOR - BASE) / PER_STEP).roundToInt().coerceIn(0, STEPS)

    /** [scale] limited to the range the slider offers. */
    fun clamp(scale: Float): Float = scale.coerceIn(MIN, MAX)

    /** The pref key [Settings.readHeightScale] reads for this orientation/fold state. */
    fun prefKey(isLandscape: Boolean, isFolded: Boolean): String =
        createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, findIndexOfDefaultSetting(isLandscape, isFolded), 2)

    fun current(prefs: SharedPreferences, isLandscape: Boolean, isFolded: Boolean): Float =
        Settings.readHeightScale(prefs, isLandscape, isFolded)
}
