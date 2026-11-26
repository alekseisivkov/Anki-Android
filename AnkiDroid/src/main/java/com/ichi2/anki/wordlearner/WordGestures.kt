// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.cardviewer.Gesture
import com.ichi2.anki.cardviewer.GestureProcessor
import com.ichi2.anki.cardviewer.ViewerCommand
import com.ichi2.anki.reviewer.Binding
import com.ichi2.anki.reviewer.CardSide
import com.ichi2.anki.reviewer.MappableBinding.Companion.toPreferenceString
import com.ichi2.anki.reviewer.ReviewerBinding

/**
 * Gestures of the study screen, switched on once for this app and changeable in the settings afterwards.
 *
 * Recognition cards: a tap shows the answer, a swipe to the right means "remembered" (Good), to the left
 * "forgotten" (Again). Vertical swipes stay free for scrolling a long card.
 *
 * Typed cards are graded by [AnswerChecker]: a swipe only continues with that verdict ("Дальше"),
 * so a wrong answer is never graded correct by a gesture. "Всё-таки верно" stays a deliberate tap.
 * While the keyboard is up, or before the typed answer was checked, gestures do nothing.
 */
object WordGestures {
    const val PREF_SET_UP = "wordlearner_gestures_set_up"
    const val PREF_HINT_SHOWN = "wordlearner_gesture_hint_shown"

    val DEFAULTS: Map<ViewerCommand, List<Gesture>> =
        mapOf(
            ViewerCommand.SHOW_ANSWER to listOf(Gesture.TAP_TOP, Gesture.TAP_BOTTOM, Gesture.TAP_LEFT, Gesture.TAP_RIGHT),
            ViewerCommand.ANSWER_GOOD to listOf(Gesture.SWIPE_RIGHT),
            ViewerCommand.ANSWER_AGAIN to listOf(Gesture.SWIPE_LEFT),
        )

    private val GRADES =
        setOf(ViewerCommand.ANSWER_AGAIN, ViewerCommand.ANSWER_HARD, ViewerCommand.ANSWER_GOOD, ViewerCommand.ANSWER_EASY)

    /** Turns gestures on with [DEFAULTS], once: later changes in the settings are kept */
    fun setUpOnce(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_SET_UP, false)) return
        prefs.edit {
            putBoolean(GestureProcessor.PREF_KEY, true)
            for ((command, gestures) in DEFAULTS) {
                val bindings = ReviewerBinding.fromPreference(prefs, command)
                val taken = bindings.mapNotNull { (it.binding as? Binding.GestureInput)?.gesture }.toSet()
                val added = gestures.filter { it !in taken }.map { ReviewerBinding(Binding.GestureInput(it), CardSide.BOTH) }
                putString(command.preferenceKey, (bindings + added).toPreferenceString())
            }
            putBoolean(PREF_SET_UP, true)
        }
    }

    /**
     * What a gesture does on a word card.
     *
     * @param typed whether the card asks to type the word
     * @param verdict the check of the typed word, `null` before the answer is shown
     * @return the command to execute, `null` to ignore the gesture
     */
    fun resolve(
        command: ViewerCommand,
        typed: Boolean,
        verdict: AnswerChecker.Verdict?,
        answerShown: Boolean,
        keyboardUp: Boolean,
    ): ViewerCommand? {
        if (keyboardUp) return null
        if (!typed) return command
        if (!answerShown || verdict == null) return null
        if (command !in GRADES) return command
        // the button "Дальше": Good after an accepted answer, Again after a wrong one
        return if (verdict.accepted) ViewerCommand.ANSWER_GOOD else ViewerCommand.ANSWER_AGAIN
    }

    /** @return whether the one-time hint about the gestures should be shown now; marks it as shown */
    fun takeHint(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(GestureProcessor.PREF_KEY, false) || prefs.getBoolean(PREF_HINT_SHOWN, false)) return false
        prefs.edit { putBoolean(PREF_HINT_SHOWN, true) }
        return true
    }
}
