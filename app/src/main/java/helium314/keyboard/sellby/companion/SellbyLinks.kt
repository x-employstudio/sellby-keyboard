// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

/** The web addresses the app points to. Set once when the public repository and the public site exist;
 *  scripts/check-release.ps1 refuses a release build while either one is still empty: the GPL requires offering
 *  the source of exactly what is shipped, and Google Play requires a privacy policy link that works. */
object SellbyLinks {
    /** The public repository with the complete source, e.g. `https://github.com/<owner>/<repo>` (no trailing slash). */
    const val SOURCE_URL = "https://github.com/x-employstudio/sellby-keyboard"

    /** The public site that hosts the privacy policy and the support page: Firebase Hosting, project `sellby-keyboard`,
     *  serving the `docs/` folder (see firebase.json; update it with `firebase deploy --only hosting`). No trailing slash. */
    const val SITE_URL = "https://sellby-keyboard.web.app"

    const val SUPPORT_EMAIL = "x.employstudio@gmail.com"

    fun licenseUrl(): String? = under(SOURCE_URL, "/blob/main/LICENSE")
    fun noticesUrl(): String? = under(SOURCE_URL, "/blob/main/THIRD_PARTY_NOTICES.md")
    fun privacyUrl(): String? = under(SITE_URL, "/privacy.html")
    fun supportUrl(): String? = under(SITE_URL, "/support.html")

    /** [base] + [path], or null while [base] is not set. */
    internal fun under(base: String, path: String): String? =
        base.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.plus(path)
}
