// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.cardviewer.ViewerCommand
import com.ichi2.anki.wordlearner.AnswerChecker.Verdict
import com.ichi2.anki.wordlearner.WordSwipeLogic.Axis
import com.ichi2.anki.wordlearner.WordSwipeLogic.Label
import com.ichi2.anki.wordlearner.WordSwipeLogic.Tone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordSwipeLogicTest {
    private val width = 1000
    private val fling = 2600f

    @Test
    fun `the axis is decided after the slop, diagonal drags are not swipes`() {
        assertNull(WordSwipeLogic.axis(5f, 3f, slop = 20f))
        assertEquals(Axis.HORIZONTAL, WordSwipeLogic.axis(-40f, 5f, slop = 20f))
        assertEquals(Axis.VERTICAL, WordSwipeLogic.axis(5f, 40f, slop = 20f))
        assertEquals(Axis.VERTICAL, WordSwipeLogic.axis(40f, 35f, slop = 20f), "diagonal")
    }

    @Test
    fun `armed from a third of the width, disarmed when moving back`() {
        assertFalse(WordSwipeLogic.isArmed(300f, width))
        assertTrue(WordSwipeLogic.isArmed(330f, width))
        assertTrue(WordSwipeLogic.isArmed(-330f, width))
        assertFalse(WordSwipeLogic.isArmed(250f, width))
        assertEquals(1f, WordSwipeLogic.progress(500f, width))
        assertEquals(0.5f, WordSwipeLogic.progress(160f, width), 0.001f)
    }

    @Test
    fun `release commits when armed or after a fast fling the same way`() {
        assertTrue(WordSwipeLogic.commits(340f, 0f, width, fling))
        assertFalse(WordSwipeLogic.commits(200f, 0f, width, fling))
        assertTrue(WordSwipeLogic.commits(200f, 3000f, width, fling))
        assertFalse(WordSwipeLogic.commits(200f, -3000f, width, fling), "flung back")
        assertFalse(WordSwipeLogic.commits(60f, 3000f, width, fling), "too short")
    }

    @Test
    fun `the card follows freely, then with resistance`() {
        assertEquals(200f, WordSwipeLogic.resisted(200f, width))
        assertTrue(WordSwipeLogic.resisted(800f, width) < 800f)
        assertEquals(-WordSwipeLogic.resisted(800f, width), WordSwipeLogic.resisted(-800f, width))
    }

    @Test
    fun `on the recognition question both sides show the answer`() {
        val outcomes = assertNotNull(WordSwipeLogic.outcomes(typed = false, verdict = null, answerShown = false, keyboardUp = false))
        assertEquals(ViewerCommand.SHOW_ANSWER, outcomes.left.command)
        assertEquals(Label.SHOW_ANSWER, outcomes.right.label)
    }

    @Test
    fun `on the recognition answer right is remembered,, left forgotten`() {
        val outcomes = assertNotNull(WordSwipeLogic.outcomes(typed = false, verdict = null, answerShown = true, keyboardUp = false))
        assertEquals(ViewerCommand.ANSWER_GOOD, outcomes.toward(1f).command)
        assertEquals(Tone.GOOD, outcomes.toward(1f).tone)
        assertEquals(ViewerCommand.ANSWER_AGAIN, outcomes.toward(-1f).command)
        assertEquals(Label.FORGOTTEN, outcomes.toward(-1f).label)
    }

    @Test
    fun `on a typed word both sides continue with the verdict and say so`() {
        for (verdict in listOf(Verdict.CORRECT, Verdict.TYPO)) {
            val outcomes = assertNotNull(WordSwipeLogic.outcomes(typed = true, verdict = verdict, answerShown = true, keyboardUp = false))
            assertEquals(ViewerCommand.ANSWER_GOOD, outcomes.left.command)
            assertEquals(Label.NEXT_CORRECT, outcomes.left.label)
            assertEquals(outcomes.left, outcomes.right)
        }
        val wrong = assertNotNull(WordSwipeLogic.outcomes(typed = true, verdict = Verdict.WRONG, answerShown = true, keyboardUp = false))
        assertEquals(ViewerCommand.ANSWER_AGAIN, wrong.right.command, "a wrong word is never graded correct")
        assertEquals(Label.NEXT_WRONG, wrong.right.label)
        assertEquals(Tone.AGAIN, wrong.left.tone)
    }

    @Test
    fun `no swipe while typing or with the keyboard up`() {
        assertNull(WordSwipeLogic.outcomes(typed = true, verdict = null, answerShown = false, keyboardUp = false))
        assertNull(WordSwipeLogic.outcomes(typed = true, verdict = Verdict.CORRECT, answerShown = true, keyboardUp = true))
        assertNull(WordSwipeLogic.outcomes(typed = false, verdict = null, answerShown = false, keyboardUp = true))
    }

    @Test
    fun `the swipe does what the gestures do`() {
        for (verdict in Verdict.entries) {
            val outcomes = assertNotNull(WordSwipeLogic.outcomes(typed = true, verdict = verdict, answerShown = true, keyboardUp = false))
            for (gesture in listOf(ViewerCommand.ANSWER_AGAIN, ViewerCommand.ANSWER_GOOD)) {
                assertEquals(WordGestures.resolve(gesture, true, verdict, answerShown = true, keyboardUp = false), outcomes.left.command)
            }
        }
    }
}
