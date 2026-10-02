// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.input

import android.content.Context
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.entity.AutoText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Backing cache + matching rules for the Auto-Text typing-triggered suggestion strip
 * (sellby_keyboard.dart's _cachedAutoTexts/_getMatchingAutoTexts/_buildTopToolbarWidget, lines
 * ~1272-1438 - ported behavior, not code, since Flutter's own text field owns its full content
 * while Android only exposes a windowed read around the cursor for a REAL app's field).
 *
 * By request, this only ever watches REAL app typing (via RichInputConnection) - no Sellby panel
 * field currently has any use for it, so KeyboardActionListenerImpl never calls into this while a
 * panel field is focused. Matching is deliberately narrower than Flutter's 3-way `contains` too:
 * only [matchesForRealApp]'s "shortcut starts with what's typed" (not "message contains query" or
 * "query contains shortcut"). The query here comes from a bounded
 * RichInputConnection.getTextBeforeCursor() window (see KeyboardActionListenerImpl), so the exact
 * boundary of "what the user meant to type as a shortcut" is inherently fuzzy in a way Flutter's
 * fully-owned text field never had to deal with - restricting the match keeps exactly how many
 * characters get deleted on pick unambiguous and safe, instead of risking deletion of unrelated
 * text the user already typed in their real chat.
 */
object AutoTextSuggestionEngine {
    private var cached: List<AutoText> = emptyList()
    private var watchJob: Job? = null

    fun startWatching(context: Context) {
        if (watchJob != null) return
        val db = SellbyDatabase.getInstance(context.applicationContext)
        watchJob = CoroutineScope(Dispatchers.IO).launch {
            // Defensive re-seed: Settings' "Hapus Semua Data" wipes this table via
            // SellbyDatabase.clearAllTables(), which never re-triggers Room's one-shot onCreate
            // seed - leaving the 3 factory defaults gone for good otherwise. IGNORE-conflict means
            // an existing (possibly user-edited) row is left untouched; this only restores what's
            // actually missing. Runs once here (IME startup), harmless no-op when already present.
            db.autoTextDao().seedIfAbsent(AutoText.defaultSeedRows())
            // Seeding never rewrites an existing row, so a device that already has an older
            // "Format Order" factory text needs this in-place upgrade (no-op once upgraded or if edited).
            AutoText.FORM_ORDER_MESSAGES_LEGACY.forEach { legacy ->
                db.autoTextDao().upgradeFactoryMessage(AutoText.FORM_ORDER_ID, legacy, AutoText.FORM_ORDER_MESSAGE)
            }
            db.autoTextDao().getAll().collect { cached = it }
        }
    }

    fun matchesForRealApp(query: String): List<AutoText> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        return cached.filter { it.shortcut.lowercase().contains(q) }
    }

    fun resolveTokens(context: Context, message: String): String {
        val storeName = context.prefs().getString("store_name", "")?.ifEmpty { "Nama Toko" } ?: "Nama Toko"
        return message.replace("#nama-toko", storeName)
    }
}
