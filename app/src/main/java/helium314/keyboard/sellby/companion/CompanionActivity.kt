// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.sellby.companion.billing.BillingRepository
import helium314.keyboard.sellby.companion.theme.SellbyCompanionTheme
import helium314.keyboard.sellby.companion.tutorial.LessonId
import kotlinx.coroutines.launch

/** Sellby companion app (Fase 5) - sole launcher Activity for the app icon, replacing
 *  SettingsActivity (whose LAUNCHER intent-filter was removed in AndroidManifest.xml; the
 *  Activity itself is untouched and still reachable via the gear icon inside the keyboard). */
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Every time the app comes to the front (also when the keyboard opens it): ask Google Play what the user owns,
        // so a purchase made elsewhere, a restore after reinstall, a payment that completed later and a refund are
        // all picked up. Best effort and silent: without Play or network nothing changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BillingRepository.get(applicationContext).reconcile()
            }
        }
        // Set by the keyboard when a locked toolbar tab was tapped (see CompanionLauncher).
        val startAtPurchase = intent?.getBooleanExtra(CompanionLauncher.EXTRA_OPEN_PURCHASE, false) == true
        // Set by Settings -> Tutorial in the keyboard: open that lesson instead of the usual start.
        val startLesson = intent?.getStringExtra(CompanionLauncher.EXTRA_OPEN_TUTORIAL)
            ?.let { name -> LessonId.entries.firstOrNull { it.name == name } }
        setContent {
            SellbyCompanionTheme {
                Surface {
                    CompanionNavHost(
                        startAtPurchase = startAtPurchase,
                        startLesson = startLesson,
                        onFinish = {
                            finish()
                            // A tutorial lesson opened from the keyboard's Settings -> Tutorial promised to bring
                            // the keyboard back on that list when it ends; a no-op for every other way out.
                            KeyboardSwitcher.getInstance().endSellbyHelper()
                        },
                    )
                }
            }
        }
    }
}
