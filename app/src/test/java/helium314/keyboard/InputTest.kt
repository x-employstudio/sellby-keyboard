// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.MotionEvent
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// todo: expand with swipe & touch stuff
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class InputTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        // create keyboardView
        keyboardSwitcher.onCreateInputView(latinIME, true)
        // create and set keyboard
        keyboardSwitcher.reloadMainKeyboard()
    }

    @Test fun pressShift() {
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun keyInput() {
        touchKey('a'.code, MotionEvent.ACTION_DOWN)
        touchKey('a'.code, MotionEvent.ACTION_UP)
        assertEquals("a", ShadowInputMethodService.text)
    }

    // Sellby: caps lock comes from double-tapping shift. Holding shift no longer locks it, because the
    // shift popup (caps lock) belongs to the "layout" popup-key type, which Sellby's default popup order
    // switches off. So holding is just a long shift press.
    @Test fun holdShiftDoesNotLock() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks() // this doesn't actually wait, just results in handling the delayed message immediately
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun doubleTapShiftLocksCaps() {
        lockCapsWithDoubleTap()
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun slidingInput() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        touchKey('F'.code, MotionEvent.ACTION_MOVE)
        touchKey('F'.code, MotionEvent.ACTION_UP)
        assertEquals("F", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun endCapsLockWithShift() {
        // keyboardSwitcher.setAlphabetShiftLockedKeyboard() isn't enough because it doesn't set the fully correct state
        lockCapsWithDoubleTap()
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks() // lets the double-tap window expire

        // later event time to get past the mTouchNoiseThresholdTime filter after the double tap (todo: should be done automatically in the test)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 500L)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP, 500L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun slidingInputFromCapsLock() {
        lockCapsWithDoubleTap()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks() // lets the double-tap window expire

        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 500L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
        touchKey('f'.code, MotionEvent.ACTION_MOVE)
        touchKey('f'.code, MotionEvent.ACTION_UP)
        assertEquals("f", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
    }

    private fun lockCapsWithDoubleTap() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)
        // the later event time is needed to get past the up-to-down touch noise filter
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP, 50L)
    }

    private fun touchKey(code: Int, action: Int, eventTime: Long = 0L) {
        val kb = keyboardSwitcher.keyboard!!
        val key = kb.getKey(code)!!
        val x = key.x + key.height / 2
        val y = key.y + key.width / 2
        val me = MotionEvent.obtain( // todo: there are many more parameters, also related to pointers
            0L,
            eventTime,
            action,
            x.toFloat(),
            y.toFloat(),
            0
        )
        keyboardSwitcher.mainKeyboardView.onTouchEvent(me)
    }

    @BeforeTest
    fun reset() {
        keyboardSwitcher.reloadMainKeyboard()
        ShadowInputMethodService.reset()
    }
}
