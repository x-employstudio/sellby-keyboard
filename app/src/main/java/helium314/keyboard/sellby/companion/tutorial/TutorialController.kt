// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Drives the tutorial. The ONLY thing that is persisted (rememberSaveable) is [index]; everything
 * the screen renders is [state], recomputed as a pure fold of every earlier step's effect over the
 * script's seed (see [TutorialStep]), so rotation / process death / going back / the Produk detour
 * all cost nothing extra.
 *
 * Transient animation state (typing progress, which key is "pressed", misses) lives here too but is
 * deliberately NOT saved - it just restarts with the step.
 */
@Stable
class TutorialController(
    private val chapters: List<ChapterDef>,
    private val storeName: String,
    startIndex: Int = 0,
) {
    private class Flat(val chapter: ChapterDef, val step: TutorialStep)

    private val flat: List<Flat> = chapters.flatMap { c -> c.steps.map { Flat(c, it) } }

    val total: Int = flat.size

    var index by mutableIntStateOf(startIndex.coerceIn(0, (flat.size - 1).coerceAtLeast(0)))
        private set

    val currentStep: TutorialStep get() = flat[index].step
    val currentChapter: ChapterDef get() = flat[index].chapter
    val chapterCount: Int get() = chapters.size
    val chapterNumber: Int get() = chapters.indexOf(currentChapter) + 1
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == flat.size - 1

    private val derivedState = derivedStateOf { compute(index) }

    /** Everything the dummy UI shows right now (chat, open panel, panel contents...). */
    val state: TutorialState get() = derivedState.value

    // ------------------------------------------------------------ transient animation state

    /** Which [Typing] segment of the current step is being typed (== segment count once all are done). */
    var typingSegment by mutableIntStateOf(0)
        private set

    /** How many characters of the current segment have been "typed" so far. */
    var typed by mutableIntStateOf(0)
        private set

    var misses by mutableIntStateOf(0)
        private set

    var assistVisible by mutableStateOf(false)
        private set

    /**
     * False while the buyer's new message(s) of this step are still arriving: the card must stay
     * bright (no dimming, ring or hand) so the user notices the message first.
     */
    var spotlightVisible by mutableStateOf(true)
        private set

    private val typingDone: Boolean get() = typingSegment >= currentStep.typing.size

    val canAdvance: Boolean get() = typingDone

    /** Field currently being auto-typed into (null once finished), so the panel can show a caret. */
    val typingField: FieldId? get() = currentStep.typing.getOrNull(typingSegment)?.field

    /** Text a dummy field should display: the partial auto-typed text while it is being typed. */
    fun fieldText(field: FieldId, base: String): String {
        val segments = currentStep.typing
        val i = segments.indexOfFirst { it.field == field }
        if (i < 0) return base
        return when {
            i < typingSegment -> segments[i].text
            i == typingSegment -> segments[i].text.take(typed + segments[i].keep)
            else -> base
        }
    }

    // ------------------------------------------------------------ navigation

    fun next() {
        if (!canAdvance) return
        if (index < flat.size - 1) {
            index++
            resetTransient()
        }
    }

    fun back() {
        if (index > 0) {
            index--
            resetTransient()
        }
    }

    /** A dummy element was tapped. Only the current gate's target advances; anything else is a miss. */
    fun tap(id: TargetId?) {
        val gate = currentStep.gate
        if (gate !is Gate.Tap) return
        if (id != null && gate.target == id) next() else miss()
    }

    fun miss() {
        misses++
        if (misses >= 2) assistVisible = true
    }

    fun showAssist() {
        assistVisible = true
    }

    /** "Bantu aku": do what the step asks on the user's behalf. */
    fun assist() {
        if (!canAdvance) finishTyping() else next()
    }

    fun finishTyping() {
        typingSegment = currentStep.typing.size
        typed = 0
    }

    // ------------------------------------------------------------ animations

    /**
     * Runs the auto-typing animation of the current step (segment after segment), then, for
     * [Gate.Auto] steps, moves on by itself. A no-op for steps without [Typing].
     */
    suspend fun runTyping() {
        val segments = currentStep.typing
        typingSegment = 0
        typed = 0
        if (segments.isNotEmpty()) {
            // Give the screen time to scroll the field into view before anything is typed into it.
            delay(800)
            while (typingSegment < segments.size) {
                val segment = segments[typingSegment]
                if (typed >= segment.text.length - segment.keep) {
                    delay(250)
                    if (typingSegment >= segments.size) break // skipped meanwhile
                    typingSegment++
                    typed = 0
                    continue
                }
                typed++
                delay((1000L / segment.charsPerSecond).coerceAtLeast(20L))
            }
        }
        val gate = currentStep.gate
        if (gate is Gate.Auto) {
            delay(gate.delayMs)
            next()
        }
    }

    /** Waits out the arrival of a buyer's new message before the spotlight comes in (see [spotlightVisible]). */
    suspend fun runSpotlightHold() {
        val hold = spotlightHoldMs(index)
        if (hold > 0) {
            spotlightVisible = false
            delay(hold)
        }
        spotlightVisible = true
    }

    /** Time the buyer's messages that are new at step [n] need to arrive and be noticed (0 = none). */
    private fun spotlightHoldMs(n: Int): Long {
        if (n == 0) return 0
        val before = compute(n - 1).chat
        val after = compute(n).chat
        if (after.size <= before.size) return 0
        val arriving = after.drop(before.size).filter { it.sender == Sender.Buyer }
        return if (arriving.isEmpty()) 0 else arriving.maxOf { it.appearDelayMs } + 1300
    }

    private fun resetTransient() {
        typingSegment = 0
        typed = 0
        misses = 0
        assistVisible = false
        spotlightVisible = spotlightHoldMs(index) == 0L
    }

    // ------------------------------------------------------------ the fold

    private fun compute(n: Int): TutorialState {
        var s = chapters.first().seed.copy(storeName = storeName)
        for (i in 0 until n) {
            val step = flat[i].step
            s = step.effect(step.entry(s))
        }
        return flat[n].step.entry(s)
    }

    companion object {
        fun saver(chapters: List<ChapterDef>, storeName: String): Saver<TutorialController, Int> =
            Saver(save = { it.index }, restore = { TutorialController(chapters, storeName, it) })
    }
}
