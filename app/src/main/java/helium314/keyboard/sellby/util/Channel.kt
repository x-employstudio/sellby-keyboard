// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

import androidx.room.TypeConverter
import helium314.keyboard.latin.R

/** How a channel's contact field should be typed/validated - drives the field's UI treatment
 *  (prefix shown, keyboard layout, live digit-filtering) in InvoicePanelView/SettingsPanelView. */
enum class IdentifierKind {
    /** WhatsApp / WhatsApp Business - "+62"-prefixed, digit-only, numeric physical keyboard. */
    PHONE,
    /** Instagram - "@"-prefixed free text, no phone formatting/validation at all. */
    USERNAME,
    /** Telegram - a single free-text field accepting EITHER a phone number (t.me/+<number>,
     *  confirmed by the user via their own device test) OR a @username (t.me/<username>) - no
     *  fixed prefix shown since either is valid; ChannelMessenger.openChat() auto-detects which
     *  one was entered when it actually opens the chat. */
    PHONE_OR_USERNAME,
}

/** The 4 messaging channels a customer can be reached on. Default is WHATSAPP (matches this
 *  app's pre-existing WhatsApp-only behavior exactly, so every customer/order that existed before
 *  this feature keeps behaving identically). [id] is the literal string persisted to Room via
 *  [ChannelConverters] - kept as an explicit field (not just `.name`) so DB storage never silently
 *  changes if the enum constants are ever reordered. */
enum class Channel(
    val id: String,
    val displayLabel: String,
    val fieldLabel: String,
    val requiredFieldName: String,
    val messageLineLabel: String,
    val identifierKind: IdentifierKind,
    val iconRes: Int,
    /** The Android package that actually handles this channel's chat app - used by
     *  ChannelMessenger.openChat() to match against EditorInfo.packageName once the IME reattaches
     *  after an app-switch (see KeyboardSwitcher.commitSellbyTextWhenPackageFocused()), so a
     *  deferred text commit only fires once we're genuinely connected to THIS app's input field,
     *  not whatever was focused before the switch. */
    val packageName: String,
) {
    WHATSAPP(
        "WHATSAPP", "WhatsApp", "Nomor Whatsapp*", "Nomor WhatsApp", "No. Telepon",
        IdentifierKind.PHONE, R.drawable.ic_channel_whatsapp_sellby, "com.whatsapp",
    ),
    WHATSAPP_BUSINESS(
        "WHATSAPP_BUSINESS", "WhatsApp Business", "Nomor WhatsApp Business*", "Nomor WhatsApp Business", "No. WA Business",
        IdentifierKind.PHONE, R.drawable.ic_channel_whatsapp_business_sellby, "com.whatsapp.w4b",
    ),
    TELEGRAM(
        "TELEGRAM", "Telegram", "Nomor HP / Username Telegram*", "Nomor/Username Telegram", "Kontak Telegram",
        IdentifierKind.PHONE_OR_USERNAME, R.drawable.ic_channel_telegram_sellby, "org.telegram.messenger",
    ),
    INSTAGRAM(
        "INSTAGRAM", "Instagram", "Username Instagram*", "Username Instagram", "Username Instagram",
        IdentifierKind.USERNAME, R.drawable.ic_channel_instagram_sellby, "com.instagram.android",
    );

    companion object {
        val DEFAULT = WHATSAPP
        fun fromId(id: String?): Channel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Wraps [text] in this channel's own bold markdown syntax for a message that will be inserted
 *  as a whole block (InputConnection.commitText, not typed key-by-key) into that channel's chat
 *  compose box. WhatsApp/WhatsApp Business: single asterisk (confirmed working in this app).
 *  Telegram: double asterisk is the officially documented client syntax - NOT verified yet that
 *  bulk-inserted text (vs. character-by-character typing) actually triggers Telegram's live
 *  markdown conversion, see the plan's device-testing note; low-risk optimistic default (worst
 *  case shows literal "**text**", not broken/corrupted). Instagram DMs have no rich-text
 *  formatting at all (confirmed), so the markers are dropped entirely rather than left visible. */
fun Channel.bold(text: String): String = when (this) {
    Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS -> "*$text*"
    Channel.TELEGRAM -> "**$text**"
    Channel.INSTAGRAM -> text
}

/** Same idea as [bold] but for strikethrough (used for a crossed-out original price next to a
 *  discounted one). WhatsApp: single tilde. Telegram: double tilde (officially documented).
 *  Instagram: no formatting, dropped. */
fun Channel.strike(text: String): String = when (this) {
    Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS -> "~$text~"
    Channel.TELEGRAM -> "~~$text~~"
    Channel.INSTAGRAM -> text
}

class ChannelConverters {
    @TypeConverter
    fun fromChannel(value: Channel): String = value.id

    @TypeConverter
    fun toChannel(value: String): Channel = Channel.fromId(value)
}
