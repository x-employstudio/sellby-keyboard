// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.TutorialStep

internal val IntroChapter = ChapterDef(
    id = ChapterId.Intro,
    title = "Pengenalan",
    // The whole script's starting world: an empty chat and an empty catalog. The buyer only shows up
    // in the last intro step, after the toolbar has been introduced.
    seed = TutorialState(),
    steps = listOf(
        TutorialStep(
            id = "intro_1",
            guide = Guide(
                "Halo, aku Sellby! 👋 Kita latihan singkat ya. Semua ini cuma contoh dan tidak disimpan.",
                Mood.Happy,
            ),
        ),
        TutorialStep(
            id = "intro_2",
            guide = Guide(
                "Toolbar Sellby: Invoice, Ongkir, Status, Produk, dan Auto-Text. Semua fitur jualanmu ada di sini.",
                Mood.Flat,
            ),
            spotlight = listOf(
                TargetId.TabInvoice, TargetId.TabOngkir, TargetId.TabStatus, TargetId.TabProduk, TargetId.TabAutoText,
            ),
        ),
        TutorialStep(
            id = "intro_3",
            guide = Guide(
                "Rina mau pesan kaos. Misi: buat invoice, kelola pesanan, lalu pasang jalan pintas Auto-Text.",
                Mood.Happy,
            ),
            entry = { it.copy(chat = it.chat + SampleData.buyerOrder) },
        ),
    ),
)
