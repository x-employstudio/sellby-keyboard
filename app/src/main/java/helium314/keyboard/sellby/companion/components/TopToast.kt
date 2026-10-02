// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reusable "slide down from top, auto-dismiss" validation toast, used across every companion-app
 *  screen (currently just ProfilTokoScreen, but the point of pulling it into its own component is
 *  that any future screen reuses it too). Styling is deliberately matched to the in-keyboard
 *  panels' own `showToast()` (InvoicePanelView.kt/StatusPanelView.kt/etc: dark #1E293B pill, white
 *  bold text, no icon/border, ~2.8s auto-dismiss) rather than the original Flutter reference's
 *  white-pill-with-red-icon design - the user asked for the two surfaces (companion app vs.
 *  in-keyboard panels) to look consistent with each other. */
class TopToastState(private val scope: CoroutineScope) {
    var message by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun show(text: String) {
        job?.cancel()
        message = text
        job = scope.launch {
            delay(2800)
            message = null
        }
    }
}

@Composable
fun rememberTopToastState(): TopToastState {
    val scope = rememberCoroutineScope()
    return remember { TopToastState(scope) }
}

@Composable
fun TopToastHost(state: TopToastState, modifier: Modifier = Modifier) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = state.message != null,
            enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E293B),
                shadowElevation = 4.dp,
                modifier = Modifier.padding(top = statusBarHeight + 16.dp, start = 28.dp, end = 28.dp),
            ) {
                Text(
                    state.message.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
