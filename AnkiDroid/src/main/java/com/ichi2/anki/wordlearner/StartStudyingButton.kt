// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import com.ichi2.anki.R
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import kotlin.math.roundToInt

/**
 * "Начать учиться": one tap continues the daily session of the word deck.
 *
 * It sits in one row with the add button of the deck list, filling the width to its left,
 * and is hidden while the add menu is open or the add button is hidden (deck search).
 */
object StartStudyingButton {
    private const val GAP_DP = 12

    /** The deck which the button studies, `null` until the bundled deck has been imported */
    fun deckToStudy(col: Collection): DeckId? = col.decks.byName(WordDeck.ROOT)?.id

    fun attach(
        fabRoot: CoordinatorLayout,
        fabMain: View,
        fabMenuBackground: View,
        onClick: () -> Unit,
    ): View {
        val density = fabRoot.resources.displayMetrics.density
        val gap = (GAP_DP * density).roundToInt()
        val button = LayoutInflater.from(fabRoot.context).inflate(R.layout.wordlearner_start_button, fabRoot, false)
        button.layoutParams =
            CoordinatorLayout
                .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, button.layoutParams.height)
                .apply {
                    anchorId = fabMain.id
                    anchorGravity = Gravity.START or Gravity.CENTER_VERTICAL
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
        // an anchored view ignores its margins
        button.translationX = -gap.toFloat()
        button.setOnClickListener { onClick() }
        fabRoot.addView(button)
        fabRoot.viewTreeObserver.addOnGlobalLayoutListener {
            val shown = fabMain.isVisible && !fabMenuBackground.isVisible
            if (button.isVisible != shown) button.isVisible = shown
            if (!shown || fabMain.width == 0) return@addOnGlobalLayoutListener
            val left = fabMain.leftIn(fabRoot)
            // the same distance to the left edge as the add button has to the right edge
            val margin = fabRoot.width - fabRoot.paddingRight - left - fabMain.width
            val width = left - gap - margin - fabRoot.paddingLeft
            if (width > 0 && button.layoutParams.width != width) {
                button.layoutParams = button.layoutParams.apply { this.width = width }
            }
        }
        return button
    }

    private fun View.leftIn(ancestor: View): Int {
        val own = IntArray(2).also { getLocationInWindow(it) }
        val other = IntArray(2).also { ancestor.getLocationInWindow(it) }
        return own[0] - other[0]
    }
}
