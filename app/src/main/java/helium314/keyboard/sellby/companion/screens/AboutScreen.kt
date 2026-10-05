// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.SellbyLinks
import helium314.keyboard.sellby.companion.TrialPolicy
import helium314.keyboard.sellby.companion.TrialRules
import helium314.keyboard.sellby.companion.TrialState
import helium314.keyboard.sellby.companion.billing.BillingRepository
import helium314.keyboard.sellby.companion.theme.SellbyColors

private val PageBackground = Color(0xFFF1F5F9)

/** "Tentang & Lisensi": what Google Play and the GPL both expect to be easy to find - the version, the licence the
 *  app is under with a link to the complete source, credit for the work Sellby is built on, the libraries, and the
 *  privacy statement. Everything is plain local text; only the links leave the app (the system browser or mail app
 *  opens them), and a link whose address is not configured yet is simply not shown (a release build cannot get
 *  that far: scripts/check-release.ps1 fails while [SellbyLinks] is empty). */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenPurchase: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }
    val billing by remember { BillingRepository.get(context).state }.collectAsState()
    val trialState = remember { TrialPolicy.state(context) }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(PageBackground)) {
        Row(
            Modifier.fillMaxWidth().background(SellbyColors.TealDark)
                .padding(top = statusBarHeight).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "<",
                color = SellbyColors.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text("Tentang & Lisensi", color = SellbyColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.sellby_companion_logo),
                    contentDescription = null,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    "Versi ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = SellbyColors.TextMuted,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                )
            }

            AboutCard("Premium") {
                Body(
                    when {
                        billing.premium -> "Premium aktif. Terima kasih sudah mendukung Sellby!"
                        billing.pending -> "Pembayaranmu sedang diproses. Fitur terbuka otomatis begitu pembayaran selesai."
                        trialState is TrialState.Active -> "Masa coba gratis: hari ke-${trialState.dayIndex} dari ${TrialRules.TRIAL_DAYS}. " +
                            "Semua fitur terbuka. Beli sekali bayar untuk memakainya selamanya."
                        trialState is TrialState.Expired -> "Masa coba gratis sudah habis. Beli sekali bayar untuk membuka fitur panel lagi."
                        else -> "Semua fitur gratis dicoba 3 hari, setelah itu sekali bayar tanpa langganan."
                    }
                )
                if (!billing.premium) {
                    Surface(
                        onClick = onOpenPurchase,
                        shape = RoundedCornerShape(24.dp),
                        color = SellbyColors.TealPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Beli Premium / Pulihkan pembelian",
                            color = SellbyColors.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        )
                    }
                }
            }

            AboutCard("Perangkat lunak bebas (GPL-3.0)") {
                Body(
                    "Sellby Keyboard adalah perangkat lunak bebas berlisensi GNU General Public License v3.0. " +
                        "Kamu boleh mempelajari, mengubah, dan membagikannya, dengan syarat versi turunannya juga " +
                        "membuka kode sumbernya di bawah lisensi yang sama dan tetap mencantumkan pemberitahuan hak cipta. " +
                        "Perangkat lunak ini diberikan tanpa jaminan apa pun, sejauh diizinkan hukum."
                )
                LinkRow(context, "Kode sumber lengkap", SellbyLinks.SOURCE_URL.ifBlank { null })
                LinkRow(context, "Teks lisensi GPL-3.0", SellbyLinks.licenseUrl())
            }

            AboutCard("Dibangun di atas karya orang lain") {
                Body(
                    "Sellby adalah turunan dari HeliBoard (GPL-3.0), yang berbasis OpenBoard dan AOSP Keyboard / LatinIME " +
                        "(Apache 2.0). Parser layout berasal dari FlorisBoard (Apache 2.0). Perbaikan dari LineageOS, " +
                        "Simple Keyboard, dan Indic Keyboard ikut terbawa lewat HeliBoard. Terima kasih kepada semua kontributornya."
                )
                LinkRow(context, "Daftar lengkap pemberitahuan pihak ketiga", SellbyLinks.noticesUrl())
            }

            AboutCard("Pustaka yang dipakai") {
                Body(
                    "AndroidX, Jetpack Compose, Navigation, Room, kotlinx.serialization, dan reorderable (Apache 2.0); " +
                        "desugar_jdk_libs (GPL-2.0 dengan Classpath Exception); Google Play Billing, Play In-App Review, " +
                        "dan Block Store (SDK Google, tunduk pada ketentuan Google)."
                )
            }

            AboutCard("Kamus kata") {
                Body("Versi ini tidak mengemas kamus kata bawaan HeliBoard, jadi tidak ada saran kata atau koreksi otomatis dari kamus.")
            }

            AboutCard("Merek dagang") {
                Body(
                    "Nama, logo, dan maskot Sellby milik pemilik proyek dan tidak dilisensikan untuk dipakai ulang oleh versi turunan. Logo dan nama bank, e-wallet, kurir, serta aplikasi chat " +
                        "(WhatsApp, Telegram, Instagram) yang tampil di aplikasi milik pemiliknya masing-masing dan dipakai " +
                        "hanya untuk menunjukkan layanan yang dimaksud; Sellby tidak berafiliasi dengan mereka."
                )
            }

            AboutCard("Privasi") {
                Body(
                    "Teks yang kamu ketik diproses hanya di perangkatmu dan tidak dikirim ke server mana pun. Data pelanggan, " +
                        "pesanan, produk, dan rekening disimpan di perangkatmu. Pembelian ditangani oleh Google Play."
                )
                LinkRow(context, "Kebijakan Privasi", SellbyLinks.privacyUrl())
            }

            AboutCard("Kontak") {
                Body("Pertanyaan atau bantuan: ${SellbyLinks.SUPPORT_EMAIL}")
                LinkRow(context, "Kirim email ke dukungan", "mailto:${SellbyLinks.SUPPORT_EMAIL}")
            }
            Spacer(Modifier.height(12.dp + navBarHeight))
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SellbyColors.White)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = SellbyColors.TextDark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = Color(0xFF475569), fontSize = 12.5.sp, lineHeight = 18.sp)
}

/** A tappable teal link; not shown at all while [url] is not configured. */
@Composable
private fun LinkRow(context: Context, label: String, url: String?) {
    if (url == null) return
    Box(Modifier.fillMaxWidth().clickable { openLink(context, url) }.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            label,
            color = SellbyColors.TealPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        )
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No browser / mail app that can open it: nothing to do, the address is also in the repository README.
    }
}
