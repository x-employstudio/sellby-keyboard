// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.PREF_ONBOARDING_COMPLETED
import helium314.keyboard.sellby.companion.theme.SellbyColors

// Matches the mockup the user supplied directly (not derived from any Flutter reference - this
// whole page is new, per PurchaseScreen's own long-standing Round-1-placeholder history). The blue
// gradient and the bright lime CTA are page-local, not added to SellbyColors: the gradient exists
// only on this one screen, and the lime green mirrors the accent color already used elsewhere in
// this app for a "positive primary action" pill (Produk panel's own "+ Tambah Produk" button).
private val BillingGradientTop = Color(0xFF3D82C4)
private val BillingGradientBottom = Color(0xFF0B2247)
private val BillingLimeGreen = Color(0xFF8CE623)

/** Round 1: the illustration and stylized price are the user's own prepared PNGs (`image_billing.png`/
 *  `price_billing.png`, copied into res/drawable-nodpi as sellby_billing_illustration/sellby_billing_price)
 *  - not built piece-by-piece here. "Beli Sekarang" has no real Google Play Billing behind it yet
 *  (disclosed placeholder toast) - Round 2 replaces that with the actual purchase flow once the
 *  Play Developer account + Play Console product are ready; the route/nav position stay the same.
 *
 *  [cameFromLockedFeature] toggles the bottom pill's copy AND behavior: reached from onboarding (the
 *  only entry point that exists today) it reads "Lanjutkan ke dashboard" and finishes onboarding;
 *  reached later from tapping a locked toolbar feature (Round 2's "kunci fitur" work, not wired up
 *  yet) it reads "Kembali" and just dismisses back to wherever the user came from, with no
 *  onboarding flag to touch. */
@Composable
fun PurchaseScreen(onContinue: () -> Unit, cameFromLockedFeature: Boolean = false) {
    val context = LocalContext.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BillingGradientTop, BillingGradientBottom)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Small fixed top clearance (not a flex spacer) - the illustration/price/copy/divider/
            // Beli-Sekarang group sits right below it instead of being pushed down by a large empty
            // top gap. The flex spacer that used to live here now lives below the Beli Sekarang
            // button instead (see below), so it's the gap ABOVE the white bottom pill that grows -
            // the pill's own distance from the screen's bottom edge is unchanged (still the fixed
            // 28dp spacer at the very end), matching the request that it stay put.
            Spacer(Modifier.height(statusBarHeight + 16.dp))
            Image(
                painter = painterResource(R.drawable.sellby_billing_illustration),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(1f),
            )
            Spacer(Modifier.height(4.dp))
            Image(
                painter = painterResource(R.drawable.sellby_billing_price),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Sekali Bayar\nSemua Manfaat Untukmu\nSelamanya!",
                color = SellbyColors.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(18.dp))
            Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                )
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = {
                    Toast.makeText(context, "Pembayaran akan segera hadir di update berikutnya!", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(30.dp),
                color = BillingLimeGreen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Beli Sekarang",
                    color = BillingGradientBottom,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = {
                    if (!cameFromLockedFeature) {
                        context.prefs().edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply()
                    }
                    onContinue()
                },
                shape = RoundedCornerShape(30.dp),
                color = SellbyColors.White,
                modifier = Modifier.fillMaxWidth(0.62f),
            ) {
                Text(
                    if (cameFromLockedFeature) "Kembali" else "Lanjutkan ke dashboard",
                    color = SellbyColors.TextMuted,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
