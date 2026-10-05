// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.R

/** Plays the "new message" sound when a buyer's bubble appears (a no-op where none is provided). */
internal val LocalChatSound = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The notification sound (res/raw/notification_1.mp3, copied from design-assets/sfx) as a ready-to-play
 * [SoundPool] clip: loaded once, so it plays without delay. It uses the notification audio stream, so
 * the phone's notification volume and silent mode apply.
 */
@Composable
internal fun rememberChatSound(): () -> Unit {
    val context = LocalContext.current.applicationContext
    val player = remember { ChatSoundPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player::play
}

private class ChatSoundPlayer(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var soundId = 0
    private var loaded = false

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        soundId = pool.load(context, R.raw.notification_1, 1)
    }

    fun play() {
        if (loaded) pool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
