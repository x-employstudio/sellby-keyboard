// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import helium314.keyboard.sellby.companion.TrialPolicy

/** Asks for a Play Store review once, on trial day 3, when the Dashboard is shown.
 *
 *  Google's rules shape this: the API never says whether the dialog appeared or whether the user
 *  reviewed, so nothing in the app may depend on the outcome; errors are swallowed; and the dialog
 *  only exists for builds installed from Google Play (Internal testing is enough) - on a sideloaded
 *  or debug build the request simply fails. The "already asked" flag is therefore set only after the
 *  flow was actually launched, so a failed request does not burn the single chance. */
object ReviewPrompter {
    private const val TAG = "SellbyReview"

    fun maybePrompt(context: Context) {
        val activity = context.findActivity() ?: return
        if (!TrialPolicy.shouldPromptReview(context)) return
        val manager = ReviewManagerFactory.create(context)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                Log.d(TAG, "requestReviewFlow failed: ${request.exception}")
                return@addOnCompleteListener
            }
            manager.launchReviewFlow(activity, request.result).addOnCompleteListener {
                TrialPolicy.markReviewPrompted(context)
            }
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
