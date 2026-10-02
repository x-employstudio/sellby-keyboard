/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewKt;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.calculator.CalculatorView;
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.LayoutDirective;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.keyboard.toolbar.SellbyToolbarView;
import helium314.keyboard.keyboard.internal.ShiftMode;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.CapsMode;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.sellby.input.SellbyInputRouter;
import helium314.keyboard.sellby.ui.ContactPickerActivity;
import helium314.keyboard.sellby.ui.QrisPhotoPickerActivity;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsKt;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

public final class KeyboardSwitcher {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private InputView mCurrentInputView;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView;
    private LinearLayout mClipboardStripView;
    private HorizontalScrollView mClipboardStripScrollView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private ClipboardHistoryView mClipboardHistoryView;
    private CalculatorView mCalculatorView;
    private View mCalculatorStripView;
    private View mToolbarStack;
    private SellbyToolbarView mSellbyToolbarView;
    private boolean mSellbyBackCallbackRegistered;
    private TextView mFakeToastView;
    private ImageView mBackgroundGatheringIndicator;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(new SwitchActions());
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
        registerQrisPhotoPickedReceiverOnce();
        registerContactPickedReceiverOnce();
        disableUnusedHeliboardCorrectionFeaturesOnce();
        applySellbySpacebarTextDefaultOnce();
    }

    private boolean mQrisPhotoPickedReceiverRegistered = false;
    private boolean mContactPickedReceiverRegistered = false;

    /** Sellby: receives QrisPhotoPickerActivity's broadcast result (same "Activity launched from
     *  the IME, result via local broadcast" pattern EmojiSearchActivity already proved safe -
     *  see LatinIME's own mEmojiSearchReceiver registration for the precedent). Registered here
     *  (KeyboardSwitcher, a persistent singleton) instead of LatinIME.java, which must never be
     *  edited - mLatinIME is only USED as the Context to register with, not modified. Guarded to
     *  run once since initInternal() could in principle run more than once per process. */
    private void registerQrisPhotoPickedReceiverOnce() {
        if (mQrisPhotoPickedReceiverRegistered) return;
        mQrisPhotoPickedReceiverRegistered = true;
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String paymentMethodId = intent.getStringExtra(QrisPhotoPickerActivity.EXTRA_PAYMENT_METHOD_ID);
                final String resultPath = intent.getStringExtra(QrisPhotoPickerActivity.EXTRA_RESULT_PATH);
                if (paymentMethodId == null || mSellbyToolbarView == null) return;
                mSellbyToolbarView.notifyQrisPhotoPicked(paymentMethodId, resultPath);
            }
        };
        final IntentFilter filter = new IntentFilter(QrisPhotoPickerActivity.QRIS_PHOTO_PICKED_ACTION);
        ContextCompat.registerReceiver(mLatinIME, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** Sellby: receives ContactPickerActivity's broadcast result - Invoice's contact button picks
     *  straight from the phone's own Contacts (a deliberate deviation from invoice_panel.dart's
     *  _pickContactFromAndroid(), which despite its name actually reads Sellby's own saved
     *  customer list - confirmed with the user this is the wanted behavior). Same "persistent
     *  singleton registers the receiver, mLatinIME only used as Context" pattern as
     *  registerQrisPhotoPickedReceiverOnce() above. */
    private void registerContactPickedReceiverOnce() {
        if (mContactPickedReceiverRegistered) return;
        mContactPickedReceiverRegistered = true;
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String name = intent.getStringExtra(ContactPickerActivity.EXTRA_NAME);
                final String phone = intent.getStringExtra(ContactPickerActivity.EXTRA_PHONE);
                if (name == null || phone == null || mSellbyToolbarView == null) return;
                mSellbyToolbarView.notifyContactPicked(name, phone);
            }
        };
        final IntentFilter filter = new IntentFilter(ContactPickerActivity.CONTACT_PICKED_ACTION);
        ContextCompat.registerReceiver(mLatinIME, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private static final String PREF_SELLBY_STOCK_CORRECTION_FEATURES_DISABLED = "sellby_stock_correction_features_disabled_v1";

    /** Sellby: HeliBoard's stock suggestion/autocorrect/prediction pipeline defaults ON
     *  (Defaults.kt: PREF_AUTO_CORRECTION, PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION,
     *  PREF_SHOW_SUGGESTIONS, PREF_KEY_USE_PERSONALIZED_DICTS, PREF_BIGRAM_PREDICTIONS are all
     *  `true`) - harmless on stock HeliBoard, which has a suggestion-strip UI to see/control it, but
     *  Sellby's suggestion strip is permanently replaced by its own 6-tab toolbar (setMainKeyboardFrame())
     *  with no user-facing way to see or turn any of this off - it was running invisibly, occasionally
     *  "fixing" text out from under the user (reported: words silently capitalized like a name -
     *  PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION applying a capitalized dictionary match - and
     *  autocorrect changing a just-typed word mid-sentence) - exactly the class of "unused HeliBoard
     *  feature quietly still active" this app should never have.
     *  Force-written ONCE per install (guarded by its own marker pref, same pattern as the QRIS/
     *  contact receiver guards above) rather than just flipping the Defaults.kt values: a compiled-in
     *  default only applies to a SharedPreferences key that was NEVER written - an existing install
     *  (like a device mid-testing) already has these persisted as their old `true` values and
     *  wouldn't pick up a new default on its own. */
    private void disableUnusedHeliboardCorrectionFeaturesOnce() {
        final SharedPreferences prefs = KtxKt.prefs(mLatinIME);
        if (prefs.getBoolean(PREF_SELLBY_STOCK_CORRECTION_FEATURES_DISABLED, false)) return;
        prefs.edit()
                .putBoolean(Settings.PREF_AUTO_CORRECTION, false)
                .putBoolean(Settings.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION, false)
                .putBoolean(Settings.PREF_SHOW_SUGGESTIONS, false)
                .putBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, false)
                .putBoolean(Settings.PREF_BIGRAM_PREDICTIONS, false)
                .putBoolean(PREF_SELLBY_STOCK_CORRECTION_FEATURES_DISABLED, true)
                .apply();
    }

    private static final String PREF_SELLBY_SPACEBAR_TEXT_DEFAULT_APPLIED = "sellby_spacebar_text_default_applied_v1";

    /** Sellby: sets the "Sellby" spacebar watermark text exactly ONCE per install, same
     *  guarded-once pattern as disableUnusedHeliboardCorrectionFeaturesOnce() above - the new
     *  Defaults.kt default only reaches a FRESH install; a device already running through this
     *  session's earlier rounds may already have Settings.PREF_SPACE_BAR_TEXT persisted as the
     *  old empty string, which a changed compiled-in default alone wouldn't override. Uses
     *  HeliBoard's own existing stock mechanism unmodified (LanguageOnSpacebarUtils /
     *  MainKeyboardView.drawLanguageOnSpacebar) - this is only a default-value migration, not a
     *  new feature. */
    private void applySellbySpacebarTextDefaultOnce() {
        final SharedPreferences prefs = KtxKt.prefs(mLatinIME);
        if (prefs.getBoolean(PREF_SELLBY_SPACEBAR_TEXT_DEFAULT_APPLIED, false)) return;
        prefs.edit()
                .putString(Settings.PREF_SPACE_BAR_TEXT, "Sellby")
                .putBoolean(PREF_SELLBY_SPACEBAR_TEXT_DEFAULT_APPLIED, true)
                .apply();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes);
            if (mKeyboardView != null) {
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
                // BUG FIX (this is the actual root cause of the "keyboard shows as a black/blank
                // area sized like the keyboard" bug, reproduced by a host app like WhatsApp hiding
                // then rapidly re-showing the IME via its own UI toggle - onCreateInputView() above
                // just built a BRAND NEW mKeyboardView with no Keyboard object assigned yet
                // (getKeyboard() == null) - something still has to call setKeyboard() on it before
                // anything can be drawn. Normally that happens moments later, back in
                // LatinIME#onStartInputViewInternal(): for a "restarting" call on the SAME field
                // (exactly this scenario - same app, same field, just hidden then re-shown) it
                // takes the resetKeyboardStateToAlphabet() path rather than the full reload path,
                // which is KeyboardState#resetToAlpha()'s deliberate, normally-correct optimization
                // to skip a disruptive full reload when "we're already in alphabet mode" - but mState
                // (KeyboardState) is a single persistent object for this IME's whole lifetime (see
                // initInternal(), created once, independent of onCreateInputView()'s reinflates), so
                // its cached "already alphabet" state has no idea a reinflate JUST happened and a
                // brand new, still-empty keyboard view is waiting - so that optimization ends up
                // skipping the one load this particular view actually still needs, and it silently
                // never gets a Keyboard for the rest of its lifetime (until some unrelated layout
                // switch happens to load one, e.g. tapping the app's own emoji toggle again, which
                // is exactly why a second, seemingly-unrelated trigger "fixed" it in testing).
                // Calling the same loadKeyboard() InputLogic.java itself already calls elsewhere
                // (setInlineEmojiSearchAction()) guarantees a Keyboard is assigned to the fresh view
                // right here, independent of - and before - whatever onStartInputViewInternal's
                // later branching decides to do (redundant in the common case where that later
                // logic reloads anyway - loadKeyboard() is a plain rebuild, safe to call twice).
                loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                        mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
            }
        } else if (mCurrentInputView != null && mLatinIME.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context, final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            KeyboardLayoutSet.Companion.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(EditorInfo editorInfo, SettingsValues settingsValues, int currentAutoCapsState,
                             @Nullable RecapitalizeMode currentRecapitalizeState, KeyboardLayoutSet.InternalAction internalAction) {
        KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(mThemeContext, editorInfo, settingsValues);
        int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        int keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues);
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.getCurrentSubtype())
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.mOneHandedModeEnabled);
        } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.getKeyboardId(), e.getCause());
            try {
                InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE.createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.getKeyboardId(), e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mState.onSaveKeyboardState();
        }
    }

    // Sellby: how long (uptime ms) after launching one of our own helper Activities (QRIS photo
    // picker, contact picker) the IME hide that launch causes must NOT close the open panel - the
    // user is coming straight back to it. See keepSellbyPanelThroughNextHide().
    private static final long SELLBY_HELPER_LAUNCH_GRACE_MS = 6000;
    private long mSellbyKeepPanelUntilUptimeMs = 0;

    /** Call right before launching a Sellby helper Activity that returns to the same panel (QRIS
     *  photo picker, Invoice contact picker). Launching it hides the keyboard like any app switch
     *  does, and onHideWindow() would otherwise close the panel the user is about to come back to. */
    public void keepSellbyPanelThroughNextHide() {
        mSellbyKeepPanelUntilUptimeMs = SystemClock.uptimeMillis() + SELLBY_HELPER_LAUNCH_GRACE_MS;
    }

    public void onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
        // Sellby: the keyboard going away for ANY reason (system back/gesture, switching to another
        // app, tapping outside, ...) closes the open panel. LatinIME.hideWindow() calls this on every
        // hide, whatever triggered it - unlike the back callback above, which only sees the back
        // gesture and not even that on every device. Without this the panel's state outlived the
        // hide, so the keyboard came back (in the same or another app) with the old panel still open
        // on top of the keys, blocking the chat.
        if (mSellbyToolbarView != null && SystemClock.uptimeMillis() > mSellbyKeepPanelUntilUptimeMs) {
            mSellbyToolbarView.collapseImmediately();
        }
    }

    @Nullable public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet() {
        mState.onResetKeyboardStateToAlphabet(mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState());
    }

    public void onPressKey(int code, int pointerCount, int currentAutoCapsState,
            @Nullable RecapitalizeMode currentRecapitalizeState) {
        mState.onPressKey(code, pointerCount, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    public void setEmojiKeyboard() {
        mState.setLayout(LayoutDirective.Utility.EMOJI);
    }

    public void setClipboardKeyboard() {
        mState.setLayout(LayoutDirective.Utility.CLIPBOARD);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        final int visibility = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState) ? View.GONE : View.VISIBLE;
        // Sellby: the stock HeliBoard suggestion/toolbar strip is fully replaced by the Sellby
        // toolbar row (sellby_toolbar_row, inside mToolbarStack) - never shown in Letters/Symbols/
        // Numpad mode anymore. Emoji/Clipboard/Calculator still manage mStripContainer themselves
        // for their own clipboard_strip/calculator_strip children, untouched here.
        mStripContainer.setVisibility(View.GONE);
        PointerTracker.switchTo(mKeyboardView);
        // The visibility of {@link #mToolbarStack} (which now wraps mKeyboardView, the Sellby
        // toolbar row and the panel region) must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mToolbarStack.setVisibility(visibility);
        mMainKeyboardFrame.setVisibility(visibility);
        mKeyboardViewWrapper.setVisibility(Settings.getInstance().readShowToolbarOnly() ? View.GONE : View.VISIBLE);
        // Sellby: restores mKeyboardView specifically - setEmojiKeyboard()/setClipboardKeyboard()
        // below now set it GONE individually (not just via the mToolbarStack parent, since the
        // toolbar row inside that stack must stay visible while Emoji/Clipboard content is showing
        // below it), so it needs its own explicit restoration here too, or it stays stuck GONE after
        // returning to any alphabet-family keyboard (bug: "?123"/"ABC" left only the 6-tab row
        // showing, keyboard gone).
        mKeyboardView.setVisibility(visibility);
        mEmojiPalettesView.setVisibility(View.GONE);
        mEmojiPalettesView.stopEmojiPalettes();
        mClipboardStripScrollView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(View.GONE);
        mClipboardHistoryView.setVisibility(View.GONE);
        mClipboardHistoryView.stopClipboardHistory();
        mCalculatorView.setVisibility(View.GONE);
        mCalculatorView.stopCalculator();
        mCalculatorStripView.setVisibility(View.GONE);
    }

    public void toggleLayout(@NonNull LayoutDirective.Utility layout, int autoCapsFlags, @Nullable RecapitalizeMode recapitalizeMode) {
        mState.toggleLayout(layout, autoCapsFlags, recapitalizeMode);
    }

    public void onLongPressAlphaSymbolForNumpad() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "onLongPressAlphaSymbol");
        }
        mState.onLongPressAlphaSymbolForNumpad();
    }

    public void onLongPressUtilityLayout(@NonNull LayoutDirective.Utility layout) {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "onLongPressUtilityLayout(" + layout + ")");
        }
        mState.onLongPressUtilityLayout(layout);
    }

    public enum KeyboardSwitchState {
        HIDDEN(null),
        SYMBOLS_SHIFTED(KeyboardElement.SYMBOLS_SHIFTED),
        EMOJI(KeyboardElement.EMOJI_RECENTS),
        CLIPBOARD(KeyboardElement.CLIPBOARD),
        OTHER(null);

        @Nullable final KeyboardElement mKeyboardElement;

        KeyboardSwitchState(@Nullable KeyboardElement keyboardElement) {
            mKeyboardElement = keyboardElement;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD;
        } else if (isShowingKeyboardId(KeyboardElement.SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState);
        if (currentState == toggleState) {
            mLatinIME.stopShowingInputView();
            mLatinIME.hideWindow();
            resetKeyboardStateToAlphabet();
        } else {
            mLatinIME.startShowingInputView(true);
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard();
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard();
            } else {
                mEmojiPalettesView.stopEmojiPalettes();
                mEmojiPalettesView.setVisibility(View.GONE);

                mClipboardHistoryView.stopClipboardHistory();
                mClipboardHistoryView.setVisibility(View.GONE);

                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.VISIBLE);
                if (toggleState == KeyboardSwitchState.SYMBOLS_SHIFTED)
                    // possible other states OTHER and HIDDEN have keyboardElement null, which we just ignore
                    // might need to be adjusted when functionality is extended
                    mState.setLayout(LayoutDirective.Utility.SYMBOLS_SHIFTED);
            }
        }
    }

    public void updateShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        // Sellby: this is the single choke point every autoCapsFlags/recapitalizeMode value passes
        // through - including calls LatinIME.java makes directly (outside KeyboardActionListenerImpl,
        // which we can't touch) - so it's the only reliable place to suppress auto-caps while a panel
        // field is focused. Those flags reflect the real app's InputConnection, which never advances
        // while typing into a Sellby field (its text lives only in that EditText), so without this
        // override shift looks permanently stuck on. See SellbyInputRouter.
        final int effectiveAutoCapsFlags = SellbyInputRouter.INSTANCE.getActiveField() != null ? 0 : autoCapsFlags;
        final RecapitalizeMode effectiveRecapitalizeMode = SellbyInputRouter.INSTANCE.getActiveField() != null ? null : recapitalizeMode;
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "updateShiftState: " + " autoCapsFlags=" + CapsModeUtils.flagsToString(effectiveAutoCapsFlags) + " recapitalizeMode=" + effectiveRecapitalizeMode);
        }
        mState.onUpdateShiftState(effectiveAutoCapsFlags, effectiveRecapitalizeMode);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.getCurrent().mOneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.INSTANCE.isFolded()
        );
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        final boolean wasEmoji = isShowingEmojiPalettes();
        final boolean wasClipboard = isShowingClipboardHistory();
        final boolean wasCalculator = isShowingCalculator();
        loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
        if (wasEmoji) {
            setEmojiKeyboard();
        } else if (wasClipboard) {
            setClipboardKeyboard();
        } else if (wasCalculator) {
            mState.setLayout(LayoutDirective.Utility.CALCULATOR);
        }
        // Sellby: this method is called from LatinIME's onStartInputViewInternal() specifically
        // when the focused field belongs to a genuinely DIFFERENT app/field (isDifferentTextField),
        // never for a same-field restart (rotation etc.) - the exact reattach signal
        // flushPendingChannelCommitIfReady() needs, see its own doc comment below.
        flushPendingChannelCommitIfReady();
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    public void showToast(final String text, final boolean briefToast){
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().isSecondaryStripVisible()? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView.getVisibility() == View.VISIBLE) return;

        final Drawable appIcon = mFakeToastView.getCompoundDrawables()[0];
        if (appIcon != null) {
            final int bound = mFakeToastView.getLineHeight();
            appIcon.setBounds(0, 0, bound, bound);
            mFakeToastView.setCompoundDrawables(appIcon, null, null, null);
        }
        mFakeToastView.setText(text);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    public void setBackgroundGatheringIndicator(boolean enabled, boolean hasData, boolean saving) {
        if (mCurrentInputView == null) return;
        mBackgroundGatheringIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        mBackgroundGatheringIndicator.setImageResource(hasData ? R.drawable.btn_keyboard_key_action_normal_lxx_base : R.drawable.ring);
        setBackgroundGatheringIndicatorPosition();
        if (!saving) return;
        mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xff00a000));
        mBackgroundGatheringIndicator.postDelayed(() -> mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xffa00000)), 1500);
    }

    private void setBackgroundGatheringIndicatorPosition() {
        if (mBackgroundGatheringIndicator == null || mBackgroundGatheringIndicator.getVisibility() != View.VISIBLE) return;
        if (mBackgroundGatheringIndicator.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margin) {
            Keyboard kb = mKeyboardView.getKeyboard();
            if (kb != null)
                margin.topMargin = kb.mOccupiedHeight - KtxKt.dpToPx(16, mCurrentInputView.getResources());
            mBackgroundGatheringIndicator.setLayoutParams(mBackgroundGatheringIndicator.getLayoutParams());
        }
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(@NonNull KeyboardElement... keyboardElements) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        KeyboardElement activeKeyboardId = keyboard.mId.getElement();
        for (KeyboardElement keyboardElement : keyboardElements) {
            if (activeKeyboardId == keyboardElement) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isShown();
    }

    public boolean isShowingClipboardHistory() {
        return mClipboardHistoryView != null && mClipboardHistoryView.isShown();
    }

    public boolean isShowingCalculator() {
        return mCalculatorView != null && mCalculatorView.isShown();
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory() || isShowingCalculator()) {
            return false;
        }
        return mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer.isShown();
    }

    public EmojiPalettesView getEmojiPalettesView() {
        return mEmojiPalettesView;
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView;
        } else if (isShowingCalculator()) {
            return mCalculatorView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public LinearLayout getClipboardStrip() {
        return mClipboardStripView;
    }

    public View getCalculatorStrip() {
        return mCalculatorStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public SellbyToolbarView getSellbyToolbarView() {
        return mSellbyToolbarView;
    }

    /** Sellby: commits generated text (e.g. an Invoice) to the host app, reusing LatinIME's existing
     *  public onTextInput (same entry point already used for gesture/batch text commit) rather than
     *  touching InputConnection directly - see KeyboardActionListenerImpl.onTextInput.
     *
     *  BUG FIX: onTextInput() also carries the routing added for Emoji/Clipboard-into-a-focused-panel-
     *  field (see SellbyInputRouter.handleTextInput()) - if a panel EditText (e.g. Invoice's Catatan
     *  field) was the last one focused, it stays Android-focused even after tapping "Buat Invoice"
     *  (that button is only isFocusable, not isFocusableInTouchMode, so it never steals focus away
     *  from the field), so SellbyInputRouter.activeField was still non-null here - the WHOLE generated
     *  invoice text was landing inside that field's own Editable instead of the host chat app, with no
     *  visible error. Text generated here is always meant for the real app, never a panel field, so
     *  the router's active field is cleared first - this is a no-op 99% of the time (most triggers,
     *  e.g. Status/Auto-Text's tap-to-send, don't leave a field focused), and even when it does clear
     *  a field, that field's panel is about to be closed by the caller right after this call anyway.
     *  Uses clearActiveFieldForAppCommit(), NOT the full unfocus() - see its doc comment, unfocus()'s
     *  extra side effects (a synchronous keyboard layout switch in particular) caused their own
     *  "keyboard goes black" regression when fired from here. */
    public void commitSellbyText(String text) {
        SellbyInputRouter.INSTANCE.clearActiveFieldForAppCommit();
        mLatinIME.mKeyboardActionListener.onTextInput(text);
    }

    private String mPendingChannelCommitText;
    private String mPendingChannelCommitTargetPackage;
    private long mPendingChannelCommitDeadlineMillis;
    private static final long PENDING_CHANNEL_COMMIT_TIMEOUT_MS = 6000L;

    /** Sellby: BUG FIX for the "Pilih Channel" feature (Telegram/Instagram specifically) - unlike
     *  wa.me links, which carry the message via their own "?text=" URL parameter (delivered by
     *  WhatsApp itself, no timing dependency on this IME at all), t.me/ig.me deep links have no
     *  such parameter - the ONLY way to deliver text to those apps is this IME's own commit. Firing
     *  that commit immediately after startActivity() (the old behavior) raced the OS's async
     *  app-switch and always lost: the text landed wherever the IME was ALREADY connected BEFORE
     *  the switch (e.g. a WhatsApp chat that happened to be open), not the new app - confirmed by
     *  the user's own on-device test. This defers the commit until reloadMainKeyboard() (called by
     *  LatinIME specifically when the IME reattaches to a genuinely different app/field, see its
     *  own comment) reports an EditorInfo.packageName matching [targetPackage] - a real reattach
     *  signal, not a blind Handler.postDelayed() guess. Expires after
     *  PENDING_CHANNEL_COMMIT_TIMEOUT_MS so a cancelled/failed app-switch (user backs out before
     *  the target app loads, etc.) never leaves stale text sitting around ready to fire into some
     *  unrelated app the user happens to focus next - see flushPendingChannelCommitIfReady(). */
    public void commitSellbyTextWhenPackageFocused(String text, String targetPackage) {
        mPendingChannelCommitText = text;
        mPendingChannelCommitTargetPackage = targetPackage;
        mPendingChannelCommitDeadlineMillis = System.currentTimeMillis() + PENDING_CHANNEL_COMMIT_TIMEOUT_MS;
    }

    /** Called from reloadMainKeyboard() - see commitSellbyTextWhenPackageFocused()'s doc comment.
     *  No-op the overwhelming majority of the time (no pending commit, or a reload unrelated to a
     *  channel deep-link - e.g. one-handed-mode toggles also call reloadMainKeyboard() but never
     *  set a pending commit, so this simply finds nothing to flush and returns). */
    private void flushPendingChannelCommitIfReady() {
        if (mPendingChannelCommitText == null) return;
        if (System.currentTimeMillis() > mPendingChannelCommitDeadlineMillis) {
            mPendingChannelCommitText = null;
            mPendingChannelCommitTargetPackage = null;
            return;
        }
        final EditorInfo info = mLatinIME.getCurrentInputEditorInfo();
        if (info != null && TextUtils.equals(info.packageName, mPendingChannelCommitTargetPackage)) {
            final String text = mPendingChannelCommitText;
            mPendingChannelCommitText = null;
            mPendingChannelCommitTargetPackage = null;
            commitSellbyText(text);
        }
    }

    /** Sellby: like commitSellbyText() but for rich content (e.g. the QRIS QR-code photo attached
     *  to an invoice) - reuses the SAME onContent() plumbing already proven by Clipboard's own
     *  image-paste feature (KeyboardActionListenerImpl.onContent -> RichInputConnection.
     *  commitContent -> InputConnectionCompat.commitContent, which already handles the target
     *  editor's mime-type check, the URI read-permission grant, and a graceful fallback/no-op when
     *  unsupported - nothing new to reimplement here). onContent() never consulted SellbyInputRouter
     *  to begin with (a plain EditText can't display an image), so no equivalent fix is needed here -
     *  kept for symmetry with commitSellbyText's doc comment, not because this one was broken too. */
    public void commitSellbyContent(InputContentInfoCompat content) {
        mLatinIME.mKeyboardActionListener.onContent(content);
    }

    /** Sellby: called from SellbyToolbarView.onTabTapped()/onSettingsTapped() before their normal
     *  open-panel logic - if the user is currently browsing Emoji or Clipboard (where the toolbar
     *  row now stays visible, see setEmojiKeyboard()/setClipboardKeyboard() above), tapping a tab
     *  needs to leave that mode first since the panel's own input fields need the physical alphabet
     *  keyboard (via SellbyInputRouter) to type into, not the emoji grid or clipboard list. Reuses
     *  the exact same "return to alphabet" call already used by emoji_bottom_bar's own back-to-alpha
     *  button (EmojiPalettesView) and Calculator's own ABC button (CalculatorView) - not a new
     *  mechanism. No-op (and safe to call unconditionally) when already on the alphabet/symbols/
     *  numpad keyboard. */
    public void exitUtilityModeIfNeeded() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mLatinIME.mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        }
    }

    /** Sellby: switches to/from the numpad layout for a focused panel field (Invoice/Settings numeric
     *  fields), mirroring sellby_keyboard.dart's isNumeric-driven KeyboardMode.numpad switch. Checks
     *  isShowingKeyboardId first so this behaves like a "set", not a blind toggle - toggleLayout()
     *  itself has no direct "set" form, only toggle. */
    public void setSellbyNumericLayout(boolean numeric) {
        final boolean isNumpad = isShowingKeyboardId(KeyboardElement.NUMPAD);
        if (numeric == isNumpad) return;
        toggleLayout(LayoutDirective.Utility.NUMPAD, mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState());
    }

    /**
     * Sellby: on Android 13+ with predictive back enabled (this app opts in via
     * android:enableOnBackInvokedCallback="true"), KeyEvent.KEYCODE_BACK is no longer dispatched
     * through the legacy InputMethodService#onKeyDown path at all - the system handles back
     * through OnBackInvokedDispatcher instead, bypassing KeyboardActionListenerImpl#onKeyDown
     * (which still covers older devices as a fallback). Registered once, tied to the IME's own
     * Window (obtained the same way as Ktx.kt's updateSoftInputWindowLayoutParameters), so it
     * works for as long as the service lives regardless of how many times onCreateInputView() runs.
     * Zero changes to LatinIME.java - requestHideSelf() is already its own public method, already
     * called externally elsewhere (e.g. KeyboardActionListenerImpl.kt).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void registerSellbyBackCallbackApi33() {
        final android.app.Dialog dialog = mLatinIME.getWindow();
        final android.view.Window window = dialog == null ? null : dialog.getWindow();
        Log.i(TAG, "Sellby: registering back callback, dialog=" + dialog + " window=" + window);
        if (window == null) return;
        window.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            // PRIORITY_OVERLAY: this panel is a floating/overlay-style UI element that should
            // consume back before any lower-priority (default) "dismiss the whole IME" handling.
            android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            () -> {
                final boolean closed = mSellbyToolbarView != null && mSellbyToolbarView.closeIfOpen();
                Log.i(TAG, "Sellby: back callback invoked, panel was open=" + closed);
                // Bug found after this shipped: closeIfOpen() collapses panelRegion via a 200ms
                // ANIMATION, but requestHideSelf() below fires immediately after - the window (and
                // this same long-lived SellbyToolbarView/panelRegion instance, which persists across
                // hide/show cycles) can disappear mid-animation, leaving panelRegion stuck at
                // whatever height/visibility the animation was interrupted at. Next time a field
                // gets focus and the keyboard reappears, that stale non-collapsed state is still
                // there, so the panel visually reopens even though activeTab is correctly null.
                // collapseImmediately() (already used for the Emoji/Clipboard/Calculator mode-switch
                // path) cancels any in-flight animator and forces panelRegion to GONE/height=0
                // synchronously - calling it here right before the hide guarantees a clean state
                // regardless of whether the animation above had time to finish.
                if (mSellbyToolbarView != null) mSellbyToolbarView.collapseImmediately();
                mLatinIME.requestHideSelf(0);
            });
    }

    private void registerSellbyBackCallback() {
        Log.i(TAG, "Sellby: registerSellbyBackCallback() called, already=" + mSellbyBackCallbackRegistered + " sdk=" + Build.VERSION.SDK_INT);
        if (mSellbyBackCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        mSellbyBackCallbackRegistered = true;
        registerSellbyBackCallbackApi33();
    }

    public FrameLayout getStripContainer() { return mStripContainer; }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
        }
        if (mCalculatorView != null) {
            mCalculatorView.stopCalculator();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, boolean isHardwareAcceleratedDrawingEnabled) {
        Log.d(TAG, "create new input view");
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        PointerTracker.clearOldViewData();
        SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        mCurrentInputView = (InputView)LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view);
        mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);
        mCalculatorView = mCurrentInputView.findViewById(R.id.calculator_view);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mToolbarStack = mCurrentInputView.findViewById(R.id.sellby_toolbar_stack);
        // BUG FIX ("keyboard turns black" after a host app like WhatsApp hides then immediately
        // re-shows the IME via its own emoji/keyboard toggle - reproduced with adb logcat: no crash,
        // pure render gap): mKeyboardView (MainKeyboardView) deliberately paints its OWN background
        // as fully transparent for a normal alphabet keyboard (see KeyboardView.setKeyboard(), "actual
        // background color/drawable is applied to main_keyboard_frame") - it was always relying on
        // R.id.main_keyboard_frame (painted once, via InputView.onNextLayout()'s one-shot
        // doOnNextLayout callback, ColorType.MAIN_BACKGROUND) showing through from several levels up
        // the tree. That was fine when mKeyboardView sat directly under main_keyboard_frame, but it
        // now lives one level deeper, inside sellby_toolbar_stack (see main_keyboard_frame.xml) -
        // sellby_toolbar_stack itself paints nothing of its own, so on a fast hide-then-reshow restart
        // (not a fresh inflate) it's plausible for that one-shot ancestor paint to not have (re)run in
        // time, leaving nothing but the window's own default black clear color showing through the
        // gap. Rather than chase that one-shot timing further, sellby_toolbar_stack gets the exact
        // same ColorType.MAIN_BACKGROUND paint applied directly to itself here - same call the
        // ancestor already uses (so it still respects a user's custom keyboard background image,
        // which needs real width/height to size correctly - deferred via the same doOnNextLayout
        // pattern InputView.java itself uses, not called immediately while still 0x0 during inflate).
        // This runs every time onCreateInputView() does (every reinflate), independent of whatever
        // happens several levels up - a direct, guaranteed paint instead of a relied-upon one.
        ViewKt.doOnNextLayout(mToolbarStack, v -> {
            Settings.getValues().mColors.setBackground(mToolbarStack, ColorType.MAIN_BACKGROUND);
            return null;
        });
        mSellbyToolbarView = mCurrentInputView.findViewById(R.id.sellby_toolbar_row);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        // BUG FIX (root cause of both the "black blank area sized like the keyboard" AND the
        // "app content not pushed up enough" symptoms reported after a host app like WhatsApp
        // hides then rapidly re-shows the IME via its own UI toggle): KeyboardView.onMeasure()
        // (see KeyboardView.java) falls back to super.onMeasure() - collapsing to ~0 height -
        // whenever getKeyboard() is still null, i.e. whenever this measure pass races ahead of
        // loadKeyboard()/setKeyboard() actually assigning a Keyboard object (both run
        // synchronously in onStartInputViewInternal, but nothing GUARANTEES that finishes before
        // Android's very first layout pass on a freshly-shown window). This was harmless when
        // mKeyboardView was a flat FrameLayout sibling (its own transient 0-height never affected
        // any sibling's size) - now that it lives inside sellby_toolbar_stack (a LinearLayout,
        // which sums its VISIBLE children's heights to determine ITS OWN wrap_content height), a
        // momentary 0-height here collapses the whole stack's reported height too, while the
        // WINDOW itself has already been sized normally (independent of this live measurement) -
        // leaving a gap the exact size of the keyboard that's either undrawn (black) or, if an app
        // above reads that stale/small reported height for its own inset calculation, not pushed
        // up far enough. Reserving a minimum height up front means this view can never collapse to
        // 0 while waiting for its first Keyboard object, regardless of which measure pass wins the
        // race - closing the gap instead of chasing the timing itself.
        mKeyboardView.setMinimumHeight(ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), Settings.getValues()));
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mSellbyToolbarView.initialize(mCurrentInputView.findViewById(R.id.sellby_panel_region), mKeyboardView);
        registerSellbyBackCallback();
        mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mClipboardHistoryView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mCalculatorView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mClipboardStripView = mCurrentInputView.findViewById(R.id.clipboard_strip);
        mClipboardStripScrollView = mCurrentInputView.findViewById(R.id.clipboard_strip_scroll_view);
        mCalculatorStripView = mCurrentInputView.findViewById(R.id.calculator_strip);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);
        mBackgroundGatheringIndicator = mCurrentInputView.findViewById(R.id.backgroundGatheringIndicator);

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView);
        PointerTracker.switchTo(mKeyboardView);
        return mCurrentInputView;
    }

    public CapsMode getKeyboardCapsMode() {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return CapsMode.OFF;
        }
        return keyboard.mId.getElement().getCapsMode();
    }

    public String getCurrentKeyboardScript() {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        return mKeyboardLayoutSet.getScript();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }

    // private SwitchActions implementation so e.g. setEmojiKeyboard can only be called via KeyboardState (avoid inconsistencies!)
    private class SwitchActions implements KeyboardState.SwitchActions {
        @Override
        public void setAlphabetKeyboard(@NonNull ShiftMode shiftMode) {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setAlphabetKeyboard");
            }
            setKeyboard(shiftMode.element, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setSymbolsKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsKeyboard");
            }
            setKeyboard(KeyboardElement.SYMBOLS, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setSymbolsShiftedKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsShiftedKeyboard");
            }
            setKeyboard(KeyboardElement.SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
        }

        @Override
        public void setEmojiKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setEmojiKeyboard");
            }
            mMainKeyboardFrame.setVisibility(View.VISIBLE);
            // Sellby: mToolbarStack now stays VISIBLE here (used to be GONE) so the 6-tab toolbar
            // row inside it keeps showing above the emoji grid. Unlike before, this no longer calls
            // collapseImmediately() (which force-closed any open Sellby panel and cleared the
            // focused panel field) - a panel field the user was typing into stays open/focused
            // through this mode switch, so tapping an emoji can insert straight into it (see
            // SellbyInputRouter.handleTextInput()/KeyboardActionListenerImpl.onTextInput()).
            // clearAutoTextSuggestions() alone still runs, since the suggestion strip only applies
            // to real-app typing and never made sense to leave showing over the emoji grid. Only
            // mKeyboardView (physicalKeys) is explicitly hidden here - the panel above it, if any, is
            // left exactly as it was. emoji_palettes_view now lives inside mToolbarStack (see
            // main_keyboard_frame.xml) so it renders below the toolbar row instead of overlapping it.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mToolbarStack.setVisibility(View.VISIBLE);
            mSellbyToolbarView.clearAutoTextSuggestions();
            mKeyboardView.setVisibility(View.GONE);
            mSuggestionStripView.setVisibility(View.GONE);
            // Forced GONE (not getSecondaryStripVisibility()) - that setting is meant for
            // calculator_strip's expression/result display, the only thing strip_container ever
            // shows content for outside Clipboard's old toolbar (also GONE below). Nothing in
            // strip_container is relevant to Emoji, so leaving this setting-driven left the whole
            // (empty) container visible as a blank bar above the toolbar row whenever that setting
            // was on - same fix already applied to setClipboardKeyboard() below.
            mStripContainer.setVisibility(View.GONE);
            mClipboardStripScrollView.setVisibility(View.GONE);
            mClipboardHistoryView.setVisibility(View.GONE);
            mCalculatorView.setVisibility(View.GONE);
            mCalculatorView.stopCalculator();
            mCalculatorStripView.setVisibility(View.GONE);
            mEmojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
            mEmojiPalettesView.setVisibility(View.VISIBLE);
        }

        @Override
        public void setClipboardKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setClipboardKeyboard");
            }
            mMainKeyboardFrame.setVisibility(View.VISIBLE);
            // Sellby: mToolbarStack now stays VISIBLE here (used to be GONE) so the 6-tab toolbar
            // row inside it keeps showing above the clipboard list, REPLACING the stock clipboard
            // toolbar (chevrons/undo/cut/copy/paste/select/close, mClipboardStripScrollView's
            // clipboard_strip content) entirely - that toolbar is simply never shown anymore
            // (mStripContainer forced GONE below, mClipboardStripScrollView left GONE, its old
            // fullScroll/setVisibility(VISIBLE) calls removed). Like setEmojiKeyboard() above, this
            // no longer calls collapseImmediately() - an open Sellby panel and its focused field
            // survive this mode switch, so tapping a clipboard entry can insert straight into it
            // (see SellbyInputRouter.handleTextInput()). clipboard_history_view now lives inside
            // mToolbarStack (see main_keyboard_frame.xml) so it renders below the toolbar row
            // instead of overlapping it.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mToolbarStack.setVisibility(View.VISIBLE);
            mSellbyToolbarView.clearAutoTextSuggestions();
            mKeyboardView.setVisibility(View.GONE);
            mSuggestionStripView.setVisibility(View.GONE);
            mStripContainer.setVisibility(View.GONE);
            mEmojiPalettesView.setVisibility(View.GONE);
            mCalculatorView.setVisibility(View.GONE);
            mCalculatorView.stopCalculator();
            mCalculatorStripView.setVisibility(View.GONE);
            mClipboardHistoryView.startClipboardHistory(mLatinIME.getClipboardHistoryManager(), mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
            mClipboardHistoryView.setVisibility(View.VISIBLE);
        }

        @Override
        public void setCalculatorKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setCalculatorKeyboard");
            }
            mMainKeyboardFrame.setVisibility(View.VISIBLE);
            // The visibility of {@link #mToolbarStack} (wraps mKeyboardView + the Sellby toolbar
            // row + panel region) must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mToolbarStack.setVisibility(View.GONE);
            mSellbyToolbarView.collapseImmediately();
            mSuggestionStripView.setVisibility(View.GONE);
            mStripContainer.setVisibility(getSecondaryStripVisibility());
            mClipboardStripScrollView.setVisibility(View.GONE);
            mCalculatorStripView.setVisibility(View.VISIBLE);
            mEmojiPalettesView.setVisibility(View.GONE);
            mClipboardHistoryView.setVisibility(View.GONE);
            mCalculatorView.startCalculator(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
            mCalculatorView.setVisibility(View.VISIBLE);
        }

        @Override
        public void setNumpadKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setNumpadKeyboard");
            }
            setKeyboard(KeyboardElement.NUMPAD, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setDpadKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setDpadKeyboard");
            }
            setKeyboard(KeyboardElement.DPAD, KeyboardSwitchState.OTHER);
        }

        @Override
        public void startDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "startDoubleTapShiftKeyTimer");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.startDoubleTapShiftKeyTimer();
            }
        }

        @Override
        public void cancelDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "cancelDoubleTapShiftKeyTimer");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.cancelDoubleTapShiftKeyTimer();
            }
        }

        @Override
        public void setOneHandedModeEnabled(boolean enabled) {
            KeyboardSwitcher.this.setOneHandedModeEnabled(enabled, false);
        }

        @Override
        public void switchOneHandedMode() {
            mKeyboardViewWrapper.switchOneHandedModeSide();
            Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
        }

        @Override
        public void setFloatingKeyboardEnabled(boolean enabled) {
            if (enabled != Settings.getValues().mIsFloatingKeyboard)
                // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
                SettingsKt.setFloatingKeyboardEnabled(mThemeContext, enabled);
            if (enabled) FloatingKeyboardUtils.setFloating(mCurrentInputView);
            else FloatingKeyboardUtils.disableFloating(mCurrentInputView);
            setBackgroundGatheringIndicatorPosition();
        }

        @Override
        public boolean popDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            return keyboardView != null && keyboardView.popDoubleTapShiftKeyTimer();
        }

        // not a SwitchAction, but should only be called from a SwitchAction to avoid inconsistent state / actual layout
        private void setKeyboard(KeyboardElement keyboardElement, @NonNull KeyboardSwitchState toggleState) {
            // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
            if (mKeyboardView == null) return;

            // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
            SettingsValues currentSettingsValues = Settings.getValues();
            setMainKeyboardFrame(currentSettingsValues, toggleState);
            // TODO: pass this object to setKeyboard instead of getting the current values.
            MainKeyboardView keyboardView = mKeyboardView;
            Keyboard oldKeyboard = keyboardView.getKeyboard();
            Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardElement);
            keyboardView.setKeyboard(newKeyboard);
            mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
            keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
            keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
            boolean subtypeChanged = (oldKeyboard == null) || !newKeyboard.mId.getSubtype().equals(oldKeyboard.mId.getSubtype());
            int languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.getSubtype());
            boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
            keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);

            if (currentSettingsValues.needsToLookupSuggestions()
                && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
                EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
            }
        }
    }
}
