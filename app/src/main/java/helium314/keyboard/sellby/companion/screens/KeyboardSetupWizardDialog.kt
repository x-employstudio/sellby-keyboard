// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.sellby.companion.theme.SellbyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sellby-branded port of HeliBoard's own `WelcomeWizard.kt` (helium314.keyboard.settings) - same
 *  3-step enable/switch/done state machine and the same 2 detection mechanisms (an activity-result
 *  callback for step 1's return from system Settings, a 50ms `isThisImeCurrent` poll for step 2's
 *  return from the IME picker), but renumbered 1/2/3 with no splash gate (this is opened from an
 *  already-explicit "Aktifkan" tap on the Dashboard, so a second "Get started" gate would just be an
 *  extra tap), Sellby teal instead of HeliBoard's hardcoded blue `R.color.setup_*` resources, and
 *  fresh Indonesian copy instead of the shared `R.string.setup_*` strings. The original wizard and
 *  its resources are completely untouched by this - still reachable via the in-keyboard gear icon. */
@Composable
fun KeyboardSetupWizardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(context, imm) -> 1
        !UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm) -> 2
        else -> 3
    }

    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope { Dispatchers.IO }

    LaunchedEffect(step) {
        if (step == 2) {
            scope.launch {
                while (step == 2 && !UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)) {
                    delay(50)
                }
                step = 3
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        step = determineStep()
    }

    Dialog(
        onDismissRequest = onDismiss,
        // A guided multi-step flow - an accidental scrim tap silently abandoning it mid-step would
        // be a worse failure mode than being over-eager to dismiss, so only the system back
        // button/gesture (dismissOnBackPress stays at its default true) closes this.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // A real Dialog owns its own Android Window, so back-press is caught by that window before
        // it can ever reach DashboardScreen's own BackHandler - unlike the in-tree TestKeyboardOverlay
        // (which needed an explicit enabled-BackHandler layered on top for that exact reason), no
        // extra back-handling wiring is needed here.
        //
        // Floating card (not full-screen) with margin on all 4 sides, same shape as
        // CustomDateRangeDialog's own Dialog further down this file: a plain Box with horizontal
        // padding wrapping a Surface, wrapping its content instead of filling the screen - Compose's
        // Dialog centers a non-fillMaxSize composable by default, so the Dashboard behind stays
        // visible (dimmed by the platform's own default dialog scrim) above/below/around the card.
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = SellbyColors.White, shadowElevation = 10.dp) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StepIndicatorRow(step)
                    Spacer(Modifier.height(28.dp))
                    when (step) {
                        1 -> WizardStepBlock(
                            icon = R.drawable.ic_setup_key,
                            title = "Aktifkan Sellby Keyboard",
                            instruction = "Nyalakan dulu Sellby Keyboard di pengaturan Bahasa & Masukan HP kamu, ya.",
                            actionLabel = "Buka Pengaturan",
                            onAction = {
                                try {
                                    launcher.launch(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT))
                                } catch (_: Exception) { /* no settings screen resolvable - nothing more we can do here */ }
                            },
                        )
                        2 -> WizardStepBlock(
                            icon = R.drawable.ic_setup_select,
                            title = "Pilih Sellby sebagai Keyboard Aktif",
                            instruction = "Sellby udah aktif! Sekarang tinggal pilih Sellby sebagai keyboard yang kamu pakai.",
                            actionLabel = "Pilih Keyboard",
                            onAction = { imm.showInputMethodPicker() },
                        )
                        else -> WizardStepBlock(
                            icon = R.drawable.ic_setup_check,
                            title = "Selesai, Sellby siap dipakai!",
                            instruction = "Sellby Keyboard sudah aktif dan siap kamu gunakan di semua aplikasi chat favoritmu.",
                            actionLabel = "Selesai",
                            onAction = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicatorRow(step: Int) {
    Row(Modifier.fillMaxWidth(0.6f), verticalAlignment = Alignment.CenterVertically) {
        for (index in 1..3) {
            StepCircle(number = index, filled = index <= step)
            if (index != 3) {
                Spacer(
                    Modifier.weight(1f).height(2.dp)
                        .background(if (index < step) SellbyColors.TealPrimary else SellbyColors.SurfaceMuted),
                )
            }
        }
    }
}

@Composable
private fun StepCircle(number: Int, filled: Boolean) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (filled) SellbyColors.TealPrimary else SellbyColors.SurfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            color = if (filled) SellbyColors.White else SellbyColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WizardStepBlock(icon: Int, title: String, instruction: String, actionLabel: String, onAction: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(SellbyColors.TealPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SellbyColors.White),
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(title, color = SellbyColors.TextDark, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(instruction, color = SellbyColors.TextMuted, fontSize = 13.5.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
        Spacer(Modifier.height(28.dp))
        Surface(onClick = onAction, shape = RoundedCornerShape(28.dp), color = SellbyColors.TealPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(
                actionLabel,
                color = SellbyColors.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
    }
}
