// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.PREF_PREMIUM_NOT_OWNED_SINCE
import helium314.keyboard.sellby.companion.PREF_PREMIUM_PENDING
import helium314.keyboard.sellby.companion.PREF_PREMIUM_PRICE_CACHE
import helium314.keyboard.sellby.companion.PREF_PREMIUM_PURCHASED

/** Where [BillingRepository] keeps the [Entitlement]. An interface so the repository can be tested without
 *  Android storage. */
interface EntitlementStorage {
    fun load(): Entitlement
    fun save(entitlement: Entitlement)
}

/** Remembers the last price Play reported, so the purchase page can show it before Play answers. */
interface PriceCache {
    fun loadPrice(): String?
    fun savePrice(price: String)
}

/** The ONLY writer of the premium flag ([PREF_PREMIUM_PURCHASED]) - a test keeps it that way. The keyboard's
 *  feature lock and the companion screens read the flag through TrialPolicy, from the same shared preferences,
 *  so a purchase unlocks the keyboard the moment it is saved here (no Play call anywhere near typing). */
class EntitlementStore(context: Context) : EntitlementStorage, PriceCache {
    private val appContext = context.applicationContext

    override fun load(): Entitlement {
        val prefs = appContext.prefs()
        return Entitlement(
            premium = prefs.getBoolean(PREF_PREMIUM_PURCHASED, false),
            pending = prefs.getBoolean(PREF_PREMIUM_PENDING, false),
            notOwnedSinceMillis = prefs.getLong(PREF_PREMIUM_NOT_OWNED_SINCE, 0L),
        )
    }

    override fun loadPrice(): String? = appContext.prefs().getString(PREF_PREMIUM_PRICE_CACHE, null)?.takeIf { it.isNotBlank() }

    override fun savePrice(price: String) {
        appContext.prefs().edit { putString(PREF_PREMIUM_PRICE_CACHE, price) }
    }

    override fun save(entitlement: Entitlement) {
        appContext.prefs().edit {
            putBoolean(PREF_PREMIUM_PURCHASED, entitlement.premium)
            putBoolean(PREF_PREMIUM_PENDING, entitlement.pending)
            putLong(PREF_PREMIUM_NOT_OWNED_SINCE, entitlement.notOwnedSinceMillis)
        }
    }
}
