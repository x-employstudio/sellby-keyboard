// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.TutorialStep

/** One short closing sentence; the guide's button turns into "Selesai" on the last step. */
internal val RecapChapter = ChapterDef(
    id = ChapterId.Recap,
    title = "Selesai",
    steps = listOf(
        TutorialStep(
            id = "recap_1",
            guide = Guide("Mantap, kamu siap berjualan dengan Sellby! 🎉 Ongkir dan Settings bisa kamu coba sendiri.", Mood.Happy),
        ),
    ),
)
