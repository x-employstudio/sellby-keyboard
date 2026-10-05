// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.PREF_ONBOARDING_COMPLETED
import helium314.keyboard.sellby.companion.billing.BillingRepository
import helium314.keyboard.sellby.companion.billing.LaunchResult
import helium314.keyboard.sellby.companion.billing.ProductState
import helium314.keyboard.sellby.companion.theme.SellbyColors
import kotlinx.coroutines.launch

// Matches the mockup the user supplied directly (not derived from any Flutter reference - this
// whole page is new). The blue gradient and the bright lime CTA are page-local, not added to
// SellbyColors: the gradient exists only on this one screen, and the lime green mirrors the accent
// color already used elsewhere in this app for a "positive primary action" pill (Produk panel's own
// "+ Tambah Produk" button).
private val BillingGradientTop = Color(0xFF3D82C4)
private val BillingGradientBottom = Color(0xFF0B2247)
private val BillingLimeGreen = Color(0xFF8CE623)

/** The purchase page: the one-time "sellby_premium" product, bought through Google Play Billing
 *  ([BillingRepository]). The price is Play's own localized text (never a picture), so it is always the price the
 *  user will really be charged. The illustration is the user's own prepared PNG (`image_billing.png`, copied into
 *  res/drawable-nodpi as sellby_billing_illustration).
 *
 *  States: the price is loading / failed (with "Coba lagi") / ready; "Beli Sekarang" opens Play's purchase
 *  sheet; a PENDING payment (minimarket, bank transfer...) shows a banner and unlocks by itself once it
 *  completes; "Pulihkan pembelian" re-checks Play (reinstall, a new phone); once premium is on, the button
 *  turns into a thank-you.
 *
 *  This page appears once the 3-day free trial has run out. [cameFromLockedFeature] toggles the bottom pill's
 *  copy AND behavior: reached from the app itself (app start after the trial, or onboarding after a data reset
 *  past the trial) it reads "Lanjutkan ke dashboard" and goes on to the Dashboard; reached from the keyboard
 *  because a locked toolbar tab was tapped (CompanionLauncher) it reads "Kembali" and just closes the app back
 *  to wherever the user came from, with no onboarding flag to touch. */
@Composable
fun PurchaseScreen(onContinue: () -> Unit, cameFromLockedFeature: Boolean = false) {
    val context = LocalContext.current
    val repository = remember { BillingRepository.get(context) }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LaunchedEffect(Unit) { repository.loadProduct() }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BillingGradientTop, BillingGradientBottom)))) {
        // Scrollable so the extra states (pending banner, restore link) still fit on small phones; the
        // bottom pill is pinned below and this column leaves room for it.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(statusBarHeight + 16.dp))
            Image(
                painter = painterResource(R.drawable.sellby_billing_illustration),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(1f),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 84.dp), contentAlignment = Alignment.Center) {
                when (val product = state.product) {
                    ProductState.Loading ->
                        Text("Memuat harga...", color = SellbyColors.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    is ProductState.Ready -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            product.formattedPrice,
                            color = SellbyColors.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                        )
                        Text("sekali bayar, tanpa langganan", color = SellbyColors.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                    ProductState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Harga belum bisa dimuat",
                            color = SellbyColors.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "Coba lagi",
                            color = SellbyColors.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { scope.launch { repository.loadProduct() } }
                                .padding(12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
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

            val canBuy = !state.premium && state.product is ProductState.Ready && !state.purchasing
            Surface(
                onClick = {
                    val activity = context.findActivity()
                    if (activity == null) {
                        toast("Pembelian tidak bisa dibuka dari layar ini.")
                    } else {
                        scope.launch {
                            when (repository.launchPurchase(activity)) {
                                LaunchResult.NotReady -> toast("Google Play belum siap. Coba lagi sebentar lagi.")
                                LaunchResult.Failed -> toast("Pembelian tidak bisa dimulai. Silakan coba lagi.")
                                LaunchResult.Started, LaunchResult.AlreadyOwned -> Unit
                            }
                        }
                    }
                },
                enabled = canBuy,
                shape = RoundedCornerShape(30.dp),
                color = if (state.premium) Color.White.copy(alpha = 0.92f) else BillingLimeGreen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.premium -> "Premium aktif - terima kasih!"
                        state.purchasing -> "Membuka Google Play..."
                        else -> "Beli Sekarang"
                    },
                    color = BillingGradientBottom.copy(alpha = if (canBuy || state.premium || state.purchasing) 1f else 0.45f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                )
            }

            if (state.pending && !state.premium) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Pembayaranmu sedang diproses. Semua fitur terbuka otomatis begitu pembayaran selesai.",
                        color = SellbyColors.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            if (state.purchaseFailed && !state.premium) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pembelian belum berhasil. Silakan coba lagi.",
                    color = SellbyColors.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (!state.premium) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.checking) "Memeriksa pembelian..." else "Pulihkan pembelian",
                    color = SellbyColors.White.copy(alpha = 0.85f),
                    fontSize = 13.5.sp,
                    textDecoration = if (state.checking) TextDecoration.None else TextDecoration.Underline,
                    modifier = Modifier
                        .clickable(enabled = !state.checking) {
                            scope.launch {
                                val answered = repository.reconcile()
                                val now = repository.state.value
                                toast(
                                    when {
                                        !answered -> "Tidak bisa terhubung ke Google Play. Periksa koneksi internetmu."
                                        now.premium -> "Pembelian dipulihkan. Terima kasih!"
                                        now.pending -> "Pembayaranmu masih diproses."
                                        else -> "Belum ada pembelian yang ditemukan di akun Google ini."
                                    }
                                )
                            }
                        }
                        .padding(10.dp),
                )
            }
            // Room for the pinned pill below.
            Spacer(Modifier.height(96.dp))
        }
        Surface(
            onClick = {
                if (!cameFromLockedFeature) {
                    context.prefs().edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply()
                }
                onContinue()
            },
            shape = RoundedCornerShape(30.dp),
            color = SellbyColors.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).fillMaxWidth(0.62f),
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
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
