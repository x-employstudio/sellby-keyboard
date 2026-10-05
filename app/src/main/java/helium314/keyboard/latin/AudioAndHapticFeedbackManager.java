/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.SettingsValues;

/**
 * This class gathers audio feedback and haptic feedback functions.
 * <p>
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private ContentResolver mContentResolver;
    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private boolean mDoNotDisturb;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        // Application context: this singleton outlives every LatinIME instance, don't pin a service.
        mContentResolver = context.getApplicationContext().getContentResolver();
        mDoNotDisturb = readDoNotDisturb();
    }

    /** Same "zen_mode" check LatinIME's ringer-mode receiver uses. That receiver only exists while
     *  the IME service is alive, so a Do Not Disturb change made while another keyboard was active
     *  was missed and this singleton (it survives service re-creation) kept a stale value -
     *  re-read it whenever the service starts and whenever settings reload instead of trusting
     *  only the broadcast. */
    private boolean readDoNotDisturb() {
        if (mContentResolver == null) return false;
        try {
            return android.provider.Settings.Global.getInt(mContentResolver, "zen_mode") != 0;
        } catch (android.provider.Settings.SettingNotFoundException e) {
            return false;
        }
    }

    public void performHapticAndAudioFeedback(
        final int code,
        final View viewToPerformHapticFeedbackOn,
        final HapticEvent hapticEvent
    ) {
        performHapticFeedback(viewToPerformHapticFeedbackOn, hapticEvent);
        performAudioFeedback(code, hapticEvent);
    }

    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    public void vibrate(final long milliseconds) {
        if (mVibrator == null || milliseconds <= 0) {
            return;
        }
        mVibrator.vibrate(milliseconds);
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null || mDoNotDisturb) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code, final HapticEvent hapticEvent) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        if (mAudioManager == null) {
            return;
        }
        if (!mSoundOn) {
            return;
        }
        if (hapticEvent != HapticEvent.KEY_PRESS) {
            return;
        }
        final int sound = switch (code) {
            case KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE;
            case Constants.CODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN;
            case Constants.CODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR;
            default -> AudioManager.FX_KEYPRESS_STANDARD;
        };
        mAudioManager.playSoundEffect(sound, mSettingsValues.mKeypressSoundVolume);
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn, final HapticEvent hapticEvent) {
        if (mSettingsValues == null) {
            return;
        }
        if (!mSettingsValues.mVibrateOn || (mDoNotDisturb && !mSettingsValues.mVibrateInDndMode)) {
            return;
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            // Avoid surprises with the handling of HapticFeedbackConstants.NO_HAPTICS
            return;
        }
        if (hapticEvent.allowCustomDuration && mSettingsValues.mKeypressVibrationDuration >= 0) {
            vibrate(mSettingsValues.mKeypressVibrationDuration);
            return;
        }
        // The platform haptic effect is the primary path: it's the OEM-tuned waveform for this
        // hardware. An earlier version bypassed it for typing with a hard-coded 20 ms
        // Vibrator.vibrate() pulse; that is far too short to feel on many phones' motors (it only
        // seemed to work during hold-delete, where the pulses stack up), so typing was silent there
        // while the gestures - which kept the platform path - still vibrated. HapticEvent now picks
        // constants that are confirmed to vibrate (CLOCK_TICK for typing); the direct pulse below
        // is only a fallback for when the platform call can't be performed (no usable view, view
        // detached, haptics disabled on the view) and uses a duration long enough to be felt.
        final boolean performed = viewToPerformHapticFeedbackOn != null
                && viewToPerformHapticFeedbackOn.performHapticFeedback(
                        hapticEvent.feedbackConstant,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        if (!performed) {
            vibrate(FALLBACK_VIBRATION_DURATION_MS);
        }
    }

    private static final long FALLBACK_VIBRATION_DURATION_MS = 30L;

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mDoNotDisturb = readDoNotDisturb();
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged(boolean doNotDisturb) {
        mDoNotDisturb = doNotDisturb;
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
