// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Deck
import org.json.JSONObject
import timber.log.Timber

/**
 * Keeps the success rate on recently introduced words near 80% by changing how many new cards are mixed in.
 *
 * It never ends a session: when the queue is empty, the next portion of new cards is added. A low success rate makes
 * the portions smaller and holds new cards back until the cards which are due have been answered.
 */
object NewCardMix {
    const val WINDOW = 50
    const val MIN_SAMPLES = 10
    const val LOW = 0.75
    const val HIGH = 0.85

    /** Success rate of [samples] answers */
    data class Success(
        val samples: Int,
        val correct: Int,
    ) {
        val rate: Double? get() = if (samples < MIN_SAMPLES) null else correct.toDouble() / samples
    }

    /** Number of new cards in the next portion */
    fun portion(success: Success): Int {
        val rate = success.rate ?: return 8
        return when {
            rate > HIGH -> 12
            rate < LOW -> 4
            else -> 8
        }
    }

    /** Whether new cards wait until the cards which are due have been answered */
    fun holdsNewCards(success: Success): Boolean = success.rate?.let { it < LOW } ?: false

    /** Called before a deck is opened: there is always something to study, unless every word was introduced. */
    fun ensureAvailable(col: Collection) {
        val deck = selectedWordDeck(col) ?: return
        if (todaysLimit(col, deck) == null) {
            setLimit(col, deck, introducedToday(col, deck) + portion(success(col)))
        }
        if (col.sched.currentQueueState() == null) extend(col)
    }

    /** Called after an answer */
    fun onAnswered(col: Collection) {
        val deck = selectedWordDeck(col) ?: return
        val introduced = introducedToday(col, deck)
        val limit = todaysLimit(col, deck) ?: return
        if (limit > introduced && holdsNewCards(success(col))) {
            Timber.i("holding new cards back")
            setLimit(col, deck, introduced)
        }
    }

    /**
     * Called when the queue is empty: adds a portion of new cards.
     *
     * @return whether the deck has cards to study now
     */
    fun extend(col: Collection): Boolean {
        val deck = selectedWordDeck(col) ?: return false
        val portion = portion(success(col))
        Timber.i("adding %d new cards", portion)
        setLimit(col, deck, introducedToday(col, deck) + portion)
        return col.sched.currentQueueState() != null
    }

    /**
     * The first answers of a day to cards which were introduced on an earlier day of the last month.
     * The answers of the day when a card was introduced say nothing about remembering it.
     */
    fun success(col: Collection): Success {
        // must start with "select": the backend discards the undo history after any other statement
        val cutoff = col.sched.dayCutoff
        val since = (cutoff - 31 * DAY) * 1000
        val ratings =
            col.db.queryLongList(
                """
                select r.ease from (
                  select cid, min(id) as fid, (id / 1000 - $cutoff) / $DAY as day
                  from revlog where id > $since and ease > 0 and type < 3 group by cid, day
                ) f join revlog r on r.id = f.fid
                where (select min(x.id) from revlog x where x.cid = f.cid and x.ease > 0 and x.type < 3)
                  between $since and ($cutoff + (f.day - 1) * $DAY) * 1000
                order by f.fid desc limit $WINDOW
                """.trimIndent(),
            )
        return Success(samples = ratings.size, correct = ratings.count { it > 1 })
    }

    private fun selectedWordDeck(col: Collection): Deck? =
        col.decks.getLegacy(col.decks.selected())?.takeIf { it.isNormal && WordDeck.isWordDeck(it.name) }

    private fun introducedToday(
        col: Collection,
        deck: Deck,
    ): Int {
        val (day, count) = deck.optJSONArray("newToday")?.let { it.optInt(0) to it.optInt(1) } ?: return 0
        return if (day == col.sched.today) count else 0
    }

    private fun todaysLimit(
        col: Collection,
        deck: Deck,
    ): Int? =
        deck
            .optJSONObject("newLimitToday")
            ?.takeIf { it.optInt("today", -1) == col.sched.today }
            ?.optInt("limit")

    private fun setLimit(
        col: Collection,
        deck: Deck,
        limit: Int,
    ) {
        val previousStep = col.undoStatus().lastStep
        deck.put("newLimitToday", JSONObject().put("limit", limit).put("today", col.sched.today))
        col.decks.save(deck)
        // "undo" should take back the answer, not this change
        if (previousStep != 0) {
            runCatching { col.mergeUndoEntries(previousStep) }.onFailure { Timber.w(it, "undo entries not merged") }
        }
    }

    private const val DAY = 86_400L
}
