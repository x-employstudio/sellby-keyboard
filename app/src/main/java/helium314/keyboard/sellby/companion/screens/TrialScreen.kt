// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

// Page-local palette, taken from the user's mockup (same approach as PurchaseScreen): the gradient
// and the lime CTA exist only on these billing-flow screens, so they are not added to SellbyColors.
private val TrialGradientTop = Color(0xFF2FA0D9)
private val TrialGradientBottom = Color(0xFF2A74BA)
private val TrialCardTop = Color(0xFF0A5BA6)
private val TrialCardBottom = Color(0xFF06407F)
private val TrialNavy = Color(0xFF0B2A5B)
private val TrialLime = Color(0xFF8CE623)

private class TrialStep(val icon: Int, val title: String, val body: String)

private val TrialSteps = listOf(
    TrialStep(R.drawable.ic_trial_day_1, "Hari ini", "Buka akses penuh ke semua fitur tanpa biaya (Rp0)"),
    TrialStep(R.drawable.ic_trial_day_2, "Hari ke 2", "Jelajahi dan nikmati semua fitur sepuasnya tanpa batasan"),
    TrialStep(R.drawable.ic_trial_day_3, "Hari ke 3", "Sekali bayar untuk akses permanen selamanya"),
)

private val StepCircle = 30.dp

/** Onboarding page that starts the 3-day free trial (design supplied by the user). The screen itself
 *  touches no storage: [onStart] is where CompanionNavHost starts the trial and finishes onboarding,
 *  the same way the tutorial leaves persistence to the NavHost. The illustration is the existing
 *  `image_billing.png` (R.drawable.sellby_billing_illustration), shared with the Purchase page. */
@Composable
fun TrialScreen(onStart: () -> Unit) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(TrialGradientTop, TrialGradientBottom)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(statusBarHeight + 12.dp))
            // Takes whatever height the card and button leave; Fit keeps the artwork undistorted.
            Image(
                painter = painterResource(R.drawable.sellby_billing_illustration),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            TrialCard()
            Spacer(Modifier.height(26.dp))
            Surface(
                onClick = onStart,
                shape = RoundedCornerShape(30.dp),
                color = TrialLime,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Coba GRATIS & Miliki Selamanya",
                    color = TrialNavy,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                )
            }
            Spacer(Modifier.height(navBarHeight + 24.dp))
        }
    }
}

@Composable
private fun TrialCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(TrialCardTop, TrialCardBottom)), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Mulai dengan 3 Hari\nBebas Biaya!",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 25.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth()) {
            // The connector runs between the first and the last circle centre (1/6 .. 5/6 of the
            // row), behind the circles, and warms from navy to lime like the mockup.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = StepCircle / 2 - 1.5.dp)
                    .fillMaxWidth(2f / 3f)
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0B3B75), TrialLime)), RoundedCornerShape(2.dp)),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TrialSteps.forEachIndexed { index, step -> TrialStepColumn(step, isLast = index == TrialSteps.lastIndex) }
            }
        }
    }
}

/** The first two steps are white discs with a navy icon; the last one (the purchase) is a navy disc
 *  ringed in lime so it reads as the destination. */
@Composable
private fun RowScope.TrialStepColumn(step: TrialStep, isLast: Boolean) {
    Column(Modifier.weight(1f).padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(StepCircle)
                .background(if (isLast) TrialCardBottom else Color.White, CircleShape)
                .then(if (isLast) Modifier.border(2.dp, TrialLime, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(step.icon),
                contentDescription = null,
                tint = if (isLast) Color.White else TrialNavy,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(step.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(
            step.body,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}
