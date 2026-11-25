// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection

/** Names shared with the deck builder */
object WordDeck {
    const val NOTE_TYPE = "Wörter"
    const val ROOT = "Deutsch"

    fun isWordCard(
        col: Collection,
        card: Card,
    ): Boolean = card.noteType(col).name == NOTE_TYPE

    fun isWordDeck(name: String): Boolean = name == ROOT || name.startsWith("$ROOT::")
}
