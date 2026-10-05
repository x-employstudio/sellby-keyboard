// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialRulesTest {
    private val day = TrialRules.DAY_MS
    private val start = 1_000_000_000_000L

    private fun at(elapsed: Long) = TrialRules.evaluate(premium = false, startMillis = start, nowMillis = start + elapsed)

    @Test
    fun neverStartedIsNotStarted() {
        assertEquals(TrialState.NotStarted, TrialRules.evaluate(false, 0L, start))
    }

    @Test
    fun premiumBeatsEverythingEvenAnExpiredTrial() {
        assertEquals(TrialState.Premium, TrialRules.evaluate(true, start, start + 10 * day))
        assertEquals(TrialState.Premium, TrialRules.evaluate(true, 0L, start))
    }

    @Test
    fun dayIndexFollowsThe24HourBoundaries() {
        assertEquals(TrialState.Active(1, 3 * day), at(0))
        assertEquals(1, (at(day - 1) as TrialState.Active).dayIndex)
        assertEquals(2, (at(day) as TrialState.Active).dayIndex)
        assertEquals(2, (at(2 * day - 1) as TrialState.Active).dayIndex)
        assertEquals(3, (at(2 * day) as TrialState.Active).dayIndex)
        assertEquals(3, (at(3 * day - 1) as TrialState.Active).dayIndex)
    }

    @Test
    fun expiresExactlyAfter72Hours() {
        assertEquals(TrialState.Expired, at(3 * day))
        assertEquals(TrialState.Expired, at(30 * day))
        assertEquals(1L, (at(3 * day - 1) as TrialState.Active).remainingMs)
    }

    @Test
    fun aClockBeforeTheStartIsTreatedAsTheStart() {
        assertEquals(TrialState.Active(1, 3 * day), TrialRules.evaluate(false, start, start - 5 * day))
    }

    @Test
    fun reviewWindowIsOnlyHari3() {
        assertFalse(TrialRules.inReviewWindow(0L, start))
        assertFalse(TrialRules.inReviewWindow(start, start))
        assertFalse(TrialRules.inReviewWindow(start, start + 2 * day - 1))
        assertTrue(TrialRules.inReviewWindow(start, start + 2 * day))
        assertTrue(TrialRules.inReviewWindow(start, start + 3 * day - 1))
        assertFalse(TrialRules.inReviewWindow(start, start + 3 * day))
    }

    @Test
    fun windingTheClockBackNeverGivesExtraTime() {
        val lastSeen = start + 4 * day // we already saw a time past the end
        val effective = TrialRules.effectiveNow(wallMillis = start + day, lastSeenMillis = lastSeen)
        assertEquals(lastSeen, effective)
        assertEquals(TrialState.Expired, TrialRules.evaluate(false, start, effective))
    }

    @Test
    fun mergeKeepsTheEarlierStartSoWipingTheAppNeverGivesExtraTime() {
        assertEquals(start, TrialRules.mergeStart(localMillis = 0L, storedMillis = start)) // app data wiped
        assertEquals(start, TrialRules.mergeStart(localMillis = start, storedMillis = 0L)) // old install, nothing stored yet
        assertEquals(start, TrialRules.mergeStart(localMillis = start + day, storedMillis = start))
        assertEquals(start, TrialRules.mergeStart(localMillis = start, storedMillis = start + day))
        assertEquals(0L, TrialRules.mergeStart(0L, 0L))
        assertEquals(0L, TrialRules.mergeStart(-5L, 0L))
    }

    @Test
    fun aRestoredStartPastTheEndMeansTheTrialIsStillOver() {
        val restored = TrialRules.mergeStart(localMillis = 0L, storedMillis = start)
        assertEquals(TrialState.Expired, TrialRules.evaluate(false, restored, start + 5 * day))
    }

    @Test
    fun aClockMovingForwardIsAccepted() {
        assertEquals(start + 2 * day, TrialRules.effectiveNow(wallMillis = start + 2 * day, lastSeenMillis = start))
    }
}
