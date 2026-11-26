// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.view.KeyEvent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.cardviewer.Gesture
import com.ichi2.anki.cardviewer.GestureProcessor
import com.ichi2.anki.cardviewer.ViewerCommand
import com.ichi2.anki.cardviewer.ViewerCommand.ANSWER_AGAIN
import com.ichi2.anki.cardviewer.ViewerCommand.ANSWER_EASY
import com.ichi2.anki.cardviewer.ViewerCommand.ANSWER_GOOD
import com.ichi2.anki.cardviewer.ViewerCommand.ANSWER_HARD
import com.ichi2.anki.cardviewer.ViewerCommand.SHOW_ANSWER
import com.ichi2.anki.cardviewer.ViewerCommand.UNDO
import com.ichi2.anki.reviewer.Binding
import com.ichi2.anki.reviewer.ReviewerBinding
import com.ichi2.anki.wordlearner.AnswerChecker.Verdict
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WordGesturesTest : RobolectricTest() {
    private fun gestures(command: ViewerCommand): Set<Gesture> =
        ReviewerBinding
            .fromPreference(getPreferences(), command)
            .mapNotNull { (it.binding as? Binding.GestureInput)?.gesture }
            .toSet()

    /** What [GestureProcessor] executes for a gesture with the current preferences */
    private fun executed(perform: GestureProcessor.() -> Unit): ViewerCommand? {
        var executed: ViewerCommand? = null
        GestureProcessor { which, _ ->
            executed = which
            true
        }.apply {
            init(getPreferences())
            perform()
        }
        return executed
    }

    @Test
    fun `gestures are switched on with taps and horizontal swipes`() {
        WordGestures.setUpOnce(getPreferences())

        assertTrue(getPreferences().getBoolean(GestureProcessor.PREF_KEY, false))
        assertEquals(setOf(Gesture.TAP_TOP, Gesture.TAP_BOTTOM, Gesture.TAP_LEFT, Gesture.TAP_RIGHT), gestures(SHOW_ANSWER))
        assertEquals(setOf(Gesture.SWIPE_RIGHT), gestures(ANSWER_GOOD))
        assertEquals(setOf(Gesture.SWIPE_LEFT), gestures(ANSWER_AGAIN))
        // vertical swipes stay free for scrolling
        assertTrue(ViewerCommand.entries.none { Gesture.SWIPE_UP in gestures(it) || Gesture.SWIPE_DOWN in gestures(it) })
    }

    @Test
    fun `the keys of the answer buttons are kept`() {
        WordGestures.setUpOnce(getPreferences())
        val keys = ReviewerBinding.fromPreference(getPreferences(), ANSWER_GOOD).map { it.binding }
        assertTrue(keys.any { it is Binding.KeyCode && it.keycode == KeyEvent.KEYCODE_3 }, "$keys")
    }

    @Test
    fun `the study screen understands the gestures`() {
        WordGestures.setUpOnce(getPreferences())
        assertEquals(SHOW_ANSWER, executed { onTap(1000, 500, 250f, 900f) })
        assertEquals(SHOW_ANSWER, executed { onTap(1000, 500, 250f, 100f) })
        assertEquals(ANSWER_GOOD, executed { onFling(400f, 10f, 3000f, 0f, false, false, false) })
        assertEquals(ANSWER_AGAIN, executed { onFling(-400f, 10f, -3000f, 0f, false, false, false) })
        assertNull(executed { onFling(10f, 400f, 0f, 3000f, false, false, false) })
    }

    @Test
    fun `a choice in the settings survives the next start`() {
        WordGestures.setUpOnce(getPreferences())
        getPreferences().edit { putBoolean(GestureProcessor.PREF_KEY, false) }
        WordGestures.setUpOnce(getPreferences())
        assertFalse(getPreferences().getBoolean(GestureProcessor.PREF_KEY, true))
    }

    private fun typed(
        command: ViewerCommand,
        verdict: Verdict?,
        answerShown: Boolean = true,
        keyboardUp: Boolean = false,
    ) = WordGestures.resolve(command, typed = true, verdict = verdict, answerShown = answerShown, keyboardUp = keyboardUp)

    @Test
    fun `a swipe never grades a wrong typed word correct`() {
        for (command in listOf(ANSWER_AGAIN, ANSWER_HARD, ANSWER_GOOD, ANSWER_EASY)) {
            assertEquals(ANSWER_AGAIN, typed(command, Verdict.WRONG), "$command")
        }
    }

    @Test
    fun `a swipe continues an accepted typed word`() {
        for (verdict in listOf(Verdict.CORRECT, Verdict.TYPO)) {
            assertEquals(ANSWER_GOOD, typed(ANSWER_AGAIN, verdict))
            assertEquals(ANSWER_GOOD, typed(ANSWER_GOOD, verdict))
        }
    }

    @Test
    fun `gestures wait while the word is typed`() {
        assertNull(typed(SHOW_ANSWER, verdict = null, answerShown = false))
        assertNull(typed(ANSWER_GOOD, verdict = null, answerShown = false))
        assertNull(typed(ANSWER_GOOD, Verdict.CORRECT, keyboardUp = true))
        assertNull(WordGestures.resolve(SHOW_ANSWER, typed = false, verdict = null, answerShown = false, keyboardUp = true))
    }

    @Test
    fun `other commands and recognition cards are not changed`() {
        assertEquals(UNDO, typed(UNDO, Verdict.WRONG))
        assertEquals(SHOW_ANSWER, WordGestures.resolve(SHOW_ANSWER, typed = false, verdict = null, answerShown = false, keyboardUp = false))
        assertEquals(ANSWER_EASY, WordGestures.resolve(ANSWER_EASY, typed = false, verdict = null, answerShown = true, keyboardUp = false))
    }

    @Test
    fun `the hint is shown once, only with gestures on`() {
        assertFalse(WordGestures.takeHint(getPreferences()))
        WordGestures.setUpOnce(getPreferences())
        assertTrue(WordGestures.takeHint(getPreferences()))
        assertFalse(WordGestures.takeHint(getPreferences()))
    }
}
