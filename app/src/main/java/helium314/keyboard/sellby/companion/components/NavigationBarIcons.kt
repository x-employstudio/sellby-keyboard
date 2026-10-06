// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Colors the phone's own navigation buttons (back / home / recents) for the screen being shown. The app draws
 *  edge-to-edge, and the system picks light or dark button icons from the PHONE's theme, not from what is behind
 *  them: on a phone in dark mode a white page got white buttons that nobody could see.
 *
 *  [darkIcons] = true for a screen whose bottom edge is light (dark gray buttons); false for one whose bottom edge is
 *  dark, like the purchase page's navy gradient (light buttons). The previous setting is restored when the screen
 *  leaves, so each screen only has to say what it needs. */
@Composable
fun NavigationBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, darkIcons) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousIcons = controller.isAppearanceLightNavigationBars
            // The translucent scrim Android adds behind 3-button navigation would otherwise tint the bar gray.
            val previousContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
            controller.isAppearanceLightNavigationBars = darkIcons
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            onDispose {
                controller.isAppearanceLightNavigationBars = previousIcons
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = previousContrast
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
