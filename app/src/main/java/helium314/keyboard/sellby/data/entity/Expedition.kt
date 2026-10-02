// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// Fields match sellby_keyboard.dart's ExpeditionModel (settings_panel.dart) - just the persisted
// enable/disable state per courier. The courier identity/deep-link data itself is a compile-time
// constant list (see ExpeditionCatalog.kt, ported from ongkir_panel.dart's ExpeditionItem list),
// not user data - this table only tracks which catalog ids the user has enabled.
@Entity(tableName = "expedition")
data class Expedition(
    @PrimaryKey val id: String,
    val isEnabled: Boolean = true,
)
