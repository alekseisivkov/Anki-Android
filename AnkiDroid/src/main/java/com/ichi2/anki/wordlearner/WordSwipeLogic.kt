// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.cardviewer.ViewerCommand
import kotlin.math.abs
import kotlin.math.sign

/**
 * Decisions of the card swipe of the study screen, without views (see [WordSwipe]).
 *
 * A drag becomes a swipe only when it is clearly horizontal after the touch slop, so vertical scrolling and
 * diagonal noise never grade a card. It is "armed" beyond [COMMIT_FRACTION] of the width and commits on release
 * when armed, or after a fast fling in the direction of the drag.
 */
object WordSwipeLogic {
    const val COMMIT_FRACTION = 0.32f

    /** A drag decides for horizontal only if it is this much wider than high */
    const val HORIZONTAL_DOMINANCE = 1.5f

    /** A fling commits from this fraction of the width */
    const val FLING_MIN_FRACTION = 0.12f

    enum class Axis { HORIZONTAL, VERTICAL }

    enum class Tone { GOOD, AGAIN, NEUTRAL }

    /** What the preview says */
    enum class Label { SHOW_ANSWER, REMEMBERED, FORGOTTEN, NEXT_CORRECT, NEXT_WRONG }

    data class Outcome(
        val command: ViewerCommand,
        val label: Label,
        val tone: Tone,
    )

    data class Outcomes(
        val left: Outcome,
        val right: Outcome,
    ) {
        fun toward(dx: Float): Outcome = if (dx < 0) left else right
    }

    /** @return `null` while the drag is shorter than [slop] */
    fun axis(
        dx: Float,
        dy: Float,
        slop: Float,
    ): Axis? =
        when {
            abs(dx) <= slop && abs(dy) <= slop -> null
            abs(dx) > abs(dy) * HORIZONTAL_DOMINANCE -> Axis.HORIZONTAL
            else -> Axis.VERTICAL
        }

    fun isArmed(
        dx: Float,
        width: Int,
    ): Boolean = width > 0 && abs(dx) >= width * COMMIT_FRACTION

    /** 0 at rest, 1 when armed */
    fun progress(
        dx: Float,
        width: Int,
    ): Float = if (width <= 0) 0f else (abs(dx) / (width * COMMIT_FRACTION)).coerceIn(0f, 1f)

    fun commits(
        dx: Float,
        velocityX: Float,
        width: Int,
        flingVelocity: Float,
    ): Boolean {
        if (isArmed(dx, width)) return true
        val fling = abs(velocityX) >= flingVelocity && sign(velocityX) == sign(dx)
        return fling && abs(dx) >= width * FLING_MIN_FRACTION
    }

    /** The card follows the finger, beyond the threshold with resistance */
    fun resisted(
        dx: Float,
        width: Int,
    ): Float {
        val free = width * COMMIT_FRACTION
        if (abs(dx) <= free) return dx
        return sign(dx) * (free + (abs(dx) - free) * 0.45f)
    }

    /**
     * What a swipe to either side does on a word card, `null` if the card cannot be swiped now.
     * The commands are those of [WordGestures.resolve]: on a typed card both sides continue with the verdict.
     */
    fun outcomes(
        typed: Boolean,
        verdict: AnswerChecker.Verdict?,
        answerShown: Boolean,
        keyboardUp: Boolean,
    ): Outcomes? {
        if (keyboardUp) return null
        if (typed) {
            if (!answerShown || verdict == null) return null
            val next =
                if (verdict.accepted) {
                    Outcome(ViewerCommand.ANSWER_GOOD, Label.NEXT_CORRECT, Tone.GOOD)
                } else {
                    Outcome(ViewerCommand.ANSWER_AGAIN, Label.NEXT_WRONG, Tone.AGAIN)
                }
            return Outcomes(next, next)
        }
        if (!answerShown) {
            val flip = Outcome(ViewerCommand.SHOW_ANSWER, Label.SHOW_ANSWER, Tone.NEUTRAL)
            return Outcomes(flip, flip)
        }
        return Outcomes(
            left = Outcome(ViewerCommand.ANSWER_AGAIN, Label.FORGOTTEN, Tone.AGAIN),
            right = Outcome(ViewerCommand.ANSWER_GOOD, Label.REMEMBERED, Tone.GOOD),
        )
    }
}
