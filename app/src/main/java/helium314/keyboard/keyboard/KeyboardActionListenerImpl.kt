// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.os.Build
import android.text.InputType
import android.util.SparseArray
import android.view.KeyEvent
import android.view.inputmethod.InputMethodSubtype
import android.widget.EditText
import androidx.core.util.forEach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HangulEventDecoder
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.event.HardwareEventDecoder
import helium314.keyboard.event.HardwareKeyboardEventDecoder
import helium314.keyboard.keyboard.internal.LayoutDirective
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.EmojiAltPhysicalKeyDetector
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.combiningRange
import helium314.keyboard.latin.common.moveStepsToCharCount
import helium314.keyboard.latin.define.ProductionFlags
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackgroundGatheringCache
import helium314.keyboard.latin.utils.GestureDataGatheringSettings
import helium314.keyboard.latin.utils.RecapitalizeMode
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.input.AutoTextSuggestionEngine
import helium314.keyboard.sellby.input.SellbyInputRouter
import kotlin.math.abs

// Sellby: lookback window for the Auto-Text suggestion strip's real-app read path (see
// updateAutoTextSuggestions()) - generous enough for the longest realistic multi-word shortcut,
// small enough to stay a cheap, cache-backed RichInputConnection read every keystroke.
private const val SELLBY_AUTOTEXT_LOOKBACK = 60

class KeyboardActionListenerImpl(private val latinIME: LatinIME, private val inputLogic: InputLogic) : KeyboardActionListener {

    private val connection = inputLogic.mConnection
    private val emojiAltPhysicalKeyDetector by lazy { EmojiAltPhysicalKeyDetector(latinIME.resources) }

    // We expect to have only one decoder in almost all cases, hence the default capacity of 1.
    // If it turns out we need several, it will get grown seamlessly.
    private val hardwareEventDecoders: SparseArray<HardwareEventDecoder> = SparseArray(1)

    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private val settings = Settings.getInstance()
    private val audioAndHapticFeedbackManager = AudioAndHapticFeedbackManager.getInstance()

    // language slide state
    private var initialSubtype: InputMethodSubtype? = null
    private var subtypeSwitchCount = 0

    override fun onPressKey(primaryCode: Int, repeatCount: Int, pointerCount: Int, hapticEvent: HapticEvent) {
        metaOnPressKey(primaryCode)
        keyboardSwitcher.onPressKey(primaryCode, pointerCount, sellbyAwareAutoCapsState(), sellbyAwareRecapitalizeState())
        // we need to use LatinIME for handling of key-down audio and haptics
        latinIME.hapticAndAudioFeedback(primaryCode, repeatCount, hapticEvent)
    }

    override fun onLongPressKey(primaryCode: Int) {
        metaOnLongPressKey(primaryCode)
        performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
    }

    override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {
        metaOnReleaseKey(primaryCode)
        keyboardSwitcher.onReleaseKey(primaryCode, withSliding, sellbyAwareAutoCapsState(), sellbyAwareRecapitalizeState())
    }

    /** Sellby: latinIME.currentAutoCapsState/currentRecapitalizeState reflect the REAL app's
     *  InputConnection, which never advances while a Sellby panel field is focused (its text lives
     *  only in that EditText, see SellbyInputRouter) - so "start of sentence" auto-caps would look
     *  permanently stuck, showing shift as always active. Suppress it while a field is focused;
     *  Sellby fields behave like Flutter's own panel fields (manual shift only, no auto-caps). */
    private fun sellbyAwareAutoCapsState(): Int =
        if (SellbyInputRouter.activeField != null) 0 else latinIME.currentAutoCapsState

    private fun sellbyAwareRecapitalizeState(): RecapitalizeMode? =
        if (SellbyInputRouter.activeField != null) null else latinIME.currentRecapitalizeState

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyUp(keyEvent)
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
            return false

        val keyIdentifier = keyEvent.deviceId.toLong() shl 32 + keyEvent.keyCode
        return inputLogic.mCurrentlyPressedHardwareKeys.remove(keyIdentifier)
    }

    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {
        // Sellby: close an open toolbar panel on back - only below API 33 (Build.VERSION_CODES.
        // TIRAMISU). On 33+ this app opts into predictive back (android:enableOnBackInvokedCallback
        // in AndroidManifest.xml), and KeyboardSwitcher.registerSellbyBackCallbackApi33() handles
        // back there instead via OnBackInvokedDispatcher. Confirmed via logcat this onKeyDown path
        // STILL fires on 33+ even though KEYCODE_BACK "interception" is documented as unsupported
        // there - its return value is just ignored by the framework, so BOTH handlers were racing:
        // this one closed the panel first, then the OnBackInvokedCallback ran anyway and found
        // nothing open, hiding the keyboard regardless. Gating this one to <33 removes that race.
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            && keyboardSwitcher.sellbyToolbarView?.closeIfOpen() == true) {
            // Matches the API 33+ path in KeyboardSwitcher.registerSellbyBackCallbackApi33(): back
            // with a panel open should dismiss the whole keyboard along with it, not just step back
            // to the plain toolbar. requestHideSelf() is the same call already used a few lines
            // below for SwipeAction.HIDE_KEYBOARD - nothing new here, just reused. collapseImmediately()
            // forces panelRegion synchronously closed (cancelling closeIfOpen()'s 200ms animation)
            // right before the hide, so the panel can't get left mid-animation and reappear stale
            // next time a field is tapped and the keyboard comes back - same fix as the 33+ path.
            keyboardSwitcher.sellbyToolbarView?.collapseImmediately()
            latinIME.requestHideSelf(0)
            return true
        }
        emojiAltPhysicalKeyDetector.onKeyDown(keyEvent)
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
            return false

        val event: Event
        if (settings.current.mLocale.language == "ko") { // todo: this does not appear to be the right place
            val subtype = keyboardSwitcher.keyboard?.mId?.subtype ?: RichInputMethodManager.getInstance().currentSubtype
            event = HangulEventDecoder.decodeHardwareKeyEvent(subtype, keyEvent) {
                getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
            }
        } else {
            event = getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
        }

        if (event.isHandled) {
            inputLogic.onCodeInput(
                settings.current, event,
                keyboardSwitcher.getKeyboardCapsMode(), // TODO: this is not necessarily correct for a hardware keyboard right now
                keyboardSwitcher.getCurrentKeyboardScript(),
                latinIME.mHandler
            )
            return true
        }
        return false
    }

    override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        // Sellby: a panel EditText (e.g. Settings > Profil Toko) is focused - route characters and
        // backspace straight into it instead of the app's InputConnection. See SellbyInputRouter.
        //
        // The keyboard's own Shift state machine still needs to react normally to this keypress -
        // specifically KeyboardState.updateAlphabetShiftState(), which is what turns Shift back off
        // after one letter (single manual press, not caps-lock) - or Shift visually stays "on"
        // forever after the first letter. That update only happens inside latinIME.onEvent(), which
        // we can't call here because it ALSO commits the character to the app's InputConnection -
        // exactly what SellbyInputRouter just avoided. keyboardSwitcher.onEvent() is the inner call
        // latinIME.onEvent() itself makes for this: it only updates KeyboardState, never touches
        // InputConnection, so it's safe to call directly. (An earlier fix here called
        // metaAfterCodeInput() instead, which turned out to only govern hardware Ctrl/Alt/Fn/Meta -
        // it doesn't touch Shift at all, so it did nothing for this bug.)
        if (SellbyInputRouter.handleCodeInput(primaryCode)) {
            val mkv = keyboardSwitcher.mainKeyboardView
            val event = if (primaryCode in combiningRange)
                Event.createSoftwareDeadEvent(primaryCode, 0, metaState, mkv.getKeyX(x), mkv.getKeyY(y), null)
            else Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
            keyboardSwitcher.onEvent(event, sellbyAwareAutoCapsState(), sellbyAwareRecapitalizeState())
            metaAfterCodeInput(primaryCode)
            return
        }
        when (primaryCode) {
            KeyCode.TOGGLE_AUTOCORRECT -> return settings.toggleAutoCorrect()
            KeyCode.TOGGLE_INCOGNITO_MODE -> {
                settings.toggleAlwaysIncognitoMode()
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                return
            }
            KeyCode.BACKGROUND_GATHERING -> {
                if (BackgroundGatheringCache.isEmpty) {
                    // only enable, no toggle
                    GestureDataGatheringSettings.setBackgroundGatheringEnabled(latinIME.prefs(), true)
                    latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                } else {
                    if (GestureDataGatheringSettings.isDiscardByDefault(latinIME))
                        BackgroundGatheringCache.save(latinIME)
                    else
                        BackgroundGatheringCache.clear()
                }
                return
            }
            KeyCode.BACKGROUND_GATHERING_TEMP_OFF -> {
                GestureDataGatheringSettings.tempDisableBackgroundGathering(latinIME.prefs())
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                return
            }
        }
        if (Settings.getValues().mIsLocked && KeyCode.isIsBlockedWhenLocked(primaryCode))
            return
        val mkv = keyboardSwitcher.mainKeyboardView

        // checking if the character is a combining accent
        val event = if (primaryCode in combiningRange) { // todo: should this be done later, maybe in inputLogic?
            Event.createSoftwareDeadEvent(primaryCode, 0, metaState, mkv.getKeyX(x), mkv.getKeyY(y), null)
        } else {
            // todo:
            //  setting meta shift should only be done for arrow and similar cursor movement keys
            //  should only be enabled once it works more reliably (currently depends on app for some reason)
//            if (mkv.keyboard?.mId?.isAlphabetShiftedManually == true)
//                Event.createSoftwareKeypressEvent(primaryCode, metaState or KeyEvent.META_SHIFT_ON, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
//            else Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
            Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
        }
        latinIME.onEvent(event)
        metaAfterCodeInput(primaryCode)
        updateAutoTextSuggestions()
    }

    override fun onTextInput(text: String?) {
        // Sellby: a panel field can now stay focused while browsing Emoji/Clipboard (see
        // KeyboardSwitcher.setEmojiKeyboard()/setClipboardKeyboard()) - an emoji tap or a tapped
        // Clipboard history entry both commit here, so route into that field the same way
        // onCodeInput() already does for character-at-a-time typing, instead of always landing in
        // the app's InputConnection.
        if (text != null && SellbyInputRouter.handleTextInput(text)) return
        latinIME.onTextInput(text)
        updateAutoTextSuggestions()
    }

    /** Sellby: Auto-Text typing-triggered suggestion strip (sellby_keyboard.dart's
     *  _buildAutoTextSuggestionToolbar) - checked after every keystroke that could have changed the
     *  currently-focused REAL app text. By request, no Sellby panel field participates - nothing in
     *  any panel currently consumes shortcuts while filling out a form field, so skip entirely
     *  while one is focused (SellbyInputRouter.focus()/unfocus() already clear any strip that was
     *  showing from a previous real-app match when a panel field gains/loses focus). */
    private fun updateAutoTextSuggestions() {
        if (SellbyInputRouter.activeField != null) return
        val raw = connection.getTextBeforeCursor(SELLBY_AUTOTEXT_LOOKBACK, 0)?.toString().orEmpty()
        val query = raw.trim()
        if (query.isEmpty()) {
            keyboardSwitcher.sellbyToolbarView?.updateAutoTextSuggestions(emptyList(), true) {}
            return
        }
        val matches = AutoTextSuggestionEngine.matchesForRealApp(query)
        keyboardSwitcher.sellbyToolbarView?.updateAutoTextSuggestions(matches, false) { item ->
            val resolved = AutoTextSuggestionEngine.resolveTokens(latinIME, item.message)
            connection.beginBatchEdit()
            connection.finishComposingText()
            connection.deleteTextBeforeCursor(query.length)
            connection.commitText(resolved, 1)
            connection.endBatchEdit()
        }
    }

    override fun onContent(content: InputContentInfoCompat) {
        val editorInfo = latinIME.currentInputEditorInfo
        val editorMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        if (editorMimeTypes.any { content.description.hasMimeType(it) }) {
            connection.commitContent(content, editorInfo)
        } else if (editorMimeTypes.isEmpty()) { // make the fallback optional?
            latinIME.clipboardHistoryManager.pasteWithoutChangingClips(content)
        }
    }

    override fun onStartBatchInput() = latinIME.onStartBatchInput()

    override fun onUpdateBatchInput(batchPointers: InputPointers?) = latinIME.onUpdateBatchInput(batchPointers)

    override fun onEndBatchInput(batchPointers: InputPointers?) = latinIME.onEndBatchInput(batchPointers)

    override fun onCancelBatchInput() = latinIME.onCancelBatchInput()

    // User released a finger outside any key
    override fun onCancelInput() { }

    override fun onFinishSlidingInput() =
        keyboardSwitcher.onFinishSlidingInput(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)

    override fun onCustomRequest(request: KeyboardActionListener.CustomAction) = when (request) {
        KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER -> latinIME.showInputPickerDialog()
        KeyboardActionListener.CustomAction.TOUCHPAD_ON -> {
            keyboardSwitcher.mainKeyboardView?.alpha = 0.5f
            true
        }
        KeyboardActionListener.CustomAction.TOUCHPAD_OFF -> {
            keyboardSwitcher.mainKeyboardView?.alpha = 1f
            true
        }
        KeyboardActionListener.CustomAction.PERFORM_HAPTIC -> {
            performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
            true
        }
    }

    override fun onHorizontalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeHorizontal) {
        KeyboardActionListener.SwipeAction.MOVE_CURSOR -> onMoveCursorHorizontally(steps)
        KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD -> {
            toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.TOGGLE_DPAD -> {
            toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        else -> false
    }

    override fun onVerticalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeVertical) {
        KeyboardActionListener.SwipeAction.MOVE_CURSOR -> onMoveCursorVertically(steps)
        KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD -> {
            toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.TOGGLE_DPAD -> {
            toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.HIDE_KEYBOARD -> {
            latinIME.requestHideSelf(0)
            true
        }
        KeyboardActionListener.SwipeAction.TOUCHPAD_MODE -> {
            // Activate touchpad mode - the actual cursor movement will be handled in PointerTracker

            // Activation and ensure enough room for navigation.
            val requiredSteps = 8

            if (abs(steps) >= requiredSteps) {
                TouchpadHandler.setTouchpadModeActive(true)
                true
            } else {
                false
            }
        }
        else -> false
    }

    override fun onEndSpaceSwipe() {
        initialSubtype = null
        subtypeSwitchCount = 0
    }

    override fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        keyboardSwitcher.toggleLayout(layout, autoCapsFlags, recapitalizeMode)
    }

    override fun onLongPressAlphaSymbolForNumpad() {
        keyboardSwitcher.onLongPressAlphaSymbolForNumpad()
    }

    override fun onLongPressUtilityLayout(layout: LayoutDirective.Utility) {
        keyboardSwitcher.onLongPressUtilityLayout(layout)
    }

    override fun onMoveDeletePointer(steps: Int) {
        // Sellby: hold-backspace-and-slide (fast delete) - same "operate on the app's real
        // InputConnection, which never advances for a Sellby field" gap as the other gestures.
        // Extend the FIELD's own selection instead; PointerTracker sends steps incrementally
        // (see mStartX += steps * sPointerStep before this call), so reading the field's current
        // selection fresh each call and nudging it is the direct equivalent of the
        // expectedSelectionStart-based math below.
        SellbyInputRouter.activeField?.let { field ->
            val text = field.text ?: return
            val end = field.selectionEnd.coerceIn(0, text.length)
            val actualSteps = sellbyActualSteps(field, steps)
            val start = (field.selectionStart + actualSteps).coerceIn(0, text.length)
            if (start > end) return
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
            field.setSelection(start, end)
            return
        }
        inputLogic.finishInput()
        val end = connection.expectedSelectionEnd
        val actualSteps = actualSteps(steps)
        val start = connection.expectedSelectionStart + actualSteps
        if (start > end) return
        gestureMoveBackHaptics()
        connection.setSelection(start, end)
    }

    private fun actualSteps(steps: Int): Int {
        val text = if (steps > 0) connection.getSelectedText(0) ?: return steps
        else connection.getTextBeforeCursor(-steps * 4, 0) ?: return steps
        return moveStepsToCharCount(text, steps)
    }

    private fun sellbyActualSteps(field: EditText, steps: Int): Int {
        val text = field.text ?: return steps
        val start = field.selectionStart.coerceIn(0, text.length)
        val end = field.selectionEnd.coerceIn(0, text.length)
        val basis = if (steps > 0) text.subSequence(start, end) else text.subSequence(0, start)
        return moveStepsToCharCount(basis, steps)
    }

    override fun onUpWithDeletePointerActive() {
        // Sellby: mirrors onCodeInput's own routing - if a panel field is focused, check/consume
        // its own selection instead of the app's (which never had one, since we never touched it).
        SellbyInputRouter.activeField?.let { field ->
            if (field.selectionStart == field.selectionEnd) return
            onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            return
        }
        if (!connection.hasSelection()) return
        inputLogic.finishInput()
        onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun resetMetaState() {
        metaState = 0
    }

    private fun onLanguageSlide(steps: Int): Boolean {
        if (abs(steps) < settings.current.mLanguageSwipeDistance) return false
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size <= 1) { // only allow if we have more than one subtype
            return false
        }
        // decide next or previous dependent on up or down
        val current = RichInputMethodManager.getInstance().currentSubtype.rawSubtype
        var wantedIndex = subtypes.indexOf(current) + if (steps > 0) 1 else -1
        wantedIndex %= subtypes.size
        if (wantedIndex < 0) {
            wantedIndex += subtypes.size
        }
        val newSubtype = subtypes[wantedIndex]

        // do not switch if we would switch to the initial subtype after cycling all other subtypes
        if (initialSubtype == null) initialSubtype = current
        if (initialSubtype == newSubtype) {
            if ((subtypeSwitchCount > 0 && steps > 0) || (subtypeSwitchCount < 0 && steps < 0)) {
                return true
            }
        }
        if (steps > 0) subtypeSwitchCount++ else subtypeSwitchCount--

        keyboardSwitcher.switchToSubtype(newSubtype)
        return true
    }

    private fun onMoveCursorVertically(steps: Int): Boolean {
        if (steps == 0) return false
        val code = if (steps < 0) {
            gestureMoveBackHaptics()
            KeyCode.ARROW_UP
        } else {
            gestureMoveForwardHaptics()
            KeyCode.ARROW_DOWN
        }
        onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        return true
    }

    private fun onMoveCursorHorizontally(rawSteps: Int): Boolean {
        if (rawSteps == 0) return false
        // for RTL languages we want to invert pointer movement
        val rtl = RichInputMethodManager.getInstance().currentSubtype.isRtlSubtype
        val steps = if (rtl) -rawSteps else rawSteps
        // Sellby: spacebar-slide cursor move - same gap as onMoveDeletePointer, this moved the
        // real app's cursor via InputConnection, which does nothing useful while typing into a
        // Sellby field. moveStepsToCharCount() only needs a CharSequence, so it works directly on
        // the field's own text.
        SellbyInputRouter.activeField?.let { field ->
            val text = field.text ?: return false
            val cursor = field.selectionStart.coerceIn(0, text.length)
            val moveSteps = if (steps < 0) moveStepsToCharCount(text.subSequence(0, cursor), steps)
                else moveStepsToCharCount(text.subSequence(cursor, text.length), steps)
            if (moveSteps == 0) return false
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
            field.setSelection((cursor + moveSteps).coerceIn(0, text.length))
            return true
        }
        val moveSteps: Int
        if (steps < 0) {
            val text = connection.getTextBeforeCursor(-steps * 4, 0) ?: return false
            moveSteps = moveStepsToCharCount(text, steps)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(-steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_RIGHT else KeyCode.ARROW_LEFT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveBackHaptics()
                }
                return true
            }
            gestureMoveBackHaptics()
        } else {
            val text = connection.getTextAfterCursor(steps * 4, 0) ?: return false
            moveSteps = moveStepsToCharCount(text, steps)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_LEFT else KeyCode.ARROW_RIGHT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveForwardHaptics(true)
                }
                return true
            }
            gestureMoveForwardHaptics(text.isNotEmpty())
        }
        inputLogic.setExpectCursorMove()

        // the shortcut below causes issues due to horrible handling of text fields by Firefox and forks
        // issues:
        //  * setSelection "will cause the editor to call onUpdateSelection", see: https://developer.android.com/reference/android/view/inputmethod/InputConnection#setSelection(int,%20int)
        //     but Firefox is simply not doing this within the same word... WTF?
        //     https://github.com/HeliBorg/HeliBoard/issues/1139#issuecomment-2588169384
        //  * inputType is NOT of variant InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT (variant appears to always be 0)
        //     -> this is "fixed" now using AppWorkarounds.adjustInputType
        val variation = InputType.TYPE_MASK_VARIATION and Settings.getValues().mInputAttributes.mInputType
        if (variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                && inputLogic.moveCursorByAndReturnIfInsideComposingWord(moveSteps)) {
            // no need to finish input and restart suggestions if we're still in the word
            // this is a noticeable performance improvement when moving through long words
            val newPosition = connection.expectedSelectionStart + moveSteps
            connection.setSelection(newPosition, newPosition)
            return true
        }

        inputLogic.finishInput()
        val newPosition = connection.expectedSelectionStart + moveSteps
        connection.setSelection(newPosition, newPosition)
        inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current, keyboardSwitcher.currentKeyboardScript)
        return true
    }

    private fun gestureMoveBackHaptics() {
        if (connection.canDeleteCharacters()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    // hasTextAfterCursor is used because text before the cursor is cached, going through the InputConnection can be slow
    private fun gestureMoveForwardHaptics(hasTextAfterCursor: Boolean? = null) {
        if (hasTextAfterCursor ?: connection.hasTextAfterCursor()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    private fun performHapticFeedback(hapticEvent: HapticEvent) {
        audioAndHapticFeedbackManager.performHapticFeedback(keyboardSwitcher.visibleKeyboardView, hapticEvent)
    }

    private fun getHardwareKeyEventDecoder(deviceId: Int): HardwareEventDecoder {
        hardwareEventDecoders.get(deviceId)?.let { return it }

        // TODO: create the decoder according to the specification
        val newDecoder = HardwareKeyboardEventDecoder(deviceId)
        hardwareEventDecoders.put(deviceId, newDecoder)
        return newDecoder
    }

    // -------------------------- meta state handling -----------------------------

    // current state
    // press enables meta
    // release keeps meta enabled, unless there was a onCodeInput for a different key in between
    // onCodeInput ends the meta if it was enabled
    // long press on meta key also ends meta so popups are handled properly
    // sliding from a meta key to some other words too, though this was not intended (and there are no sliding key input graphics)

    // todo: move meta state tracking to KeyboardState? seems more suitable, also for handling sliding input
    //  but the issue is that meta state is used in Event to determine whether it's a functional Event (does not add a character)
    //  (and also it's in the hardware keyEvents which are handled by onKeyUp/Down, but that should be manageable)

    /** actual Android metaState like in KeyEvent */
    private var metaState = 0

    /** keeps track of the state of meta keys by (HeliBoard) KeyCodes */
    private val metaPressStates = SparseArray<MetaPressState>(4)

    // todo: lock and non-lock versions interact badly: when any of them is released, the meta state is removed
    //  this is not wanted, especially because the state of the other key is not affected (still looks pressed)
    private fun metaOnPressKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        if (primaryCode.isMetaLock()) {
            // if unset -> lock, otherwise set to UNSET_ON_RELEASE so it's unset on release
            if (metaPressStates[primaryCode] != MetaPressState.LOCKED) {
                metaPressStates[primaryCode] = MetaPressState.LOCKED
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
                metaState = metaState or metaCode
            } else {
                metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
            }
            return
        }
        if (metaPressStates[primaryCode] == MetaPressState.RELEASED_BUT_ACTIVE) {
            // meta key is pressed again without other input -> should be disabled on release
            metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
        } else {
            // otherwise just press it normally
            metaPressStates[primaryCode] = MetaPressState.PRESSED
        }
        metaState = metaState or metaCode
        // pressed graphics are set anyway, no need to lock it
    }

    // looks like this is not called if there are no popups
    private fun metaOnLongPressKey(primaryCode: Int) {
        if (metaPressStates[primaryCode] != MetaPressState.PRESSED) return
        // we long-pressed a meta key that has popups -> disable so the meta state is not used for the popup
        metaPressStates[primaryCode] = MetaPressState.UNSET
        keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        val metaCode = primaryCode.toMetaState() ?: return
        metaState = metaState and metaCode.inv()
    }

    private fun metaOnReleaseKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        val metaPressState = metaPressStates[primaryCode]
        if (metaPressState == MetaPressState.UNSET_ON_RELEASE) {
            metaPressStates[primaryCode] = MetaPressState.UNSET
            metaState = metaState and metaCode.inv()
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        } else if (metaPressState == MetaPressState.PRESSED) {
            metaPressStates[primaryCode] = MetaPressState.RELEASED_BUT_ACTIVE
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
        }
    }

    private fun metaAfterCodeInput(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState()
        if (metaCode != null) {
            // meta key might be a popup key, we just toggle between set and unset
            val metaPressState = metaPressStates[primaryCode] ?: MetaPressState.UNSET
            if (metaPressState == MetaPressState.UNSET) {
                metaPressStates[primaryCode] = MetaPressState.SET
                metaState = metaState or metaCode
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
            } else if (metaPressState == MetaPressState.SET) {
                metaPressStates[primaryCode] = MetaPressState.UNSET
                metaState = metaState and metaCode.inv()
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
            }
        } else if (metaState != 0) {
            // non-meta key -> unset all set / released_but_active, and mark pressed as UNSET_ON_RELEASE
            metaPressStates.forEach { key, value ->
                if (value == MetaPressState.RELEASED_BUT_ACTIVE || value == MetaPressState.SET) {
                    metaPressStates[key] = MetaPressState.UNSET
                    keyboardSwitcher.mainKeyboardView?.updateLockState(key, false)
                    val metaCode = key.toMetaState() ?: return@forEach
                    metaState = metaState and metaCode.inv()
                } else if (value == MetaPressState.PRESSED) {
                    metaPressStates[key] = MetaPressState.UNSET_ON_RELEASE
                }
            }
        }
    }

    companion object {
        private enum class MetaPressState {
            UNSET, // default state, not active
            SET, // enabled without onPressKey (e.g. in popup)
            PRESSED, // key is pressed
            UNSET_ON_RELEASE, // key is pressed, but state will be unset on release
            RELEASED_BUT_ACTIVE, // key was released without UNSET_ON_RELEASE state, meta state is still set
            LOCKED, // key is locked and will be released only by pressing the same key again
        }

        private fun Int.toMetaState() = when (this) {
            KeyCode.CTRL, KeyCode.CTRL_LOCK -> KeyEvent.META_CTRL_ON
            KeyCode.CTRL_LEFT               -> KeyEvent.META_CTRL_LEFT_ON
            KeyCode.CTRL_RIGHT              -> KeyEvent.META_CTRL_RIGHT_ON
            KeyCode.ALT, KeyCode.ALT_LOCK   -> KeyEvent.META_ALT_ON
            KeyCode.ALT_LEFT                -> KeyEvent.META_ALT_LEFT_ON
            KeyCode.ALT_RIGHT               -> KeyEvent.META_ALT_RIGHT_ON
            KeyCode.FN, KeyCode.FN_LOCK     -> KeyEvent.META_FUNCTION_ON
            KeyCode.META, KeyCode.META_LOCK -> KeyEvent.META_META_ON
            KeyCode.META_LEFT               -> KeyEvent.META_META_LEFT_ON
            KeyCode.META_RIGHT              -> KeyEvent.META_META_RIGHT_ON
            else -> null
        }

        private fun Int.isMetaLock() = this == KeyCode.CTRL_LOCK || this == KeyCode.ALT_LOCK || this == KeyCode.FN_LOCK || this == KeyCode.META_LOCK
    }
}
