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
import kotlin.test.assertFalse
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
    fun `the app opens with the progress screen and its button opens the study screen`() {
        addWord()
        col.decks.select(addDeck("Other"))
        deckPicker {
            progressScreen.reload()
            assertTrue(progressScreen.isShown)
            assertFalse(findViewById<View>(R.id.decks).isShown, "deck list is hidden")
            findViewById<View>(R.id.wl_progress_start).performClick()
            advanceRobolectricLooper()
            assertEquals(Reviewer::class.java.name, shadowOf(this).nextStartedActivity?.component?.className)
            assertEquals(col.decks.byName(WordDeck.ROOT)!!.id, col.decks.selected())
        }
    }

    @Test
    fun `the deck list is one tap away and Back returns to the progress screen`() {
        addWord()
        deckPicker {
            progressScreen.reload()
            progressScreen.showDeckList(true)
            assertFalse(progressScreen.isShown)
            assertTrue(findViewById<View>(R.id.wordlearner_start).isShown, "start button of the deck list is visible")
            onBackPressedDispatcher.onBackPressed()
            assertTrue(progressScreen.isShown)
            assertFalse(isFinishing)
        }
    }

    @Test
    fun `without the word deck the deck list stays`() {
        addDeck("Other")
        deckPicker {
            progressScreen.reload()
            assertFalse(progressScreen.isShown)
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
