// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import java.time.LocalDate
import kotlin.math.roundToInt

/** The texts of the progress screen. Russian only: plural forms do not follow the phone's language. */
data class ProgressText(
    val heroNumber: String,
    val heroLabel: String,
    /** Shown before the first session instead of the numbers of the day */
    val heroNote: String?,
    /** The numbers under the bar; before the first session they would only repeat the course */
    val legend: Boolean,
    val remembered: String,
    val inProgress: String,
    val ahead: String,
    val help: String,
    /** `null` before the first session */
    val today: List<Tile>?,
    val current: String?,
    /** `null` before the first session */
    val ahead7: List<Row>?,
    val outlook: String?,
) {
    data class Tile(
        val value: String,
        val label: String,
    )

    data class Row(
        val label: String,
        val value: String,
    )

    companion object {
        private const val NBSP = ' '

        fun of(
            p: CourseProgress,
            course: Course?,
            date: LocalDate,
        ): ProgressText {
            val fresh = p.isFresh
            return ProgressText(
                heroNumber = if (fresh) "${p.courseWords}" else "${p.remembered}",
                heroLabel =
                    if (fresh) {
                        "${Russian.plural(p.courseWords, "слово", "слова", "слов")} в${NBSP}курсе"
                    } else {
                        "${Russian.plural(p.remembered, "слово запомнено", "слова запомнено", "слов запомнено")} из${NBSP}${p.courseWords}"
                    },
                heroNote =
                    when {
                        fresh -> "Начните учиться$NBSP— и${NBSP}здесь появятся запомненные слова."
                        // nothing can be remembered before it was repeated over three weeks
                        p.remembered == 0 ->
                            "Первые запомненные слова появятся недели через три: слово запомнено, " +
                                "когда его повторяют раз в${NBSP}${CourseProgress.MATURE_DAYS}${NBSP}день и${NBSP}реже."
                        else -> null
                    },
                legend = !fresh,
                remembered = "${p.remembered}",
                inProgress = "${p.inProgress}",
                ahead = "${p.ahead}",
                help = help(p, course),
                today = if (fresh) null else today(p),
                current = p.current?.let(::part),
                ahead7 = if (p.started == 0) null else ahead7(p),
                outlook = if (p.started == 0) null else outlook(p, date),
            )
        }

        private fun today(p: CourseProgress): List<Tile> {
            val t = p.today
            val minutes = (t.seconds + 30) / 60
            val rate = p.success.rate
            return listOf(
                Tile("${t.newWords}", Russian.plural(t.newWords, "новое слово", "новых слова", "новых слов")),
                Tile(
                    "${t.answers}",
                    Russian.plural(t.answers, "ответ", "ответа", "ответов") +
                        if (minutes > 0) " за$NBSP$minutes${NBSP}мин" else "",
                ),
                if (rate == null) {
                    Tile("—", "верно с первого раза: пока мало ответов")
                } else {
                    Tile(percent(rate), "верно с первого раза, цель ${percent(TARGET)}")
                },
                Tile("${t.due}", if (t.due == 0) "повторять больше нечего" else "ещё повторить"),
            )
        }

        private fun ahead7(p: CourseProgress): List<Row> =
            buildList {
                add(Row("Завтра", cards(p.dueTomorrow)))
                add(Row("За${NBSP}неделю", cards(p.dueWeek)))
                // while the app waits for the next lesson, the pace measures the lessons, not the learner
                if (!p.waitsForLessons) p.wordsPerDay?.let { add(Row("Темп", wordsPerDay(it))) }
            }

        private fun outlook(
            p: CourseProgress,
            date: LocalDate,
        ): String? {
            if (p.ahead == 0) return "Все слова курса начаты."
            if (p.waitsForLessons) return "Все слова приложения начаты. Новые появятся со${NBSP}следующим уроком."
            val days = p.daysToStartAll ?: return null
            return "При таком темпе весь курс будет начат ${Russian.byDate(date.plusDays(days.toLong()), date)}."
        }

        private fun help(
            p: CourseProgress,
            course: Course?,
        ): String =
            buildString {
                append("Запомнено$NBSP— слово, обе карточки которого, узнавание и${NBSP}вспоминание, ")
                append("повторяются с${NBSP}интервалом от${NBSP}${CourseProgress.MATURE_DAYS}${NBSP}дня. ")
                append("В${NBSP}процессе$NBSP— начатые слова, которые ещё закрепляются. ")
                append("Впереди$NBSP— ещё не${NBSP}начатые слова, в${NBSP}том числе слова уроков, которых пока нет в${NBSP}приложении.")
                if (course != null && course.levels.isNotEmpty()) {
                    append("\n\nВ курсе ${p.courseWords} ${Russian.plural(p.courseWords, "слово", "слова", "слов")}: ")
                    append(course.levels.joinToString(", ") { "${it.name}$NBSP— ${it.words}" })
                    append(". В${NBSP}приложении пока ${p.wordsInApp}")
                    val lessons =
                        course.levels
                            .mapNotNull { level ->
                                level.lessons
                                    .filter { it.inApp > 0 }
                                    .map { it.number }
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { "${level.name}, ${lessonList(it)}" }
                            }
                    if (lessons.isNotEmpty()) append(": ${lessons.joinToString("; ")}")
                    append(".")
                }
                append("\n\nВерно с${NBSP}первого раза$NBSP— первые ответы дня по${NBSP}недавно начатым словам ")
                append("(последние ${NewCardMix.WINDOW}). Если их больше ${percent(NewCardMix.HIGH)}, ")
                append("новых слов в${NBSP}порции становится больше, если меньше ${percent(NewCardMix.LOW)}$NBSP— меньше.")
            }

        private fun lessonList(numbers: List<Int>): String {
            val sorted = numbers.sorted()
            if (sorted.size == 1) return "урок ${sorted[0]}"
            val contiguous = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
            return "уроки " + if (contiguous && sorted.size > 2) "${sorted.first()}–${sorted.last()}" else sorted.joinToString(", ")
        }

        fun part(part: CourseProgress.Part): String = "${part.level}, урок ${part.lesson}" + (part.part?.let { ", часть $it" } ?: "")

        fun cards(n: Int) = "$n$NBSP${Russian.plural(n, "карточка", "карточки", "карточек")}"

        fun wordsPerDay(pace: Double): String {
            val tenths = (pace * 10).roundToInt()
            return if (tenths % 10 == 0 || pace >= 10) {
                val n = pace.roundToInt()
                "$n$NBSP${Russian.plural(n, "новое слово", "новых слова", "новых слов")} в${NBSP}день"
            } else {
                // a fraction takes the genitive singular: 1,5 слова
                "${tenths / 10},${tenths % 10}${NBSP}нового слова в${NBSP}день"
            }
        }

        fun percent(rate: Double) = "${(rate * 100).roundToInt()}$NBSP%"

        private const val TARGET = 0.8
    }
}

object Russian {
    /** [one] for 1, 21, 101; [few] for 2–4, 22–24; [many] for 0, 5–20, 25–30 */
    fun plural(
        n: Int,
        one: String,
        few: String,
        many: String,
    ): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private val MONTHS_DATIVE =
        listOf("январю", "февралю", "марту", "апрелю", "маю", "июню", "июлю", "августу", "сентябрю", "октябрю", "ноябрю", "декабрю")

    /** "к марту", "к марту 2028 года", or "через 12 дней" when it is less than a month away */
    fun byDate(
        date: LocalDate,
        today: LocalDate,
    ): String {
        val days = (date.toEpochDay() - today.toEpochDay()).toInt()
        if (days < 31) return "через $days ${plural(days, "день", "дня", "дней")}"
        val month = MONTHS_DATIVE[date.monthValue - 1]
        return if (date.year == today.year) "к $month" else "к $month ${date.year} года"
    }
}
