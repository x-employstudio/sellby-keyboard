// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.input

import android.text.method.SingleLineTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import kotlin.math.abs

/**
 * Android port of sellby_keyboard.dart's "readOnly TextField + manually tracked active field"
 * pattern (settings_panel.dart's `_buildInputBox`/`_focusField`/`_activeFieldController`), used by
 * every Flutter panel that has text input. There, fields never request a real soft keyboard - a
 * single "active field" variable is tracked at the panel level, and physical key taps write
 * straight into that field's controller.
 *
 * The Android equivalent is simpler because the platform already gives us most of it for free:
 * [EditText.enableSellbyRouting] turns off `showSoftInputOnFocus` (the real Android API that stops
 * an EditText from popping the system IME) and lets normal Android focus/cursor/selection handling
 * do the rest - no manual "isFocused"/"showCursor" bookkeeping needed like in Flutter. Only the
 * "which field do physical keys write into" part needs a manual singleton, which is this object.
 *
 * [KeyboardActionListenerImpl.onCodeInput] calls [handleCodeInput] before its normal switch/dispatch;
 * when it returns true the key was consumed here and never reaches `LatinIME.onEvent`/InputConnection,
 * so app text input is untouched whenever no Sellby panel field is focused.
 */
object SellbyInputRouter {

    var activeField: EditText? = null
        private set

    fun focus(field: EditText, isNumeric: Boolean = false) {
        activeField = field
        KeyboardSwitcher.getInstance().setSellbyNumericLayout(isNumeric)
        // The Auto-Text suggestion strip only ever watches real-app typing (see
        // AutoTextSuggestionEngine) - if one was showing from before this panel field gained
        // focus, clear it immediately rather than leaving it stuck since no further keystroke here
        // will ever re-check/hide it (Sellby field typing is fully routed away from that check).
        KeyboardSwitcher.getInstance().sellbyToolbarView?.clearAutoTextSuggestions()
    }

    fun unfocus() {
        activeField = null
        KeyboardSwitcher.getInstance().setSellbyNumericLayout(false)
        // Prevents a stale Auto-Text suggestion strip (see AutoTextSuggestionEngine) from lingering
        // when a field loses focus without another keystroke following - e.g. navigating a panel's
        // form back to its list, switching tabs, or closing the panel. unfocus() already runs at
        // every one of those points, so this piggybacks on it instead of needing a new hook.
        KeyboardSwitcher.getInstance().sellbyToolbarView?.clearAutoTextSuggestions()
    }

    /** BUG FIX: KeyboardSwitcher.commitSellbyText()/commitSellbyContent() need [activeField] cleared
     *  right before committing generated text (e.g. an Invoice) to the real app, so it can never be
     *  swallowed into whichever panel field last held Android focus (see handleTextInput below) - but
     *  calling the FULL unfocus() there was itself the cause of a "keyboard goes black" regression:
     *  unfocus()'s setSellbyNumericLayout(false) can trigger a real keyboard layout switch
     *  (Numpad -> Alphabet), and firing that synchronously mid-generate, immediately followed by the
     *  panel-close height animation the caller runs right after, raced with it - the same class of bug
     *  as the (reverted) root-overlay incident: two view/layout transitions landing back to back.
     *  This does ONLY the one thing that's actually needed at that exact moment (make
     *  handleTextInput() return false) and nothing else. The field's real Android focus is left
     *  untouched, so the normal focus-loss path already fires a full unfocus() (numeric-layout reset,
     *  autotext-strip clear included) a moment later anyway, once the panel-close animation actually
     *  hides it - same as it always did before commitSellbyText existed, just deferred to its usual
     *  timing instead of forced early. */
    fun clearActiveFieldForAppCommit() {
        activeField = null
    }

    /** Returns true if [primaryCode] was a character or backspace and got routed into [activeField]. */
    fun handleCodeInput(primaryCode: Int): Boolean {
        val field = activeField ?: return false
        // Enter on a SINGLE-LINE field moves to the next field instead of inserting a literal "\n"
        // (which is otherwise what happens - CODE_ENTER's code point passes the CHARACTERS_MIN
        // check below same as any other character). Multi-line fields (Auto-Text's Pesan, Produk's
        // Deskripsi) are untouched - isSingleLine is false there, so this branch never applies and
        // Enter keeps adding real newlines as before. focusSearch(FOCUS_DOWN) is plain Android focus
        // navigation, not a hand-maintained "next field" chain - it finds whichever focusable view
        // is positioned below in the current layout, so this works for any panel's form without
        // needing each field wired to a specific "next" target. Only EditText fields end up
        // isFocusableInTouchMode (set by enableSellbyRouting below) - regular buttons/pills in this
        // app are isFocusable but NOT isFocusableInTouchMode, so focusSearch (which honors touch-mode
        // focusability) skips them and only ever lands on another Sellby-routed field or null.
        // (TextView.isSingleLine() only exists from API 29 - calling it threw NoSuchMethodError on Android 6-9,
        // so a single-line field is recognised by what setSingleLine(true) installs: its transformation method.)
        if (primaryCode == Constants.CODE_ENTER && field.transformationMethod is SingleLineTransformationMethod) {
            (field.focusSearch(View.FOCUS_DOWN) as? EditText)?.requestFocus()
            return true
        }
        val editable = field.text ?: return false
        val start = field.selectionStart.coerceIn(0, editable.length)
        val end = field.selectionEnd.coerceIn(0, editable.length)
        if (primaryCode >= KeyCode.Spec.CHARACTERS_MIN) {
            editable.replace(minOf(start, end), maxOf(start, end), String(Character.toChars(primaryCode)))
            return true
        }
        if (primaryCode == KeyCode.DELETE) {
            if (start != end) {
                editable.delete(minOf(start, end), maxOf(start, end))
            } else if (start > 0) {
                var deleteStart = start - 1
                if (deleteStart > 0 && Character.isLowSurrogate(editable[deleteStart]) && Character.isHighSurrogate(editable[deleteStart - 1]))
                    deleteStart--
                editable.delete(deleteStart, start)
            }
            return true
        }
        return false
    }

    /** Multi-character counterpart to [handleCodeInput] - same routing, for text committed as a
     *  whole string rather than one key code at a time (an emoji tap or a Clipboard history entry,
     *  both of which go through KeyboardActionListener.onTextInput() rather than onCodeInput()).
     *  Returns true if [text] was routed into [activeField] instead of the app. */
    fun handleTextInput(text: String): Boolean {
        val field = activeField ?: return false
        val editable = field.text ?: return false
        val start = field.selectionStart.coerceIn(0, editable.length)
        val end = field.selectionEnd.coerceIn(0, editable.length)
        editable.replace(minOf(start, end), maxOf(start, end), text)
        return true
    }
}

/**
 * Wires this EditText into [SellbyInputRouter]: suppresses the system soft keyboard and registers
 * it as the active field whenever it has Android focus (and clears it on focus loss, which also
 * fires automatically when the view is detached or made GONE - e.g. switching Settings sub-screens
 * or toolbar tabs - so no separate cleanup call is needed in the common case).
 */
fun EditText.enableSellbyRouting(isNumeric: Boolean = false, onFocusChanged: ((Boolean) -> Unit)? = null) {
    showSoftInputOnFocus = false
    isFocusableInTouchMode = true
    // A focused EditText normally claims every touch gesture on it (for cursor placement), which
    // blocks the panel's surrounding ScrollView from scrolling while that field has focus. Release
    // the claim once a touch clearly becomes a vertical drag past touch-slop, so the panel scrolls
    // normally again; a plain tap still reaches the EditText's own touch handling untouched (this
    // listener never consumes the event, it only toggles what the ancestor is allowed to intercept).
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var downY = 0f
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.y
            MotionEvent.ACTION_MOVE -> if (abs(event.y - downY) > touchSlop) parent?.requestDisallowInterceptTouchEvent(false)
        }
        false
    }
    setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) SellbyInputRouter.focus(this, isNumeric)
        else if (SellbyInputRouter.activeField === this) SellbyInputRouter.unfocus()
        onFocusChanged?.invoke(hasFocus)
    }
}
