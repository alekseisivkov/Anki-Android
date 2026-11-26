// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.wordlearner.CourseProgress.Part
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CourseProgressTest : RobolectricTest() {
    private val course = Course(words = 2391, levels = emptyList())
    private var revlogSequence = 0L

    /** Words with two cards each, like the recognition and the recall card */
    private fun addWords(
        count: Int,
        deck: String = TEIL_1,
    ): List<Note> {
        val did = addDeck(deck)
        return List(count) {
            addBasicAndReversedNote("w$it", "b").also { col.setDeck(it.cardIds(col), did) }
        }
    }

    private fun Note.wordCards(): List<CardId> = cardIds(col).sorted()

    private fun setCard(
        cid: CardId,
        type: Int,
        queue: Int,
        ivl: Int = 0,
        due: Long = 0,
    ) = col.db.execute("update cards set type = ?, queue = ?, ivl = ?, due = ? where id = ?", type, queue, ivl, due, cid)

    private fun mature(cid: CardId) = setCard(cid, type = 2, queue = 2, ivl = 30, due = col.sched.today + 20L)

    /** An answer [daysAgo] days ago; 0 is today */
    private fun answer(
        cid: CardId,
        daysAgo: Int,
        ease: Int = 3,
        millis: Int = 5000,
    ) {
        val id = (col.sched.dayCutoff - daysAgo * DAY - 40_000L) * 1000 + revlogSequence++
        col.db.execute("insert into revlog values (?,?,0,?,1,1,2500,?,1)", id, cid, ease, millis)
    }

    private fun progress(course: Course? = this.course) = CourseProgress.compute(col, course)

    @Test
    fun `a word is remembered when both of its cards are mature`() {
        val (both, one, learning, _) = addWords(4)
        both.wordCards().forEach(::mature)
        mature(one.wordCards()[0])
        setCard(one.wordCards()[1], type = 2, queue = 2, ivl = 5, due = col.sched.today + 5L)
        setCard(learning.wordCards()[0], type = 1, queue = 1, due = col.sched.dayCutoff - 100)
        // a word outside of the word deck does not count
        addBasicNote("other", "deck").cardIds(col).forEach(::mature)

        val p = progress()
        assertEquals(2391, p.courseWords)
        assertEquals(4, p.wordsInApp)
        assertEquals(1, p.remembered)
        assertEquals(2, p.inProgress)
        assertEquals(2388, p.ahead)
        assertTrue(!p.isFresh)
    }

    @Test
    fun `a suspended mature word stays remembered, a relearning one does not`() {
        val (suspended, relearning) = addWords(2)
        suspended.wordCards().forEach { setCard(it, type = 2, queue = -1, ivl = 40, due = col.sched.today + 3L) }
        relearning.wordCards().forEach(::mature)
        setCard(relearning.wordCards()[1], type = 3, queue = 1, ivl = 1, due = col.sched.dayCutoff - 100)
        assertEquals(1, progress().remembered)
        assertEquals(1, progress().inProgress)
    }

    @Test
    fun `without the course file the words of the app are the course`() {
        addWords(3)
        val p = progress(course = null)
        assertEquals(3, p.courseWords)
        assertEquals(3, p.ahead)
        assertTrue(p.isFresh)
    }

    @Test
    fun `today counts the words started today, the answers and the time`() {
        val (old, new) = addWords(2)
        answer(old.wordCards()[0], daysAgo = 3)
        answer(old.wordCards()[0], daysAgo = 0, millis = 4000)
        answer(new.wordCards()[0], daysAgo = 0, ease = 1, millis = 6000)
        answer(new.wordCards()[0], daysAgo = 0, millis = 5000)
        // a manual reschedule is not an answer
        answer(new.wordCards()[1], daysAgo = 0, ease = 0)

        val today = progress().today
        assertEquals(1, today.newWords)
        assertEquals(3, today.answers)
        assertEquals(15, today.seconds)
    }

    @Test
    fun `cards due today, tomorrow and during the week`() {
        val today = col.sched.today.toLong()
        val cutoff = col.sched.dayCutoff
        val cards = addWords(6).flatMap { it.wordCards() }
        setCard(cards[0], type = 2, queue = 2, ivl = 3, due = today - 2) // overdue: today
        setCard(cards[1], type = 2, queue = 2, ivl = 3, due = today) // today
        setCard(cards[2], type = 1, queue = 1, due = cutoff - 100) // learning step later today
        setCard(cards[3], type = 3, queue = 3, ivl = 1, due = today) // relearning over days: today
        setCard(cards[4], type = 2, queue = 2, ivl = 3, due = today + 1) // tomorrow
        setCard(cards[5], type = 1, queue = 1, due = cutoff + 3600) // learning step after midnight: tomorrow
        setCard(cards[6], type = 2, queue = -2, ivl = 3, due = today) // buried sibling: back tomorrow
        setCard(cards[7], type = 2, queue = 2, ivl = 3, due = today + 5) // this week
        setCard(cards[8], type = 2, queue = 2, ivl = 30, due = today + 9) // later
        setCard(cards[9], type = 2, queue = -1, ivl = 3, due = today) // suspended: never
        // 10, 11: new cards are never due

        val p = progress()
        assertEquals(4, p.today.due)
        assertEquals(3, p.dueTomorrow)
        assertEquals(4, p.dueWeek)
    }

    @Test
    fun `the current part is where the next new words come from`() {
        val started = addWords(1, TEIL_1).single()
        addWords(1, "${WordDeck.ROOT}::A2::Lektion 01::Teil 2")
        started.wordCards().forEach(::mature)
        assertEquals(Part("A2", 1, 2), progress().current)
    }

    @Test
    fun `when every word was started, the current part is the last one studied`() {
        val word = addWords(1).single()
        word.wordCards().forEach(::mature)
        answer(word.wordCards()[0], daysAgo = 1)
        assertEquals(Part("A2", 1, 1), progress().current)
    }

    @Test
    fun `the pace needs a week of history`() {
        assertNull(CourseProgress.pace(emptyList()))
        assertNull(CourseProgress.pace(listOf(0, 0, -5)))
        assertEquals(1.0, CourseProgress.pace(listOf(0, -1, -2, -3, -4, -5, -6)))
        // only the last two weeks count
        assertEquals(0.5, CourseProgress.pace(List(7) { -it * 2 } + listOf(-20, -30)))
    }

    @Test
    fun `the days to start the whole course follow the pace`() {
        val p =
            CourseProgress(
                courseWords = 100,
                wordsInApp = 69,
                remembered = 10,
                inProgress = 30,
                today = CourseProgress.Today(0, 0, 0, 0),
                dueTomorrow = 0,
                dueWeek = 0,
                wordsPerDay = 4.0,
                current = null,
                success = NewCardMix.Success(0, 0),
            )
        assertEquals(15, p.daysToStartAll)
        assertNull(p.copy(wordsPerDay = null).daysToStartAll)
        assertNull(p.copy(remembered = 70).daysToStartAll)
        assertTrue(p.copy(inProgress = 59).waitsForLessons)
    }

    @Test
    fun `deck names become parts of lessons`() {
        assertEquals(Part("A2", 1, 2), CourseProgress.parsePart("Deutsch::A2::Lektion 01::Teil 2"))
        assertEquals(Part("B1", 12, null), CourseProgress.parsePart("Deutsch::B1::Lektion 12"))
        assertNull(CourseProgress.parsePart("Deutsch::A2"))
    }

    @Test
    fun `the course file lists the lessons of every level`() {
        val parsed =
            Course.parse(
                """{"words": 160, "levels": [
                  {"level": "A2", "words": 100, "lessons": [{"lesson": 1, "words": 69, "inApp": 69}, {"lesson": 2, "words": 31, "inApp": 0}]},
                  {"level": "B1", "words": 60, "lessons": [{"lesson": 1, "words": 60, "inApp": 0}]}]}""",
            )
        assertEquals(160, parsed.words)
        assertEquals(listOf("A2", "B1"), parsed.levels.map { it.name })
        assertEquals(Course.Lesson(1, 69, 69), parsed.levels[0].lessons[0])
    }

    companion object {
        private const val DAY = 86_400L
        private const val TEIL_1 = "${WordDeck.ROOT}::A2::Lektion 01::Teil 1"
    }
}
