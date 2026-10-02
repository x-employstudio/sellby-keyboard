// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.PREF_ONBOARDING_COMPLETED
import helium314.keyboard.sellby.companion.theme.SellbyColors
import kotlinx.coroutines.delay

/** Ported from loading_page.dart: teal splash, centered logo, 2.5s delay, then routes based on
 *  whether onboarding was already completed - simplified from Flutter's 3-way branch (license
 *  activated / profile completed / neither) to a single flag since there's no license step anymore. */
@Composable
fun LoadingScreen(onOnboardingDone: () -> Unit, onNeedsOnboarding: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        delay(2500)
        val completed = context.prefs().getBoolean(PREF_ONBOARDING_COMPLETED, false)
        if (completed) onOnboardingDone() else onNeedsOnboarding()
    }
    Box(
        Modifier.fillMaxSize().background(SellbyColors.TealDark),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.sellby_companion_logo_white),
            contentDescription = null,
            modifier = Modifier.width(120.dp),
        )
    }
}
