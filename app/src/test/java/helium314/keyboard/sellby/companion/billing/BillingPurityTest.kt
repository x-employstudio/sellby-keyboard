// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Architecture guards for the purchase code, checked on the source files (import lines and the premium flag's
 *  writers only, so comments may still mention these names). They keep three promises:
 *  1. Google Play Billing is touched by exactly one class ([PlayBillingBackend]); the decision logic is pure.
 *  2. Nothing in the keyboard's typing path (keyboard, latin, the panels in sellby/ui, settings) can reach Play
 *     or the purchase code: the keyboard only READS the stored flag, through TrialPolicy.
 *  3. Exactly one class ([EntitlementStore]) writes the premium flag, so it can only ever be set from what
 *     Google Play said. */
class BillingPurityTest {
    private val sourceRoot = File("src/main/java")

    private fun kotlinAndJavaFiles(): List<File> {
        assertTrue("sources not found at ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    private fun File.importLines() = readLines().filter { it.trimStart().startsWith("import ") }
    private fun File.rel() = relativeTo(sourceRoot).invariantSeparatorsPath

    @Test
    fun onlyPlayBillingBackendImportsThePlayBillingLibrary() {
        val importers = kotlinAndJavaFiles()
            .filter { f -> f.importLines().any { it.contains("com.android.billingclient") } }
            .map { it.name }
        assertEquals(listOf("PlayBillingBackend.kt"), importers)
    }

    @Test
    fun theDecisionLogicIsPureKotlin() {
        val rules = File(sourceRoot, "helium314/keyboard/sellby/companion/billing/EntitlementRules.kt")
        assertTrue(rules.isFile)
        val androidImports = rules.importLines().filter { it.contains("android") }
        assertTrue("EntitlementRules must not import Android or Play types: $androidImports", androidImports.isEmpty())
    }

    @Test
    fun onlyTheCompanionScreensReachThePurchaseCode() {
        val allowed = setOf(
            "helium314/keyboard/sellby/companion/CompanionActivity.kt",
            "helium314/keyboard/sellby/companion/screens/LoadingScreen.kt",
            "helium314/keyboard/sellby/companion/screens/PurchaseScreen.kt",
        )
        val offenders = kotlinAndJavaFiles()
            .filter { !it.rel().startsWith("helium314/keyboard/sellby/companion/billing/") }
            .filter { f -> f.importLines().any { it.contains("sellby.companion.billing") } }
            .map { it.rel() }
            .filter { it !in allowed }
        assertTrue("these files must not use the purchase code: $offenders", offenders.isEmpty())
    }

    @Test
    fun onlyEntitlementStoreWritesThePremiumFlag() {
        val writers = kotlinAndJavaFiles()
            .filter { it.name != "EntitlementStore.kt" }
            .filter { f ->
                f.readLines().any { line ->
                    val code = line.substringBefore("//")
                    (code.contains("PREF_PREMIUM_PURCHASED") || code.contains("sellby_premium_purchased")) &&
                        (code.contains("put") || code.contains("remove") || code.contains("clear"))
                }
            }
            .map { it.rel() }
        assertTrue("only EntitlementStore may write the premium flag, also found: $writers", writers.isEmpty())
    }
}
