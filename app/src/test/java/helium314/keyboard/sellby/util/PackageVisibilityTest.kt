// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Since Android 11 a package-visibility lookup (resolveActivity, queryIntentActivities, getLaunchIntentForPackage,
 *  getPackageInfo of another app) returns null/empty for anything the manifest's <queries> does not declare. Sellby's
 *  manifest declares only the input-method query on purpose (Google Play reviews broad package visibility), so such a
 *  check inside Sellby's own code silently fails: "Tidak dapat membuka situs cek ongkir" appeared on every phone
 *  because of one resolveActivity() call. Open things with startActivity() and catch ActivityNotFoundException instead. */
class PackageVisibilityTest {
    private val forbidden = listOf("resolveActivity(", "queryIntentActivities(", "getLaunchIntentForPackage(", "getPackageInfo(")

    @Test
    fun sellbyCodeNeverDependsOnPackageVisibility() {
        val root = File("src/main/java/helium314/keyboard/sellby")
        assertTrue("sources not found at ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                    .filter { (_, line) -> forbidden.any { line.contains(it) } }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue("package-visibility lookups in Sellby code: $offenders", offenders.isEmpty())
    }
}
