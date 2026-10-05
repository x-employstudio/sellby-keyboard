// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.event

import android.view.HapticFeedbackConstants

enum class HapticEvent(@JvmField val feedbackConstant: Int, @JvmField val allowCustomDuration: Boolean) {
    NO_HAPTICS(HapticFeedbackConstants.NO_HAPTICS, false),
    // Sellby: CLOCK_TICK instead of stock KEYBOARD_TAP for typing. KEYBOARD_TAP is silently
    // swallowed on some devices (OEM input stacks gate it even with FLAG_IGNORE_GLOBAL_SETTING),
    // while CLOCK_TICK (slide-spacebar / hold-backspace gestures) and LONG_PRESS were confirmed
    // working on every test device - so typing uses a constant known to actually vibrate.
    KEY_PRESS(HapticFeedbackConstants.CLOCK_TICK, true),
//    KEY_RELEASE(
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
//            HapticFeedbackConstants.KEYBOARD_RELEASE
//        } else {
//            HapticFeedbackConstants.?
//        },
//        ?
//    ),
    KEY_LONG_PRESS(HapticFeedbackConstants.LONG_PRESS, true),
    KEY_REPEAT(HapticFeedbackConstants.CLOCK_TICK, allowCustomDuration = false),
//    GESTURE_START(
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            HapticFeedbackConstants.GESTURE_START
//        } else {
//            HapticFeedbackConstants.?
//        },
//        ?
//    ),
    GESTURE_MOVE(HapticFeedbackConstants.CLOCK_TICK, false),
//    GESTURE_END(
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            HapticFeedbackConstants.GESTURE_END
//        } else {
//            HapticFeedbackConstants.?
//        },
//        ?
//    )
}
