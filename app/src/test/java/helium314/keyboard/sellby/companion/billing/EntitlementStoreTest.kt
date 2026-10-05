// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.TrialPolicy
import helium314.keyboard.sellby.companion.TrialState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntitlementStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        context.prefs().edit { clear() }
    }

    @Test
    fun nothingBoughtByDefault() {
        assertEquals(Entitlement(), EntitlementStore(context).load())
    }

    @Test
    fun savedStateComesBack() {
        val saved = Entitlement(premium = true, pending = false, notOwnedSinceMillis = 123L)
        EntitlementStore(context).save(saved)
        assertEquals(saved, EntitlementStore(context).load())
    }

    @Test
    fun theKeyboardsFeatureLockSeesWhatWasSaved() {
        EntitlementStore(context).save(Entitlement(premium = true))
        assertEquals(TrialState.Premium, TrialPolicy.state(context))
        assertFalse(TrialPolicy.isLocked(context))
    }
}
