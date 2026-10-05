// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [TrialPolicy] on top of real (Robolectric) SharedPreferences. The arithmetic itself is covered by
 *  [TrialRulesTest]; this checks the stored-state side: starting once, locking, premium, the
 *  clock-rollback floor and the Hari-3 review prompt. The clock is the real one, so every case
 *  keeps hours of margin around its boundary instead of racing it. */
@RunWith(RobolectricTestRunner::class)
class TrialPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hour = 60L * 60 * 1000

    @Before
    fun clean() {
        context.prefs().edit { clear() }
    }

    private fun startedAgo(ms: Long) {
        val now = System.currentTimeMillis()
        context.prefs().edit {
            putLong(PREF_TRIAL_START_MILLIS, now - ms)
            putLong(PREF_TRIAL_LAST_SEEN_MILLIS, now - ms)
        }
    }

    @Test
    fun notStartedStaysOpen() {
        assertEquals(TrialState.NotStarted, TrialPolicy.state(context))
        assertFalse(TrialPolicy.hasStart(context))
        assertFalse(TrialPolicy.isLocked(context))
    }

    @Test
    fun startIfNeededBeginsHariPertama() {
        TrialPolicy.startIfNeeded(context)
        assertTrue(TrialPolicy.hasStart(context))
        val state = TrialPolicy.state(context)
        assertTrue(state is TrialState.Active)
        assertEquals(1, (state as TrialState.Active).dayIndex)
        assertFalse(TrialPolicy.isLocked(context))
    }

    @Test
    fun startingAgainNeverRestartsOrExtends() {
        startedAgo(30 * hour)
        val before = context.prefs().getLong(PREF_TRIAL_START_MILLIS, 0L)
        TrialPolicy.startIfNeeded(context)
        assertEquals(before, context.prefs().getLong(PREF_TRIAL_START_MILLIS, 0L))
        assertEquals(2, (TrialPolicy.state(context) as TrialState.Active).dayIndex)
    }

    @Test
    fun locksAfter72Hours() {
        startedAgo(71 * hour)
        assertEquals(3, (TrialPolicy.state(context) as TrialState.Active).dayIndex)
        assertFalse(TrialPolicy.isLocked(context))

        startedAgo(73 * hour)
        assertEquals(TrialState.Expired, TrialPolicy.state(context))
        assertTrue(TrialPolicy.isLocked(context))
    }

    @Test
    fun premiumOpensAnExpiredTrial() {
        startedAgo(100 * hour)
        context.prefs().edit { putBoolean(PREF_PREMIUM_PURCHASED, true) }
        assertEquals(TrialState.Premium, TrialPolicy.state(context))
        assertFalse(TrialPolicy.isLocked(context))
    }

    @Test
    fun movingTheClockBackDoesNotBuyTime() {
        // The phone's date was moved forward 4 days earlier (last seen), then set back to today:
        // the trial must still count from the newest time it ever saw.
        val now = System.currentTimeMillis()
        context.prefs().edit {
            putLong(PREF_TRIAL_START_MILLIS, now - hour)
            putLong(PREF_TRIAL_LAST_SEEN_MILLIS, now + 96 * hour)
        }
        assertEquals(TrialState.Expired, TrialPolicy.state(context))
        assertTrue(TrialPolicy.isLocked(context))
    }

    @Test
    fun migrateStartsTheTrialOnlyAfterOnboarding() {
        TrialPolicy.migrateIfNeeded(context)
        assertFalse(TrialPolicy.hasStart(context))

        context.prefs().edit { putBoolean(PREF_ONBOARDING_COMPLETED, true) }
        TrialPolicy.migrateIfNeeded(context)
        assertTrue(TrialPolicy.hasStart(context))
    }

    @Test
    fun reviewPromptOnlyOnHariKetigaAndOnlyOnce() {
        startedAgo(10 * hour)
        assertFalse(TrialPolicy.shouldPromptReview(context))

        startedAgo(60 * hour)
        assertTrue(TrialPolicy.shouldPromptReview(context))
        TrialPolicy.markReviewPrompted(context)
        assertFalse(TrialPolicy.shouldPromptReview(context))
    }

    @Test
    fun noReviewPromptForPremium() {
        startedAgo(60 * hour)
        context.prefs().edit { putBoolean(PREF_PREMIUM_PURCHASED, true) }
        assertFalse(TrialPolicy.shouldPromptReview(context))
    }
}
