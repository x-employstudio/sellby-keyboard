// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.PREF_STORE_ADDRESS
import helium314.keyboard.sellby.companion.PREF_STORE_NAME
import helium314.keyboard.sellby.companion.PREF_STORE_PHONE
import helium314.keyboard.sellby.companion.components.TopToastHost
import helium314.keyboard.sellby.companion.components.rememberTopToastState
import helium314.keyboard.sellby.companion.theme.SellbyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ported from profile_setup_page.dart: 3 pill fields (Nama/Alamat/Nomor Telepon Toko), saved to
 *  the SAME SharedPreferences keys the in-keyboard Settings > Profil Toko already reads/writes
 *  (SettingsPanelView.kt/InvoicePanelView.kt) - one source of truth, not a separate copy. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfilTokoScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val toast = rememberTopToastState()
    val scope = rememberCoroutineScope()
    val prefs = remember { context.prefs() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    var name by remember { mutableStateOf(prefs.getString(PREF_STORE_NAME, "") ?: "") }
    var address by remember { mutableStateOf(prefs.getString(PREF_STORE_ADDRESS, "") ?: "") }
    var phone by remember { mutableStateOf(prefs.getString(PREF_STORE_PHONE, "") ?: "") }
    var isSaving by remember { mutableStateOf(false) }

    fun save() {
        val trimmedName = name.trim()
        val trimmedAddress = address.trim()
        val trimmedPhone = phone.trim()
        when {
            trimmedName.isEmpty() -> { toast.show("Nama toko wajib diisi!"); return }
            trimmedAddress.isEmpty() -> { toast.show("Alamat toko wajib diisi!"); return }
            trimmedPhone.isEmpty() -> { toast.show("Nomor telepon toko wajib diisi!"); return }
        }
        isSaving = true
        scope.launch {
            prefs.edit()
                .putString(PREF_STORE_NAME, trimmedName)
                .putString(PREF_STORE_ADDRESS, trimmedAddress)
                .putString(PREF_STORE_PHONE, trimmedPhone)
                .apply()
            delay(400)
            isSaving = false
            onSaved()
        }
    }

    // The keyboard field ("Nomor Telepon Toko") used to end up hidden behind the IME when focused -
    // windowSoftInputMode="adjustResize" on CompanionActivity doesn't actually resize the window
    // once the app draws edge-to-edge (confirmed by the same status-bar-inset workaround PurchaseScreen
    // needed), so the IME has to be handled explicitly here. imePadding() on the outer box shrinks the
    // available height by the keyboard's height; heightIn(min=availableHeight) + verticalScroll means
    // if the shrunk space doesn't fit the form, the column scrolls instead of clipping - the focused
    // TextField's built-in bring-into-view behavior then scrolls it above the keyboard automatically,
    // no manual BringIntoViewRequester needed.
    // NOTE: this used to read the available height from BoxWithConstraints' `maxHeight` - that's
    // backed by SubcomposeLayout, which forces a FULL subcomposition of this whole form on every
    // single frame of the IME's slide animation (maxHeight changes every frame since imePadding()
    // animates smoothly with it) - that was the actual lag. Swapping to a flat
    // LocalConfiguration.screenHeightDp "fixed" the lag but broke the shrink-with-keyboard behavior
    // itself (Arrangement.Center then centers the form within the FULL unshrunk screen height even
    // while the keyboard is open, pushing the Simpan button's scroll position below the keyboard).
    // The fix that keeps both properties: compute the shrunk height ourselves from
    // WindowInsets.ime's animated inset value directly - reading a WindowInsets value only causes a
    // cheap recomposition (same as any other observed state, no different from typing itself
    // recomposing this function on every keystroke), not the expensive subcomposition
    // BoxWithConstraints was doing.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val imeHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val availableHeight = screenHeight - imeHeight
    Box(Modifier.fillMaxSize().background(SellbyColors.TealDark).imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = availableHeight)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Hidden while the keyboard is open - this is the block that used to collide with the
            // status bar once the shrunk available height pushed everything up (see the imePadding
            // fix above); reappears the moment the keyboard closes. Animated (fade + height) rather
            // than a plain `if` - imePadding() itself already eases smoothly with the system's IME
            // insets animation, so an instant pop-in/out here read as a jarring stutter against that
            // smooth motion; matching durations makes the whole thing feel like one continuous move.
            AnimatedVisibility(
                visible = !imeVisible,
                enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Hampir Selesai",
                        color = SellbyColors.White,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Satu langkah lagi! Isi profil toko biar semua fitur Sellby siap dipakai sesuai identitas bisnismu.",
                        color = SellbyColors.White,
                        fontSize = 13.5.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(42.dp))
                }
            }
            Text(
                "Lengkapi profil Toko",
                color = SellbyColors.White,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))

            PillField(
                value = name,
                onValueChange = { name = it },
                hint = "Nama Toko",
                capitalizeWords = true,
                imeAction = ImeAction.Next,
                onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
            )
            Spacer(Modifier.height(14.dp))
            PillField(
                value = address,
                onValueChange = { address = it },
                hint = "Alamat Toko",
                capitalizeWords = false,
                imeAction = ImeAction.Next,
                onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
            )
            Spacer(Modifier.height(14.dp))
            PillField(
                value = phone,
                onValueChange = { new -> phone = new.filter { it.isDigit() } },
                hint = "Nomor Telepon Toko",
                isPhone = true,
                imeAction = ImeAction.Done,
                onImeAction = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (!isSaving) save() },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SellbyColors.White,
                    contentColor = SellbyColors.TealDark,
                    disabledContainerColor = SellbyColors.White,
                ),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(0.68f).padding(bottom = 24.dp, top = 16.dp),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.2.dp,
                        color = SellbyColors.TealDark,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text("Simpan & Buka Aplikasi", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        TopToastHost(toast, Modifier.align(Alignment.TopCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    capitalizeWords: Boolean = false,
    isPhone: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(hint, fontSize = 13.5.sp, color = Color(0xFF8E8E8E)) },
        singleLine = true,
        shape = RoundedCornerShape(30.dp),
        leadingIcon = if (isPhone) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Text("+62", color = Color(0xFF757575), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(1.2.dp).height(18.dp).background(Color(0xFFBDBDBD)))
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPhone) KeyboardType.Phone else KeyboardType.Text,
            capitalization = if (capitalizeWords) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFDCDCDC),
            unfocusedContainerColor = Color(0xFFDCDCDC),
            disabledContainerColor = Color(0xFFDCDCDC),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = SellbyColors.TextDark,
            unfocusedTextColor = SellbyColors.TextDark,
            cursorColor = SellbyColors.TealDark,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
