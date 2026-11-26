// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate

/**
 * The screen the app opens with: progress towards the whole course, the day, and "Начать учиться".
 *
 * It takes the place of the deck list inside the deck picker, so the startup of the deck picker is unchanged.
 * The deck list is one tap away in the toolbar, and Back returns from it. Without the word deck, the deck list is shown.
 */
class ProgressScreen(
    private val activity: AppCompatActivity,
    private val onStartStudying: () -> Unit,
) {
    private lateinit var root: View
    private lateinit var deckList: View
    private var attached = false

    /** The word deck exists */
    private var available = false

    /** The user asked for the deck list */
    private var wantsDeckList = false
    private var rendered = false
    private var course: Course? = null
    private var courseLoaded = false

    /** Returns from the deck list to the progress screen */
    val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showDeckList(false)
        }

    /** The progress screen is shown instead of the deck list */
    val isShown: Boolean get() = attached && root.isVisible

    /** @param deckList the deck list pane, a child of a [LinearLayout] */
    fun attach(deckList: View) {
        val parent = deckList.parent as? LinearLayout ?: return
        this.deckList = deckList
        root = LayoutInflater.from(activity).inflate(R.layout.wordlearner_progress, parent, false)
        root.layoutParams = LinearLayout.LayoutParams(deckList.layoutParams as LinearLayout.LayoutParams)
        root.isVisible = false
        parent.addView(root, parent.indexOfChild(deckList) + 1)
        attached = true

        root.findViewById<View>(R.id.wl_progress_start).setOnClickListener { onStartStudying() }
        val help = root.findViewById<TextView>(R.id.wl_help_text)
        root.findViewById<View>(R.id.wl_help).setOnClickListener {
            TransitionManager.beginDelayedTransition(root.findViewById(R.id.wl_progress_content))
            help.isVisible = !help.isVisible
        }
        val bottom = root.findViewById<View>(R.id.wl_progress_bottom)
        val scroll = root.findViewById<NestedScrollView>(R.id.wl_progress_scroll)
        val bottomPadding = bottom.paddingBottom
        val scrollPadding = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bottom.updatePadding(bottom = bottomPadding + bars.bottom)
            scroll.updatePadding(bottom = scrollPadding + bars.bottom)
            insets
        }
        activity.addMenuProvider(menuProvider, activity)
        // imported on an earlier start: open with the progress screen, not with a glimpse of the deck list
        if (BundledDeck.wasImported(activity)) {
            available = true
            update()
        }
    }

    /** Recalculates the numbers: after the deck list was reloaded, for example when returning from the study screen */
    fun refresh() {
        if (!attached) return
        activity.lifecycleScope.launch {
            try {
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "progress not calculated")
            }
        }
    }

    @VisibleForTesting
    suspend fun reload() {
        // the course file is small: read with the collection, off the main thread
        val (course, progress) =
            withCol {
                if (!courseLoaded) {
                    this@ProgressScreen.course = Course.load(activity)
                    courseLoaded = true
                }
                this@ProgressScreen.course to if (decks.byName(WordDeck.ROOT) == null) null else CourseProgress.compute(this, course)
            }
        available = progress != null
        if (progress != null) render(ProgressText.of(progress, course, LocalDate.now()), progress)
        update()
    }

    fun showDeckList(show: Boolean) {
        wantsDeckList = show
        update()
    }

    private fun update() {
        val progress = available && !wantsDeckList
        if (root.isVisible != progress) {
            root.isVisible = progress
            deckList.isVisible = !progress
            activity.invalidateOptionsMenu()
        }
        backCallback.isEnabled = available && wantsDeckList
    }

    private fun render(
        text: ProgressText,
        progress: CourseProgress,
    ) {
        fun text(
            id: Int,
            value: String?,
        ) = root.findViewById<TextView>(id).apply {
            this.text = value
            isVisible = value != null
        }
        text(R.id.wl_hero_number, text.heroNumber)
        text(R.id.wl_hero_label, text.heroLabel)
        text(R.id.wl_hero_note, text.heroNote)
        root.findViewById<View>(R.id.wl_legend).isVisible = text.legend
        text(R.id.wl_remembered, text.remembered)
        text(R.id.wl_in_progress, text.inProgress)
        text(R.id.wl_ahead, text.ahead)
        root.findViewById<TextView>(R.id.wl_help_text).text = text.help

        root.findViewById<View>(R.id.wl_today).isVisible = text.today != null
        text(R.id.wl_current, text.current)
        val tiles = root.findViewById<GridLayout>(R.id.wl_tiles)
        tiles.removeAllViews()
        text.today?.forEach { tile ->
            val view = LayoutInflater.from(activity).inflate(R.layout.wordlearner_progress_tile, tiles, false)
            view.findViewById<TextView>(R.id.wl_tile_value).text = tile.value
            view.findViewById<TextView>(R.id.wl_tile_label).text = tile.label
            view.layoutParams =
                GridLayout.LayoutParams(view.layoutParams).apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            tiles.addView(view)
        }

        root.findViewById<View>(R.id.wl_next).isVisible = text.ahead7 != null
        val rows = root.findViewById<ViewGroup>(R.id.wl_rows)
        rows.removeAllViews()
        text.ahead7?.forEach { row ->
            val view = LayoutInflater.from(activity).inflate(R.layout.wordlearner_progress_row, rows, false)
            view.findViewById<TextView>(R.id.wl_row_label).text = row.label
            view.findViewById<TextView>(R.id.wl_row_value).text = row.value
            rows.addView(view)
        }
        text(R.id.wl_outlook, text.outlook)

        root.findViewById<SegmentBar>(R.id.wl_bar).set(
            total = progress.courseWords,
            remembered = progress.remembered,
            inProgress = progress.inProgress,
            animate = !rendered,
        )
        if (!rendered) {
            rendered = true
            root
                .findViewById<View>(R.id.wl_progress_content)
                .animate()
                .alpha(1f)
                .setDuration(220)
                .start()
        }
    }

    private val menuProvider =
        object : MenuProvider {
            override fun onCreateMenu(
                menu: Menu,
                menuInflater: MenuInflater,
            ) {
                menuInflater.inflate(R.menu.wordlearner_progress, menu)
                onPrepareMenu(menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.wordlearner_show_decks)?.isVisible = available && isShown
                menu.findItem(R.id.wordlearner_show_progress)?.isVisible = available && !isShown
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.wordlearner_show_decks -> {
                        showDeckList(true)
                        true
                    }
                    R.id.wordlearner_show_progress -> {
                        showDeckList(false)
                        true
                    }
                    else -> false
                }
        }
}
