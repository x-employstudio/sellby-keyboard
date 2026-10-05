// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SellbyLinksTest {
    @Test
    fun anAddressThatIsNotSetYieldsNoLink() {
        assertNull(SellbyLinks.under("", "/privacy.html"))
        assertNull(SellbyLinks.under("   ", "/privacy.html"))
        assertNull(SellbyLinks.under("/", "/privacy.html"))
    }

    @Test
    fun aSetAddressIsJoinedWithoutDoubleSlashes() {
        assertEquals("https://example.github.io/sellby/privacy.html", SellbyLinks.under("https://example.github.io/sellby", "/privacy.html"))
        assertEquals("https://example.github.io/sellby/privacy.html", SellbyLinks.under(" https://example.github.io/sellby/ ", "/privacy.html"))
    }

    @Test
    fun configuredAddressesAreHttps() {
        // Empty is allowed while the repository does not exist yet (scripts/check-release.ps1 blocks a release then).
        for (value in listOf(SellbyLinks.SOURCE_URL, SellbyLinks.SITE_URL)) {
            assertTrue("not an https address: $value", value.isEmpty() || value.startsWith("https://"))
            assertTrue("no trailing slash allowed: $value", !value.endsWith("/"))
        }
    }

    @Test
    fun theSupportAddressLooksLikeAnEmail() {
        assertTrue(SellbyLinks.SUPPORT_EMAIL.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")))
    }
}
