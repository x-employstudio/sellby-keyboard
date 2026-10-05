// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import android.content.Context
import android.content.Intent
import helium314.keyboard.sellby.companion.tutorial.LessonId

/** How the keyboard (a Service, not an Activity) sends the user to the companion app. */
object CompanionLauncher {
    const val EXTRA_OPEN_PURCHASE = "sellby_open_purchase"
    /** Value = a [LessonId] name; opens that stand-alone tutorial lesson straight away. */
    const val EXTRA_OPEN_TUTORIAL = "sellby_open_tutorial"

    /** The old HeliBoard settings entry points (the system's "Settings" link of the keyboard, the gear key,
     *  the settings activities themselves) all end up here: Sellby has ONE settings surface - the companion
     *  app (Dashboard) and the Settings panel inside the keyboard - and none of HeliBoard's own screens
     *  (About, Backup/Restore, personal dictionary, hidden features ...) is reachable any more. */
    fun openDashboard(context: Context) {
        try {
            context.startActivity(
                Intent(context, CompanionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (_: Exception) {
            // Best effort, same as the other launches from the keyboard.
        }
    }

    /** Settings -> Tutorial -> Cara penggunaan: opens one lesson as a full page of the companion app (the
     *  tutorial is a full-screen Compose UI, it can't live inside the keyboard panel). Finishing or
     *  leaving the lesson closes that page and returns to the app the user was typing in. Same
     *  NEW_TASK|CLEAR_TASK reasoning as [openPurchase]. Lessons stay available after the trial ends. */
    fun openTutorial(context: Context, lesson: LessonId) {
        try {
            context.startActivity(
                Intent(context, CompanionActivity::class.java)
                    .putExtra(EXTRA_OPEN_TUTORIAL, lesson.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (_: Exception) {
            // Best effort, same as the other fire-and-forget launches from the keyboard.
        }
    }

    /** Opens the Purchase page straight away (no splash), used when a locked toolbar tab is tapped.
     *  NEW_TASK is mandatory from a Service; CLEAR_TASK makes sure a fresh Activity instance reads the
     *  extra even if the companion app is still alive in the background - its screens are stateless
     *  (everything lives in Room/prefs), so nothing is lost by starting it over. */
    fun openPurchase(context: Context) {
        try {
            context.startActivity(
                Intent(context, CompanionActivity::class.java)
                    .putExtra(EXTRA_OPEN_PURCHASE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (_: Exception) {
            // Best effort, same as the other fire-and-forget launches from the keyboard.
        }
    }
}
