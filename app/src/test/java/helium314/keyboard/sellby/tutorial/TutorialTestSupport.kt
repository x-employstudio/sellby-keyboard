// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.tutorial

import helium314.keyboard.sellby.companion.tutorial.TutorialScript
import helium314.keyboard.sellby.companion.tutorial.TutorialState

/** The world right after the step with [stepId] is completed (folding the whole script from its seed). */
internal fun stateAfter(stepId: String, storeName: String = "Toko Kamu"): TutorialState {
    val chapters = TutorialScript.chapters
    var s = chapters.first().seed.copy(storeName = storeName)
    for (step in chapters.flatMap { it.steps }) {
        s = step.effect(step.entry(s))
        if (step.id == stepId) return s
    }
    error("no step with id $stepId")
}
