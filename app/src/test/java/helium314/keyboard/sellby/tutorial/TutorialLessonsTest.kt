// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.tutorial

import helium314.keyboard.sellby.companion.tutorial.AutoTextForm
import helium314.keyboard.sellby.companion.tutorial.AutoTextView
import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChatTag
import helium314.keyboard.sellby.companion.tutorial.DummyOrderStatus
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.LessonId
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.StatusOverlay
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialScript
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four stand-alone lessons of Settings -> Tutorial: pure data checks, no Compose, no Android. */
class TutorialLessonsTest {
    private fun chapters(lesson: LessonId): List<ChapterDef> = TutorialScript.chaptersFor(lesson)
    private fun steps(lesson: LessonId) = chapters(lesson).flatMap { it.steps }

    /** The world right after [stepId] of [lesson], folded from the lesson's own seed (like TutorialController does). */
    private fun stateAfter(lesson: LessonId, stepId: String): TutorialState {
        val all = chapters(lesson)
        var s = all.first().seed.copy(storeName = "Toko Kamu")
        for (step in all.flatMap { it.steps }) {
            s = step.effect(step.entry(s))
            if (step.id == stepId) return s
        }
        error("no step $stepId in $lesson")
    }

    @Test
    fun everyLessonHasAnOpeningAClosingAndUniqueStepIds() {
        LessonId.entries.forEach { lesson ->
            val all = steps(lesson)
            assertTrue("$lesson is too short", all.size >= 10)
            assertEquals("$lesson: duplicate step ids", all.size, all.map { it.id }.toSet().size)
            assertEquals("$lesson: the first chapter carries the seed", all.first().id, chapters(lesson).first().steps.first().id)
        }
    }

    @Test
    fun everyTapGateIsSpotlightedAndEveryAutoStepTypes() {
        LessonId.entries.forEach { lesson ->
            steps(lesson).forEach { step ->
                val gate = step.gate
                if (gate is Gate.Tap) assertTrue("$lesson ${step.id}: gate target must be spotlighted", gate.target in step.spotlight)
                if (gate is Gate.Auto) assertTrue("$lesson ${step.id}: Auto needs typing", step.typing.isNotEmpty())
                assertTrue("$lesson ${step.id}: typing and a tap gate don't mix", step.typing.isEmpty() || gate !is Gate.Tap)
            }
        }
    }

    /** The guide bubble has a FIXED height, so every text stays short - the lessons' own texts included. */
    @Test
    fun guideTextsStayShort() {
        LessonId.entries.forEach { lesson ->
            steps(lesson).forEach { assertTrue("$lesson ${it.id}: ${it.guide.text.length} chars", it.guide.text.length <= 100) }
        }
    }

    @Test
    fun everyLessonFoldsToTheEndWithoutErrors() {
        LessonId.entries.forEach { lesson -> stateAfter(lesson, steps(lesson).last().id) }
    }

    @Test
    fun invoiceLessonStartsWithTheCatalogAndEndsWithTheInvoiceInTheChat() {
        val seed = chapters(LessonId.Invoice).first().seed
        assertEquals(2, seed.produk.products.size)
        assertTrue(seed.chat.isEmpty())
        assertEquals(1, stateAfter(LessonId.Invoice, "linv_1").chat.size)

        val built = stateAfter(LessonId.Invoice, "invl_13")
        assertTrue("RINCIAN PESANAN" in built.chatInput)
        assertEquals(1, built.orders.size)
        assertEquals(8, built.produk.products.first { it.name == SampleData.KAOS_M }.stock) // 10 - 2 pcs

        val sent = stateAfter(LessonId.Invoice, "invl_14")
        assertTrue(sent.chat.any { it.text.contains("RINCIAN PESANAN") })
        assertEquals(1, built.orders.count { it.status == DummyOrderStatus.Pending })
    }

    @Test
    fun theSearchFindsTheProductAtOnceWithoutADetourToProduk() {
        val searched = stateAfter(LessonId.Invoice, "inv_5").invoice
        assertEquals("kaos", searched.search)
        assertTrue(steps(LessonId.Invoice).none { it.id.startsWith("produk_") })
    }

    @Test
    fun statusLessonStartsWithOnePendingOrderAndEndsOnTheFinishedOne() {
        val seed = chapters(LessonId.Status).first().seed
        assertEquals(listOf(DummyOrderStatus.Pending), seed.orders.map { it.status })
        assertEquals(135_000L, seed.orders.single().total)

        assertEquals(DummyOrderStatus.Selesai, stateAfter(LessonId.Status, "st_15").orders.single().status)
        assertEquals(StatusOverlay.Detail, stateAfter(LessonId.Status, "st_18").status.overlay)
        assertEquals(StatusOverlay.None, stateAfter(LessonId.Status, "st_19").status.overlay)
    }

    @Test
    fun produkLessonStartsEmptyAndSavesTheTwoVariations() {
        val seed = chapters(LessonId.Produk).first().seed
        assertTrue(seed.produk.products.isEmpty())
        assertTrue(stateAfter(LessonId.Produk, "lprod_tab").panel != null)

        val saved = stateAfter(LessonId.Produk, "produk_9").produk.products.map { it.name }
        assertEquals(listOf("Kaos Polos (L)", "Kaos Polos (M)"), saved)
        assertTrue(stateAfter(LessonId.Produk, "produk_10").chatInput.startsWith("*Deskripsi Produk"))
    }

    @Test
    fun invoiceLessonCopiesRinasFormAndPastesItIntoTheInvoice() {
        // The lesson opens with her filled-in Form Order as the one message in the chat (the bubble to copy).
        val opened = stateAfter(LessonId.Invoice, "linv_1").chat.single()
        assertEquals(ChatTag.FormOrder, opened.tag)
        assertEquals(SampleData.RINA_FILLED_FORM, opened.text)

        // Tahan -> Salin: the copy menu shows, then the clipboard holds the form (the offer waits for the Invoice tab).
        assertTrue(stateAfter(LessonId.Invoice, "linv_copy_1").copyMenuOpen)
        val copied = stateAfter(LessonId.Invoice, "linv_copy_2")
        assertFalse(copied.copyMenuOpen)
        assertEquals(SampleData.rinaFormOffer, copied.formOrderOffer)
        assertEquals(null, copied.panel)

        // Opening the Invoice tab shows the "Form Order Terdeteksi" offer over an empty form; Tempel fills it.
        val opened2 = stateAfter(LessonId.Invoice, "linv_copy_3")
        assertEquals(PanelTab.Invoice, opened2.panel)
        assertEquals(SampleData.rinaFormOffer, opened2.formOrderOffer)
        assertEquals("", opened2.invoice.nama)

        val pasted = stateAfter(LessonId.Invoice, "linv_copy_4")
        assertEquals(null, pasted.formOrderOffer)
        assertEquals(SampleData.BUYER_NAME, pasted.invoice.nama)
        assertEquals("81234567890", pasted.invoice.wa) // digits only, without the leading 0 (shown after +62)
        assertEquals(SampleData.BUYER_ADDRESS, pasted.invoice.alamat)

        // The paste already filled the address, so the step that typed it in the full story is not here.
        assertTrue(steps(LessonId.Invoice).none { it.id == "invl_6" })
        // ...and the finished invoice carries what was pasted.
        val text = stateAfter(LessonId.Invoice, "invl_13").chatInput
        assertTrue(SampleData.BUYER_NAME in text)
        assertTrue(SampleData.BUYER_ADDRESS in text)
    }

    @Test
    fun autoTextLessonNoLongerCopiesAFormButSendsOneAndUsesTheSuggestion() {
        assertTrue(stateAfter(LessonId.AutoText, "lat_1").chat.isNotEmpty())
        assertTrue(stateAfter(LessonId.AutoText, "at_3").chatInput.startsWith("Form Order"))

        // The buyer's filled form arrives after Kirim - but copying and pasting it is the Invoice lesson's job.
        assertEquals(ChatTag.FormOrder, stateAfter(LessonId.AutoText, "at_4").chat.last().tag)
        assertTrue(steps(LessonId.AutoText).none { it.id in setOf("at_5", "at_6", "at_7", "at_8", "at_9", "at_10") })
        assertTrue(steps(LessonId.AutoText).none { (it.gate as? Gate.Tap)?.target in setOf(TargetId.OfferTempel, TargetId.CopyButton, TargetId.TabInvoice) })
        assertEquals(null, stateAfter(LessonId.AutoText, "at_13").formOrderOffer)

        val picked = stateAfter(LessonId.AutoText, "at_12")
        assertTrue(picked.chatInput.startsWith("Terima Kasih"))
        assertFalse(picked.suggestionsActive)
    }

    @Test
    fun theFullStoryStillCopiesAndPastesDinasFormInAutoText() {
        // Only the stand-alone lessons moved the paste: the onboarding story is unchanged.
        val story = TutorialScript.chapters.flatMap { it.steps }.map { it.id }
        assertTrue(story.containsAll(listOf("at_5", "at_6", "at_7", "at_8")))
    }

    @Test
    fun autoTextLessonMakesANewAutoTextAndThenEditsIt() {
        val factory = chapters(LessonId.AutoText).first().seed.autoText.rows
        assertEquals(listOf("Format Order", "Hallo", "Terima kasih"), factory.map { it.shortcut })

        val opened = stateAfter(LessonId.AutoText, "atm_1").autoText
        assertEquals(AutoTextView.Form, opened.view)
        assertEquals(null, opened.form.editingRow)
        assertEquals(SampleData.NEW_AUTOTEXT_SHORTCUT, stateAfter(LessonId.AutoText, "atm_2").autoText.form.shortcut)
        assertEquals(SampleData.NEW_AUTOTEXT_MESSAGE, stateAfter(LessonId.AutoText, "atm_3").autoText.form.message)

        // Simpan: back on the list, the new row sits in alphabetical order, the factory rows are untouched.
        val saved = stateAfter(LessonId.AutoText, "atm_4").autoText
        assertEquals(AutoTextView.List, saved.view)
        assertEquals(listOf("Format Order", "Hallo", "Rekening", "Terima kasih"), saved.rows.map { it.shortcut })
        assertEquals(SampleData.NEW_AUTOTEXT_MESSAGE, saved.rows[2].message)
        assertEquals(AutoTextForm(), saved.form)

        // The three dots of THAT row open Edit / Hapus, Edit opens the form prefilled with its current text.
        assertEquals(2, stateAfter(LessonId.AutoText, "atm_5").autoText.menuRow)
        val editing = stateAfter(LessonId.AutoText, "atm_6").autoText
        assertEquals(AutoTextView.Form, editing.view)
        assertEquals(null, editing.menuRow)
        assertEquals(2, editing.form.editingRow)
        assertEquals(SampleData.NEW_AUTOTEXT_SHORTCUT, editing.form.shortcut)
        assertEquals(SampleData.NEW_AUTOTEXT_MESSAGE, editing.form.message)

        // Only the added sentence is typed: the old text is kept on screen (Typing.keep).
        val typing = steps(LessonId.AutoText).first { it.id == "atm_7" }.typing.single()
        assertEquals(SampleData.NEW_AUTOTEXT_MESSAGE.length, typing.keep)
        assertTrue(typing.text.startsWith(SampleData.NEW_AUTOTEXT_MESSAGE))

        val edited = stateAfter(LessonId.AutoText, "atm_8").autoText
        assertEquals(AutoTextView.List, edited.view)
        assertEquals(SampleData.NEW_AUTOTEXT_MESSAGE + SampleData.NEW_AUTOTEXT_ADDED, edited.rows[2].message)
        assertEquals(4, edited.rows.size)
    }

    @Test
    fun theRestOfTheAutoTextLessonStillWorksAfterTheNewSteps() {
        // The list is back on screen and "Format Order" is still the first row the next step points at.
        val afterEdit = stateAfter(LessonId.AutoText, "atm_9")
        assertEquals(AutoTextView.List, afterEdit.autoText.view)
        assertEquals("Format Order", afterEdit.autoText.rows.first().shortcut)
        assertTrue(stateAfter(LessonId.AutoText, "at_3").chatInput.startsWith("Form Order"))
        // The suggestion strip still finds only the one shortcut the lesson taps ("ter" is not in "Rekening").
        assertEquals(listOf("Terima kasih"), stateAfter(LessonId.AutoText, "at_11").autoText.rows.map { it.shortcut }.filter { it.contains("ter", ignoreCase = true) })
        // The onboarding story doesn't get these steps.
        assertTrue(TutorialScript.chapters.flatMap { it.steps }.none { it.id.startsWith("atm_") })
    }

    @Test
    fun theFullOnboardingScriptIsUnchanged() {
        val ids = TutorialScript.chapters.flatMap { it.steps }.map { it.id }
        assertEquals("intro_1", ids.first())
        assertEquals("recap_1", ids.last())
        assertTrue(ids.contains("inv_6")) // the detour to Produk is still part of the story
    }
}
