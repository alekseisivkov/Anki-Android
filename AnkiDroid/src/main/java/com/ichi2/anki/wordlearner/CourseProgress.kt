// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.libanki.Collection
import kotlin.math.ceil

/**
 * Progress towards the goal of the app: every word of the course learned and remembered.
 *
 * A word is one note; it is *remembered* when all its cards are mature (an interval of at least [MATURE_DAYS] days,
 * Anki's standard), *in progress* when it was introduced but is not remembered yet. Everything else is *ahead*:
 * words of the app which were not started and the words of lessons which are not in the app yet.
 */
data class CourseProgress(
    /** Every word of the course, also of the lessons which are not in the app yet */
    val courseWords: Int,
    val wordsInApp: Int,
    val remembered: Int,
    val inProgress: Int,
    val today: Today,
    /** Cards which will be due tomorrow (not counting what is left over today) */
    val dueTomorrow: Int,
    /** Cards which will be due in the next [WEEK] days, tomorrow included */
    val dueWeek: Int,
    /** Words started per day over the last [PACE_DAYS] days, `null` while there are fewer than [MIN_PACE_DAYS] days of history */
    val wordsPerDay: Double?,
    /** The part of a lesson which the next new words come from */
    val current: Part?,
    /** First answers of the day on recently introduced words, as used by [NewCardMix] */
    val success: NewCardMix.Success,
) {
    data class Today(
        val newWords: Int,
        val answers: Int,
        val seconds: Int,
        /** Reviews and learning steps which are still due today */
        val due: Int,
    )

    /** `Deutsch::A2::Lektion 01::Teil 2` */
    data class Part(
        val level: String,
        val lesson: Int,
        val part: Int?,
    )

    val started: Int get() = remembered + inProgress

    /** Not started yet, also the words which are not in the app yet */
    val ahead: Int get() = (courseWords - started).coerceAtLeast(0)

    /** Nothing was ever studied */
    val isFresh: Boolean get() = started == 0 && today.answers == 0

    /** Every word of the app was started, but the course has more */
    val waitsForLessons: Boolean get() = wordsInApp in 1..started && courseWords > wordsInApp

    /** Days until every word of the course is started at the current pace */
    val daysToStartAll: Int?
        get() {
            val pace = wordsPerDay?.takeIf { it > 0 } ?: return null
            if (ahead == 0) return null
            return ceil(ahead / pace).toInt()
        }

    companion object {
        const val MATURE_DAYS = 21
        const val WEEK = 7
        const val PACE_DAYS = 14
        const val MIN_PACE_DAYS = 7
        private const val DAY = 86_400L

        // every query must start with "select": the backend discards the undo history after any other statement
        fun compute(
            col: Collection,
            course: Course?,
        ): CourseProgress {
            val dids =
                col.decks
                    .allNamesAndIds()
                    .filter { WordDeck.isWordDeck(it.name) }
                    .joinToString(",") { it.id.toString() }
                    .ifEmpty { "-1" }
            val inDecks = "(did in ($dids) or odid in ($dids))"
            val cardInDecks = "(c.did in ($dids) or c.odid in ($dids))"
            val today = col.sched.today
            val cutoff = col.sched.dayCutoff
            val dayStart = cutoff - DAY

            var words = 0
            var remembered = 0
            var started = 0
            col.db
                .query(
                    """
                    select count(), coalesce(sum(r), 0), coalesce(sum(s), 0) from (
                      select min(type = 2 and ivl >= $MATURE_DAYS) as r, max(type != 0) as s
                      from cards where $inDecks group by nid
                    )
                    """.trimIndent(),
                ).use {
                    if (it.moveToFirst()) {
                        words = it.getInt(0)
                        remembered = it.getInt(1)
                        started = it.getInt(2)
                    }
                }

            var answers = 0
            var millis = 0L
            col.db
                .query(
                    "select count(), coalesce(sum(time), 0) from revlog " +
                        "where id > ${dayStart * 1000} and ease > 0 and cid in (select id from cards where $inDecks)",
                ).use {
                    if (it.moveToFirst()) {
                        answers = it.getInt(0)
                        millis = it.getLong(1)
                    }
                }

            // the first answer of each word: when it was started
            val firstAnswers =
                col.db.queryLongList(
                    "select min(r.id) from revlog r join cards c on c.id = r.cid " +
                        "where r.ease > 0 and $cardInDecks group by c.nid",
                )

            fun dayIndex(revlogId: Long) = Math.floorDiv(revlogId / 1000 - dayStart, DAY).toInt()
            val firstDays = firstAnswers.map(::dayIndex)

            var dueToday = 0
            var dueTomorrow = 0
            var dueWeek = 0
            // buried cards come back tomorrow; due is a day number for review cards and a time for learning cards
            col.db
                .query(
                    """
                    select
                      coalesce(sum((queue in (2, 3) and due <= $today) or (queue = 1 and due < $cutoff)), 0),
                      coalesce(sum((queue in (2, 3) and due = ${today + 1})
                        or (queue = 1 and due >= $cutoff and due < ${cutoff + DAY})
                        or (queue in (-2, -3) and type != 0 and (type != 2 or due <= ${today + 1}))), 0),
                      coalesce(sum((queue in (2, 3) and due between ${today + 1} and ${today + WEEK})
                        or (queue = 1 and due >= $cutoff and due < ${cutoff + WEEK * DAY})
                        or (queue in (-2, -3) and type != 0 and (type != 2 or due <= ${today + WEEK}))), 0)
                    from cards where $inDecks
                    """.trimIndent(),
                ).use {
                    if (it.moveToFirst()) {
                        dueToday = it.getInt(0)
                        dueTomorrow = it.getInt(1)
                        dueWeek = it.getInt(2)
                    }
                }

            return CourseProgress(
                courseWords = maxOf(course?.words ?: 0, words),
                wordsInApp = words,
                remembered = remembered,
                inProgress = started - remembered,
                today =
                    Today(
                        newWords = firstDays.count { it == 0 },
                        answers = answers,
                        seconds = (millis / 1000).toInt(),
                        due = dueToday,
                    ),
                dueTomorrow = dueTomorrow,
                dueWeek = dueWeek,
                wordsPerDay = pace(firstDays),
                current = currentPart(col, inDecks, cardInDecks),
                success = NewCardMix.success(col),
            )
        }

        /**
         * Words started per day since the first day of study, over the last [PACE_DAYS] days including today.
         *
         * @param firstDays the day on which each word was started, today is 0, yesterday -1
         */
        fun pace(firstDays: List<Int>): Double? {
            val first = firstDays.minOrNull() ?: return null
            val windowStart = maxOf(first, -(PACE_DAYS - 1))
            val days = 1 - windowStart
            if (days < MIN_PACE_DAYS) return null
            return firstDays.count { it >= windowStart }.toDouble() / days
        }

        private fun currentPart(
            col: Collection,
            inDecks: String,
            cardInDecks: String,
        ): Part? {
            val did =
                col.db
                    .queryLongList(
                        "select case when odid != 0 then odid else did end from cards " +
                            "where $inDecks and queue = 0 order by due, ord limit 1",
                    ).firstOrNull()
                    ?: col.db
                        .queryLongList(
                            "select case when c.odid != 0 then c.odid else c.did end from revlog r " +
                                "join cards c on c.id = r.cid where $cardInDecks order by r.id desc limit 1",
                        ).firstOrNull()
                    ?: return null
            return col.decks.name(did).let(::parsePart)
        }

        private val PART = Regex("""^${WordDeck.ROOT}::(\w+)::Lektion (\d+)(?:::Teil (\d+))?""")

        fun parsePart(deckName: String): Part? =
            PART.find(deckName)?.let { m ->
                Part(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
            }
    }
}
