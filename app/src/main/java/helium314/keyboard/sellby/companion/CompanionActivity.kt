// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import helium314.keyboard.sellby.companion.theme.SellbyCompanionTheme

/** Sellby companion app (Fase 5) - sole launcher Activity for the app icon, replacing
 *  SettingsActivity (whose LAUNCHER intent-filter was removed in AndroidManifest.xml; the
 *  Activity itself is untouched and still reachable via the gear icon inside the keyboard). */
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SellbyCompanionTheme {
                Surface {
                    CompanionNavHost(onFinish = { finish() })
                }
            }
        }
    }
}
