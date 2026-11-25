// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.Deck
import com.ichi2.anki.wordlearner.NewCardMix.Success
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NewCardMixTest : RobolectricTest() {
    private fun root(): Deck = assertNotNull(col.decks.byName(WordDeck.ROOT))

    private fun addWords(count: Int) {
        val did = addDeck("${WordDeck.ROOT}::A2::Lektion 01::Teil 1")
        repeat(count) {
            val note = addBasicNote("w$it", "b")
            note.notetype // the deck is set on the card
            col.setDeck(note.cardIds(col), did)
        }
        col.decks.select(root().id)
    }

    /** First answer [daysAgo] + 5 days ago, then one answer [daysAgo] days ago */
    private fun addHistory(
        answers: Int,
        correct: Int,
        daysAgo: Int = 1,
    ) {
        val cutoff = col.sched.dayCutoff
        repeat(answers) {
            fun insert(
                days: Int,
                ease: Int,
            ) {
                val id = (cutoff - days * 86_400L - 40_000L) * 1000 + it
                col.db.execute("insert into revlog values (?,?,0,?,1,1,2500,1000,1)", id, 1_000_000L + it, ease)
            }
            insert(daysAgo + 5, 1)
            insert(daysAgo, if (it < correct) 3 else 1)
        }
    }

    private fun answer(rating: Rating) = col.sched.answerCard(assertNotNull(col.sched.currentQueueState()), rating)

    private fun newCount() = assertNotNull(col.sched.currentQueueState()).counts.new

    @Test
    fun `portions follow the success rate`() {
        assertEquals(8, NewCardMix.portion(Success(5, 0)))
        assertEquals(12, NewCardMix.portion(Success(50, 45)))
        assertEquals(8, NewCardMix.portion(Success(50, 40)))
        assertEquals(4, NewCardMix.portion(Success(50, 30)))
        assertTrue(NewCardMix.holdsNewCards(Success(50, 30)))
        assertFalse(NewCardMix.holdsNewCards(Success(50, 40)))
        assertFalse(NewCardMix.holdsNewCards(Success(9, 0)))
    }

    @Test
    fun `success ignores the day a card was introduced`() {
        addWords(1)
        addHistory(answers = 20, correct = 15)
        assertEquals(Success(samples = 20, correct = 15), NewCardMix.success(col))
    }

    @Test
    fun `success ignores old words`() {
        addWords(1)
        addHistory(answers = 20, correct = 15, daysAgo = 40)
        assertEquals(0, NewCardMix.success(col).samples)
    }

    @Test
    fun `a portion is available when the deck is opened`() {
        addWords(40)
        NewCardMix.ensureAvailable(col)
        assertEquals(8, newCount())
    }

    @Test
    fun `studying can always be continued until the words run out`() {
        addWords(20)
        NewCardMix.ensureAvailable(col)
        repeat(20) {
            if (col.sched.currentQueueState() == null) assertTrue(NewCardMix.extend(col), "after $it cards")
            answer(Rating.EASY)
        }
        assertNull(col.sched.currentQueueState())
        assertFalse(NewCardMix.extend(col))
    }

    @Test
    fun `low success holds new cards back but does not block`() {
        addWords(30)
        addHistory(answers = 20, correct = 5)
        NewCardMix.ensureAvailable(col)
        assertEquals(4, newCount())
        answer(Rating.EASY)
        NewCardMix.onAnswered(col)
        assertNull(col.sched.currentQueueState())
        assertTrue(NewCardMix.extend(col))
        assertEquals(4, newCount())
    }

    @Test
    fun `undo takes back the answer after the portion was extended`() {
        addWords(30)
        NewCardMix.ensureAvailable(col)
        answer(Rating.EASY)
        val label = col.undoStatus().undo
        NewCardMix.extend(col)
        assertEquals(label, col.undoStatus().undo)
    }

    @Test
    fun `other decks are not changed`() {
        val did = addDeck("Other")
        col.decks.select(did)
        NewCardMix.ensureAvailable(col)
        assertFalse(NewCardMix.extend(col))
        assertTrue(assertNotNull(col.decks.getLegacy(did)).isNull("newLimitToday"))
    }
}
