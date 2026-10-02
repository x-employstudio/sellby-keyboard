// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Fixed Sellby brand palette, ported from the Flutter companion-app reference (loading/welcome/
 *  profile_setup/dashboard_page.dart). Deliberately NOT the same as [helium314.keyboard.latin.utils.Theme]
 *  used by SettingsActivity - that one follows the user's customizable keyboard color scheme, while
 *  the companion app's identity stays constant regardless of what keyboard theme the user picks. */
object SellbyColors {
    val TealPrimary = Color(0xFF0EA5C6)
    val TealDark = Color(0xFF1390B7)
    val GreetingGradientStart = Color(0xFF1290B6)
    val GreetingGradientEnd = Color(0xFF2279AF)
    val PenjualanCard = Color(0xFF1390B7)
    val OmzetCard = Color(0xFF142C69)
    val StatusStripStart = Color(0xFF0EA5C6)
    val StatusStripEnd = Color(0xFF53B514)
    val KeyboardActiveGreen = Color(0xFF53B514)
    val KeyboardActiveGreenDark = Color(0xFF2E6B09)
    val KeyboardInactiveRed = Color(0xFFD50032)
    val KeyboardInactiveRedDark = Color(0xFF8B001F)
    val GrowthPositiveBg = Color(0xFFD4F97A)
    val GrowthPositiveFg = Color(0xFF1E5B09)
    val GrowthNegativeBg = Color(0xFFFFD5D8)
    val GrowthNegativeFg = Color(0xFFB71C1C)
    val TextDark = Color(0xFF1E293B)
    val TextMuted = Color(0xFF64748B)
    val TextHint = Color(0xFF94A3B8)
    val SurfaceMuted = Color(0xFFE2E8F0)
    val SupportCardBg = Color(0xFF0F172A)
    val ErrorRed = Color(0xFFDC2626)
    val White = Color(0xFFFFFFFF)
}

@Composable
fun SellbyCompanionTheme(content: @Composable () -> Unit) {
    // Single fixed scheme (not system-dark-mode-adaptive) - matches the "identitas warna TETAP"
    // decision: the companion app's branding should not change with the user's keyboard theme,
    // and the Flutter reference itself never defined a separate dark variant for these screens.
    val colorScheme = lightColorScheme(
        primary = SellbyColors.TealPrimary,
        onPrimary = SellbyColors.White,
        secondary = SellbyColors.TealDark,
        background = SellbyColors.White,
        onBackground = SellbyColors.TextDark,
        surface = SellbyColors.White,
        onSurface = SellbyColors.TextDark,
        error = SellbyColors.ErrorRed,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
