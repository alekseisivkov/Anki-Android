// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.wordlearner.CourseProgress.Part
import com.ichi2.anki.wordlearner.CourseProgress.Today
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProgressTextTest {
    private val date = LocalDate.of(2026, 11, 26)
    private val course =
        Course(
            words = 2391,
            levels =
                listOf(
                    Course.Level("A2", 1174, listOf(Course.Lesson(1, 69, 69), Course.Lesson(2, 95, 0))),
                    Course.Level("B1", 1217, listOf(Course.Lesson(1, 92, 0))),
                ),
        )
    private val fresh =
        CourseProgress(
            courseWords = 2391,
            wordsInApp = 69,
            remembered = 0,
            inProgress = 0,
            today = Today(newWords = 0, answers = 0, seconds = 0, due = 0),
            dueTomorrow = 0,
            dueWeek = 0,
            wordsPerDay = null,
            current = Part("A2", 1, 1),
            success = NewCardMix.Success(0, 0),
        )
    private val studying =
        fresh.copy(
            remembered = 12,
            inProgress = 45,
            today = Today(newWords = 6, answers = 48, seconds = 540, due = 12),
            dueTomorrow = 21,
            dueWeek = 64,
            current = Part("A2", 1, 2),
            success = NewCardMix.Success(50, 41),
        )

    private fun String.plain() = replace(' ', ' ')

    @Test
    fun `russian plural forms`() {
        fun words(n: Int) = "$n ${Russian.plural(n, "слово", "слова", "слов")}"
        assertEquals(
            "0 слов, 1 слово, 2 слова, 4 слова, 5 слов, 11 слов, 14 слов, 21 слово, 22 слова, 111 слов, 2391 слово".split(", "),
            listOf(0, 1, 2, 4, 5, 11, 14, 21, 22, 111, 2391).map(::words),
        )
    }

    @Test
    fun `before the first session the course is the goal, without numbers of a day`() {
        val text = ProgressText.of(fresh, course, date)
        assertEquals("2391", text.heroNumber)
        assertEquals("слово в курсе", text.heroLabel.plain())
        assertNotNull(text.heroNote)
        assertEquals(false, text.legend)
        assertNull(text.today)
        assertNull(text.ahead7)
        assertNull(text.outlook)
        assertEquals("2391", text.ahead)
    }

    @Test
    fun `after a session the remembered words are the goal`() {
        val text = ProgressText.of(studying, course, date)
        assertEquals("12", text.heroNumber)
        assertEquals("слов запомнено из 2391", text.heroLabel.plain())
        assertEquals("45", text.inProgress)
        assertEquals("2334", text.ahead)
        assertNull(text.heroNote)
        assertEquals("A2, урок 1, часть 2", text.current)
        val today = assertNotNull(text.today).map { "${it.value} ${it.label}".plain() }
        assertEquals(
            listOf("6 новых слов", "48 ответов за 9 мин", "82 % верно с первого раза, цель 80 %", "12 ещё повторить"),
            today,
        )
        assertEquals(
            listOf("Завтра 21 карточка", "За неделю 64 карточки"),
            assertNotNull(text.ahead7).map { "${it.label} ${it.value}".plain() },
        )
        // no estimate without a week of history
        assertNull(text.outlook)
    }

    @Test
    fun `while no word can be remembered yet, the hero says when the first will be`() {
        val text = ProgressText.of(studying.copy(remembered = 0), course, date)
        assertEquals("0", text.heroNumber)
        assertContains(assertNotNull(text.heroNote).plain(), "недели через три")
        assertEquals(true, text.legend)
    }

    @Test
    fun `one remembered word`() {
        assertEquals("слово запомнено из 2391", ProgressText.of(studying.copy(remembered = 1), course, date).heroLabel.plain())
    }

    @Test
    fun `without enough answers the success rate is not guessed`() {
        val text = ProgressText.of(studying.copy(success = NewCardMix.Success(4, 4)), course, date)
        assertEquals("—", assertNotNull(text.today)[2].value)
    }

    @Test
    fun `the pace gives a date for the whole course`() {
        val text = ProgressText.of(studying.copy(wordsPerDay = 6.0), course, date)
        assertEquals("Темп 6 новых слов в день", assertNotNull(text.ahead7).last().let { "${it.label} ${it.value}" }.plain())
        // 2334 words at 6 a day: 389 days
        assertEquals("При таком темпе весь курс будет начат к декабрю 2027 года.", assertNotNull(text.outlook).plain())
    }

    @Test
    fun `a slow pace keeps its fraction`() {
        assertEquals("1,5 нового слова в день", ProgressText.wordsPerDay(1.5).plain())
        assertEquals("1 новое слово в день", ProgressText.wordsPerDay(1.0).plain())
    }

    @Test
    fun `when every word of the app was started, the next lesson is awaited`() {
        val text = ProgressText.of(studying.copy(remembered = 20, inProgress = 49, wordsPerDay = 6.0), course, date)
        assertEquals("Все слова приложения начаты. Новые появятся со следующим уроком.", assertNotNull(text.outlook).plain())
        assertEquals(listOf("Завтра", "За неделю"), assertNotNull(text.ahead7).map { it.label.plain() })
    }

    @Test
    fun `the explanation names the course and what the app has`() {
        val help = ProgressText.of(studying, course, date).help.plain()
        assertContains(help, "интервалом от 21 дня")
        assertContains(help, "В курсе 2391 слово: A2 — 1174, B1 — 1217. В приложении пока 69: A2, урок 1.")
    }

    @Test
    fun `dates in russian`() {
        assertEquals("к марту", Russian.byDate(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 1, 2)).plain())
        assertEquals("через 12 дней", Russian.byDate(date.plusDays(12), date).plain())
        assertEquals("через 1 день", Russian.byDate(date.plusDays(1), date).plain())
    }
}
