// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

import helium314.keyboard.sellby.companion.tutorial.chapters.AutoTextChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.IntroChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.InvoiceAwalChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.InvoiceLanjutChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.ProdukChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.RecapChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.StatusChapter
import helium314.keyboard.sellby.companion.tutorial.chapters.lessonChapters

/**
 * The tutorial's chapters in story order: the invoice starts, the empty catalog sends the seller on a
 * detour to Produk, the invoice is finished, the order is followed through Status and the Auto-Text
 * shortcuts close the loop (Form Order -> Invoice).
 */
internal object TutorialScript {
    val chapters: List<ChapterDef> = listOf(
        IntroChapter,
        InvoiceAwalChapter,
        ProdukChapter,
        InvoiceLanjutChapter,
        StatusChapter,
        AutoTextChapter,
        RecapChapter,
    )

    /** One of the four stand-alone lessons of Settings -> Tutorial (see LessonChapters.kt). */
    fun chaptersFor(lesson: LessonId): List<ChapterDef> = lessonChapters(lesson)
}
