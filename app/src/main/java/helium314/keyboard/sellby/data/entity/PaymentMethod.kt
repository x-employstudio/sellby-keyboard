// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// Fields match sellby_keyboard.dart's PaymentMethodModel exactly (settings_panel.dart, the
// persisted master record - NOT PaymentMethodItem in invoice_panel.dart, which is a non-persisted
// UI picker item only) - acuan tunggal.
@Entity(tableName = "payment_method")
data class PaymentMethod(
    @PrimaryKey val id: String,
    val name: String,
    val accountNumber: String,
    val bankType: String,
    val qrisImagePath: String? = null,
    val isActive: Boolean = true,
)
