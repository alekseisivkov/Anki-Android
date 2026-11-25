// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.cardviewer.TypeAnswer
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.Card
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class WordTypeAnswerTest : RobolectricTest() {
    private fun card(front: String): Card {
        if (col.notetypes.byName(WordDeck.NOTE_TYPE) == null) {
            addStandardNoteType(WordDeck.NOTE_TYPE, arrayOf("Wort", "Eingabe"), front, "{{Wort}}")
        }
        val note = col.newNote(col.notetypes.byName(WordDeck.NOTE_TYPE)!!)
        note.setItem("Wort", "Grund")
        note.setItem("Eingabe", "Grund")
        col.addNote(note, 1)
        return note.firstCard(col)
    }

    private fun verdict(
        card: Card,
        typed: String,
    ): AnswerChecker.Verdict? {
        val typeAnswer = TypeAnswer.createInstance(targetContext.sharedPrefs())
        typeAnswer.updateInfo(col, card, targetContext.resources)
        typeAnswer.input = typed
        typeAnswer.filterAnswer("[[type:Eingabe]]")
        return typeAnswer.verdict
    }

    @Test
    fun `a recognition card has no verdict`() = assertNull(verdict(card("{{Wort}}"), ""))

    @Test
    fun `a typed card has a verdict`() {
        val card = card("{{Wort}} {{type:Eingabe}}")
        assertEquals(AnswerChecker.Verdict.CORRECT, verdict(card, "die grund"))
        assertEquals(AnswerChecker.Verdict.WRONG, verdict(card, ""))
    }
}
