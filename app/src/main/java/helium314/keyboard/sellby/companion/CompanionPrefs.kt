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

// Round 2 (Billing) placeholder: apakah user sudah beli akses premium. Selalu false sampai
// integrasi Google Play Billing sungguhan (Round 2) benar-benar mengisi nilai ini. SENGAJA TIDAK
// pernah dihapus oleh performDeleteAll()'s reset (SettingsPanelView.kt) - pembelian harus tetap
// tersimpan walau data lain direset - dan inilah yang dicek CompanionNavHost buat skip Purchase.
const val PREF_PREMIUM_PURCHASED = "sellby_premium_purchased"
