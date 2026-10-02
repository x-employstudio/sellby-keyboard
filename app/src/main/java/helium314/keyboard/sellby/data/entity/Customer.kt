// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import helium314.keyboard.sellby.util.Channel

// Fields match sellby_keyboard.dart's CustomerModel exactly (settings_panel.dart) - acuan tunggal,
// except [channel] (Sellby-only addition, no Flutter equivalent - see Channel.kt). [phone] holds
// a phone number for WHATSAPP/WHATSAPP_BUSINESS/TELEGRAM-as-phone, or a username for
// INSTAGRAM/TELEGRAM-as-username, depending on [channel] - repurposed rather than renamed to
// avoid a mechanical diff across every existing `.phone` reference for a purely cosmetic gain.
@Entity(tableName = "customer")
data class Customer(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val phone: String,
    val channel: Channel = Channel.WHATSAPP,
)
