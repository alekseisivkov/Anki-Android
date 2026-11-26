// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.deckPicker
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StartStudyingTest : RobolectricTest() {
    private fun addWord(): Long {
        val did = addDeck("${WordDeck.ROOT}::A2::Lektion 01::Teil 1")
        val note = addBasicNote("Grund", "причина")
        col.setDeck(note.cardIds(col), did)
        return note.cardIds(col).first()
    }

    @Test
    fun `there is nothing to study before the word deck exists`() {
        addDeck("Other")
        assertNull(StartStudyingButton.deckToStudy(col))
    }

    @Test
    fun `the button studies the root deck, wherever the selection was`() {
        addWord()
        col.decks.select(addDeck("Other"))
        assertEquals(col.decks.byName(WordDeck.ROOT)!!.id, StartStudyingButton.deckToStudy(col))
    }

    @Test
    fun `the button is shown on the deck list and opens the study screen`() {
        addWord()
        col.decks.select(addDeck("Other"))
        deckPicker {
            val button = findViewById<View>(R.id.wordlearner_start)
            assertTrue(button.isShown, "start button is visible")
            startStudying()
            advanceRobolectricLooper()
            assertEquals(Reviewer::class.java.name, shadowOf(this).nextStartedActivity?.component?.className)
            assertEquals(col.decks.byName(WordDeck.ROOT)!!.id, col.decks.selected())
        }
    }

    @Test
    fun `without cards to study the study screen is not opened`() {
        col.sched.suspendCards(listOf(addWord()))
        deckPicker {
            startStudying()
            advanceRobolectricLooper()
            assertNotEquals(Reviewer::class.java.name, shadowOf(this).nextStartedActivity?.component?.className)
        }
    }
}
