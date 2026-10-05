// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.os.SystemClock
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
import helium314.keyboard.sellby.companion.TrialPolicy
import helium314.keyboard.sellby.companion.TrialState
import helium314.keyboard.sellby.companion.billing.BillingRepository
import helium314.keyboard.sellby.companion.theme.SellbyColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How long the logo is shown at least. Was 2.5 seconds plus a Block Store wait; the splash is only a
 *  brand moment, so it is kept short. */
private const val MIN_SPLASH_MS = 1000L

/** The longest the splash waits for the Block Store lookup, and only when the answer matters (see below). */
private const val SYNC_WAIT_MS = 1500L

/** The longest the splash waits for Google Play to say what the user owns, and only when the answer matters. */
private const val PURCHASE_WAIT_MS = 3000L

/** Ported from loading_page.dart: teal splash, centered logo, then routes based on whether onboarding was
 *  already completed - simplified from Flutter's 3-way branch (license activated / profile completed /
 *  neither) to a single flag since there's no license step anymore.
 *
 *  The trial is restored from Block Store in the background ([TrialPolicy.startSync], which outlives
 *  this screen). The splash only waits for it in the one case that needs the answer before moving on: the
 *  onboarding is finished but this install has no trial start (reinstall / Clear data) - going on without
 *  waiting would start a brand new trial before the old one could be restored. Everywhere else the local
 *  copy is already right (or the user still has the whole onboarding ahead), so nothing waits, and the
 *  worst case is bounded by [MIN_SPLASH_MS] + [SYNC_WAIT_MS].
 *
 *  Same idea for purchases: Play is asked in the background on every app start (CompanionActivity), and the splash
 *  waits for the answer only when it decides where to go next and the user is NOT known to be premium and the
 *  trial is gone or missing (reinstall of a paid app, a new phone). Without that wait such a user would be sent
 *  to the purchase page - or given a brand new trial - before Play could say "you already bought this". Users
 *  who are premium, or still inside their trial, never wait for it. */
@Composable
fun LoadingScreen(onOnboardingDone: () -> Unit, onNeedsOnboarding: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val shownAt = SystemClock.elapsedRealtime()
        val completed = context.prefs().getBoolean(PREF_ONBOARDING_COMPLETED, false)
        val sync = TrialPolicy.startSync(context)
        val purchases = BillingRepository.get(context).reconcileAsync()
        val noTrialStart = completed && !TrialPolicy.hasStart(context)
        val state = TrialPolicy.state(context)
        val needsPurchaseAnswer = completed && state !is TrialState.Premium && (!TrialPolicy.hasStart(context) || state is TrialState.Expired)
        if (noTrialStart || needsPurchaseAnswer) {
            // In parallel: the slower of the two decides, not their sum.
            coroutineScope {
                val syncWait = async { if (noTrialStart) withTimeoutOrNull(SYNC_WAIT_MS) { sync.await() } }
                val purchaseWait = async { if (needsPurchaseAnswer) withTimeoutOrNull(PURCHASE_WAIT_MS) { purchases.await() } }
                syncWait.await()
                purchaseWait.await()
            }
        }
        val remaining = MIN_SPLASH_MS - (SystemClock.elapsedRealtime() - shownAt)
        if (remaining > 0) delay(remaining)
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
