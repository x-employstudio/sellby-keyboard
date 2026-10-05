// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.tutorial

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tutorial must run entirely on in-memory dummy data. This is the "nothing is saved" guarantee:
 * the package may not import storage (Room, SharedPreferences, prefs()) or anything that reaches the
 * real keyboard / other apps. Only import lines are checked, so comments may still mention these names.
 */
class TutorialPurityTest {
    private val forbidden = listOf(
        "sellby.data.",
        "androidx.room",
        "android.content.SharedPreferences",
        "latin.utils.prefs",
        "androidx.core.content.edit",
        "keyboard.KeyboardSwitcher",
        "sellby.util.ChannelMessenger",
        "android.content.Intent",
        "android.widget.Toast",
        "android.content.ClipboardManager",
    )

    @Test
    fun tutorialPackageNeverTouchesStorageOrTheSystem() {
        val root = File("src/main/java/helium314/keyboard/sellby/companion/tutorial")
        assertTrue("tutorial sources not found at ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import ") }
                    .filter { line -> forbidden.any { line.contains(it) } }
                    .map { "${file.name}: ${it.trim()}" }
            }
            .toList()
        assertTrue("tutorial package has forbidden imports: $offenders", offenders.isEmpty())
    }
}
