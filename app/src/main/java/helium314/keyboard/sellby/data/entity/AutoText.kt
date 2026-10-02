// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// Fields match sellby_keyboard.dart's AutoTextModel exactly (auto_text_panel.dart) - acuan tunggal.
@Entity(tableName = "auto_text")
data class AutoText(
    @PrimaryKey val id: String,
    val shortcut: String,
    val message: String,
    val isDefault: Boolean = false,
) {
    companion object {
        const val FORM_ORDER_ID = "at_1"
        const val FORM_ORDER_MESSAGE = "Form Order\nNama :\nNo Hp :\nAlamat :\nOrder :"
        // Every earlier factory text of this row (oldest first). Only used to recognise a row that
        // is still exactly one of them, so it can be upgraded in place (see
        // AutoTextDao.upgradeFactoryMessage) without touching one the user has edited.
        val FORM_ORDER_MESSAGES_LEGACY = listOf(
            "Form Order\nNama :\nNo Hp :\nOrder :\nMetode Pembayaran :",
            "Form Order\nNama :\nNo Hp :\nAlamat :\nOrder :\nMetode Pembayaran :",
        )

        // Single source of truth for the 3 factory-default rows, used by both SellbyDatabase's
        // first-run seed and AutoTextPanelView's "Reset Semua" (avoids the 3 literal rows drifting
        // apart if edited in only one place). #nama-toko stays a literal token here - resolved only
        // at send-to-chat time, never persisted resolved (see AutoTextPanelView.resolveTokens()).
        fun defaultSeedRows(): List<AutoText> = listOf(
            AutoText(FORM_ORDER_ID, "Format Order", FORM_ORDER_MESSAGE, isDefault = true),
            AutoText("at_2", "Hallo",
                "Halo Kak, selamat datang di #nama-toko! Ada yang bisa kami bantu hari ini?", isDefault = true),
            AutoText("at_3", "Terima kasih",
                "Terima Kasih telah berbelanja di #nama-toko!", isDefault = true),
        )
    }
}
