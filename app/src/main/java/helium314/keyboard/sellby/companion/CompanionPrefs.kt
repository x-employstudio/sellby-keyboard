// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

// Sellby companion app (Fase 5). Same SharedPreferences instance the keyboard side uses
// (context.prefs() -> DeviceProtectedUtils.getSharedPreferences) - "store_name"/"store_address"/
// "store_phone" are the EXISTING keys read/written by SettingsPanelView.kt/InvoicePanelView.kt
// (Profil Toko inside the keyboard), reused here as-is so both surfaces share one source of truth.
const val PREF_STORE_NAME = "store_name"
const val PREF_STORE_ADDRESS = "store_address"
const val PREF_STORE_PHONE = "store_phone"

// New flag, companion-app-only: whether Loading should skip straight to Dashboard.
const val PREF_ONBOARDING_COMPLETED = "sellby_onboarding_completed"

// Whether the user bought "sellby_premium". The keyboard (toolbar feature lock) and the companion screens only
// READ it, through TrialPolicy. Exactly one class writes it: billing/EntitlementStore, from what Google Play says
// (BillingRepository). It is a local cache of the purchase, re-checked with Play on every companion app start.
// DELIBERATELY NOT removed by performDeleteAll()'s reset (SettingsPanelView.kt): a purchase must survive "Hapus
// Semua Data". The two siblings below belong to the same cache and follow the same rule.
const val PREF_PREMIUM_PURCHASED = "sellby_premium_purchased"
/** A payment for premium is waiting to be completed (pending purchase: minimarket, bank transfer...). */
const val PREF_PREMIUM_PENDING = "sellby_premium_pending"
/** Wall-clock time of the first Play check that did NOT list the purchase while premium was on; 0 = none. */
const val PREF_PREMIUM_NOT_OWNED_SINCE = "sellby_premium_not_owned_since"

// Free trial (see TrialPolicy). Like PREF_PREMIUM_PURCHASED these are deliberately NOT removed by
// performDeleteAll() - "Hapus Semua Data" must not be a way to start a fresh trial.
const val PREF_TRIAL_START_MILLIS = "sellby_trial_start_millis"
/** Newest wall-clock reading we have ever seen; the trial clock never runs backwards past it. */
const val PREF_TRIAL_LAST_SEEN_MILLIS = "sellby_trial_last_seen_millis"
const val PREF_REVIEW_PROMPTED = "sellby_review_prompted"
