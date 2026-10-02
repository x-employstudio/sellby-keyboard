// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import helium314.keyboard.keyboard.KeyboardSwitcher

/** Centralizes the "open a chat with this customer" logic that used to be 3 independent
 *  copy-pasted wa.me-opening functions (StatusPanelView.openCustomerChatHistory/
 *  sendTextMessageAndOpenWA, SettingsPanelView.openCustomerWhatsApp) - now channel-aware so each
 *  can redirect to WhatsApp, WhatsApp Business, Telegram, or Instagram depending on what channel
 *  the customer/order was saved with. */
object ChannelMessenger {

    /** Opens a chat targeting [rawIdentifier] on [channel]. [rawIdentifier] is whatever's stored
     *  on Customer.phone / Order.customerPhone: for WHATSAPP/WHATSAPP_BUSINESS, digits that may
     *  start with 0/62/8; for INSTAGRAM, a bare username (a leading '@' is stripped if present);
     *  for TELEGRAM (dual field, confirmed by the user's own device test), either a phone number
     *  OR a @username - auto-detected here by whether the identifier is purely digits.
     *
     *  Text delivery strategy differs per channel because only wa.me links can carry a message
     *  through the URL itself:
     *  - WhatsApp/WhatsApp Business: [text] is embedded as wa.me's own "?text=" parameter -
     *    WhatsApp itself fills its compose box with it once it opens/switches to that chat. This
     *    is entirely independent of this IME's own commit, so nothing further is done here.
     *  - Telegram/Instagram: t.me/ig.me links have no such parameter (confirmed via research) - the
     *    ONLY way to deliver [text] is this IME's own commit. BUG FIX: committing immediately
     *    after startActivity() raced the OS's async app-switch and always lost, landing the text
     *    wherever the IME was already connected BEFORE the switch (confirmed by on-device
     *    testing). Instead, when the deep-link launch succeeds, the commit is DEFERRED until
     *    KeyboardSwitcher detects the IME has actually reattached to that app's own input field
     *    (see KeyboardSwitcher.commitSellbyTextWhenPackageFocused()'s doc comment for the
     *    real reattach signal used - not a blind delay).
     *  - Any channel where the launch fails entirely (no app can handle the link) falls back to
     *    committing [text] immediately into whatever's currently focused, so the message isn't
     *    silently lost outright - matching this feature's original "always deliver somewhere"
     *    contract for genuine failures.
     *
     *  Returns true if an Activity was launched, false if every attempt failed (empty identifier,
     *  or no app could handle the intent) - this function shows no UI itself, callers keep their
     *  own distinct showToast() wording. */
    fun openChat(context: Context, channel: Channel, rawIdentifier: String, text: String? = null): Boolean {
        val baseUri = when (channel) {
            Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS -> {
                val target = normalizePhone(rawIdentifier)
                if (target.isEmpty()) return false
                "https://wa.me/$target" + (text?.let { "?text=${Uri.encode(it)}" } ?: "")
            }
            Channel.TELEGRAM -> {
                // A phone number typed as "0812..."/"62812..."/"812..." is ALL digits once
                // whitespace/+/-/() are stripped; a Telegram @username can never be purely
                // numeric (usernames must start with a letter), so this check never misfires
                // between the two cases.
                val strippedNonDigits = rawIdentifier.filterNot { it.isWhitespace() || it in "+-()" }
                if (strippedNonDigits.isNotEmpty() && strippedNonDigits.length >= 8 && strippedNonDigits.all { it.isDigit() }) {
                    val target = normalizePhone(rawIdentifier)
                    if (target.isEmpty()) return false
                    // "+" is REQUIRED here (confirmed by the user, who tested t.me/+<number> on
                    // their own device) - this is Telegram's documented phone-number link format,
                    // distinct from t.me/<username> below.
                    "https://t.me/+$target"
                } else {
                    val target = rawIdentifier.trim().removePrefix("@")
                    if (target.isEmpty()) return false
                    "https://t.me/$target"
                }
            }
            Channel.INSTAGRAM -> {
                val target = rawIdentifier.trim().removePrefix("@")
                if (target.isEmpty()) return false
                "https://ig.me/m/$target"
            }
        }
        // Explicit-package targeting is only needed for WhatsApp/WhatsApp Business, to disambiguate
        // between the 2 separate APKs that BOTH claim wa.me/api.whatsapp.com links when both are
        // installed (also fixes a previously-reported issue - "kenapa yg kebuka selalu WA
        // Business" - where resolution depended on the device's own default-app association,
        // outside this app's control). Telegram/Instagram each have exactly one app claiming their
        // domain, so an implicit intent is left to resolve via Android's normal (verified) App
        // Links routing instead of forcing a package, which risks NOT matching how that routing
        // actually negotiates.
        val explicitPkg = if (channel == Channel.WHATSAPP || channel == Channel.WHATSAPP_BUSINESS) channel.packageName else null
        val launched = if (explicitPkg != null && tryStartActivity(context, baseUri, explicitPkg)) {
            true
        } else {
            tryStartActivity(context, baseUri, targetPackage = null)
        }
        if (text != null) {
            when {
                channel == Channel.WHATSAPP || channel == Channel.WHATSAPP_BUSINESS -> { /* delivered via ?text=, nothing more to do */ }
                launched -> KeyboardSwitcher.getInstance().commitSellbyTextWhenPackageFocused(text, channel.packageName)
                else -> KeyboardSwitcher.getInstance().commitSellbyText(text)
            }
        }
        return launched
    }

    private fun tryStartActivity(context: Context, uri: String, targetPackage: String?): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                if (targetPackage != null) setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("0") -> "62" + digits.substring(1)
            digits.startsWith("8") -> "62$digits"
            else -> digits
        }
    }
}
