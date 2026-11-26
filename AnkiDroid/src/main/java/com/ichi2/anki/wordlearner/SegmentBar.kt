// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.ichi2.anki.R

/**
 * The whole course as one bar: remembered words, words in progress, and the track for what is ahead.
 *
 * A segment which is not empty stays visible however small it is: 12 of 2391 words are still a mark.
 */
class SegmentBar
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val track = paint(R.color.wl_progress_track)
        private val remembered = paint(R.color.wl_ink)
        private val inProgress = paint(R.color.wl_gold)
        private val clip = Path()
        private val rect = RectF()
        private val minSegment = 4 * resources.displayMetrics.density
        private val gap = 2 * resources.displayMetrics.density

        private var total = 0
        private var rememberedCount = 0
        private var inProgressCount = 0
        private var shown = 1f
        private var animator: ValueAnimator? = null

        private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ContextCompat.getColor(context, color) }

        /** @param animate the segments grow from the start of the bar */
        fun set(
            total: Int,
            remembered: Int,
            inProgress: Int,
            animate: Boolean,
        ) {
            val changed = total != this.total || remembered != rememberedCount || inProgress != inProgressCount
            this.total = total
            rememberedCount = remembered
            inProgressCount = inProgress
            contentDescription = "Запомнено $remembered, в процессе $inProgress, всего $total"
            if (!changed) return
            animator?.cancel()
            if (animate && remembered + inProgress > 0) {
                animator =
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 700
                        interpolator = DecelerateInterpolator(2.5f)
                        addUpdateListener {
                            shown = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
            } else {
                shown = 1f
                invalidate()
            }
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            clip.reset()
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            clip.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, w, h, track)
            if (total > 0) {
                val rememberedWidth = segment(rememberedCount, w)
                val inProgressWidth = segment(inProgressCount, w)
                var x = 0f
                if (rememberedWidth > 0) {
                    canvas.drawRect(x, 0f, x + rememberedWidth * shown, h, remembered)
                    x += rememberedWidth * shown + gap
                }
                if (inProgressWidth > 0) {
                    canvas.drawRect(x, 0f, x + inProgressWidth * shown, h, inProgress)
                }
            }
            canvas.restore()
        }

        private fun segment(
            count: Int,
            width: Float,
        ): Float = if (count <= 0) 0f else maxOf(minSegment, width * count / total)
    }
