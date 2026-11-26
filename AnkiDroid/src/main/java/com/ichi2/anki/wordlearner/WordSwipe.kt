// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.RelativeLayout
import androidx.core.graphics.ColorUtils
import com.ichi2.anki.R
import com.ichi2.anki.cardviewer.ViewerCommand
import com.ichi2.anki.wordlearner.WordSwipeLogic.Label
import com.ichi2.anki.wordlearner.WordSwipeLogic.Outcome
import com.ichi2.anki.wordlearner.WordSwipeLogic.Outcomes
import com.ichi2.anki.wordlearner.WordSwipeLogic.Tone
import kotlin.math.sign

/**
 * The card follows a horizontal drag, a preview shows what releasing it will do, and a committed swipe
 * sends the card off the screen. Only transforms of [card] (a hardware layer) and the drawing of the
 * preview change per frame: the WebView neither lays out nor renders again during a drag.
 *
 * @param card the frame of the card's WebView
 * @param cancelTouch hands a cancel event to the other receivers of the touch once the drag is a swipe
 */
class WordSwipe(
    private val card: View,
    private val host: Host,
    private val cancelTouch: (MotionEvent) -> Unit,
) {
    interface Host {
        /** `null` if the current card cannot be swiped now */
        fun swipeOutcomes(): Outcomes?

        fun perform(outcome: Outcome)
    }

    private val context: Context = card.context
    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val flingVelocity = FLING_DP_PER_S * density
    private val preview = Preview(context)

    private var downX = 0f
    private var downY = 0f
    private var outcomes: Outcomes? = null
    private var axis: WordSwipeLogic.Axis? = null
    private var armed = false
    private var velocity: VelocityTracker? = null
    private var entrancePending = false

    init {
        // the preview covers the card exactly: same layout rules, drawn above it
        val parent = card.parent as ViewGroup
        val params =
            when (val p = card.layoutParams) {
                is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(p)
                is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(p)
                else -> ViewGroup.LayoutParams(p)
            }
        parent.addView(preview, parent.indexOfChild(card) + 1, params)
    }

    private val animated get() = animatorsEnabled()

    /** @return whether the event belongs to a swipe; the first events of every touch are never taken */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // the previous card is still flying out
                if (entrancePending) return false
                downX = event.rawX
                downY = event.rawY
                axis = null
                armed = false
                outcomes = host.swipeOutcomes()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val outcomes = outcomes ?: return false
                velocity?.addMovement(event)
                val dx = event.rawX - downX
                if (axis == null) {
                    axis = WordSwipeLogic.axis(dx, event.rawY - downY, slop) ?: return false
                    if (axis != WordSwipeLogic.Axis.HORIZONTAL) return false
                    card.animate().cancel()
                    cancelTouch(MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                }
                if (axis != WordSwipeLogic.Axis.HORIZONTAL) return false
                drag(dx, outcomes.toward(dx))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val outcomes = outcomes
                val swiping = axis == WordSwipeLogic.Axis.HORIZONTAL && outcomes != null
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                this.outcomes = null
                axis = null
                if (!swiping) return false
                val dx = event.rawX - downX
                val commits =
                    event.actionMasked == MotionEvent.ACTION_UP &&
                        WordSwipeLogic.commits(dx, vx, card.width, flingVelocity)
                if (commits) commit(dx, outcomes.toward(dx)) else springBack()
                return true
            }
        }
        return outcomes != null && axis == WordSwipeLogic.Axis.HORIZONTAL
    }

    private fun drag(
        dx: Float,
        outcome: Outcome,
    ) {
        val width = card.width
        if (animated) {
            val moved = WordSwipeLogic.resisted(dx, width)
            card.pivotX = width / 2f
            card.pivotY = card.height * 1.5f
            card.translationX = moved
            card.rotation = moved / width * MAX_TILT
        }
        val nowArmed = WordSwipeLogic.isArmed(dx, width)
        if (nowArmed && !armed) {
            tick()
            preview.bump()
        }
        armed = nowArmed
        preview.show(outcome, sign(dx), WordSwipeLogic.progress(dx, width), armed)
    }

    private fun commit(
        dx: Float,
        outcome: Outcome,
    ) {
        if (outcome.command == ViewerCommand.SHOW_ANSWER) {
            // the card turns over in place
            preview.fadeOut()
            springBack()
            host.perform(outcome)
            return
        }
        preview.show(outcome, sign(dx), 1f, true)
        if (!animated) {
            preview.fadeOut()
            host.perform(outcome)
            return
        }
        val direction = sign(dx)
        card
            .animate()
            .translationX(direction * card.width * 1.15f)
            .rotation(direction * MAX_TILT * 2)
            .alpha(0f)
            .setDuration(FLY_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        card.animate().setListener(null)
                        card.translationX = 0f
                        card.rotation = 0f
                        entrancePending = true
                        preview.fadeOut()
                        host.perform(outcome)
                        // the next card did not come (undo, end of the session): do not leave it hidden
                        card.postDelayed({ if (entrancePending) onCardShown() }, ENTRANCE_FALLBACK_MS)
                    }
                },
            ).start()
    }

    private fun springBack() {
        preview.fadeOut()
        if (!animated) {
            reset()
            return
        }
        card
            .animate()
            .translationX(0f)
            .rotation(0f)
            .alpha(1f)
            .setDuration(SPRING_MS)
            .setInterpolator(OvershootInterpolator(1.1f))
            .setListener(null)
            .start()
    }

    /** A new card or side is shown: a committed swipe lets it come in */
    fun onCardShown() {
        if (!entrancePending) return
        entrancePending = false
        if (!animated) {
            reset()
            return
        }
        card.translationY = ENTRANCE_DP * density
        card
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTRANCE_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .setListener(null)
            .start()
    }

    /** Puts the card back in place, e.g. after an undo */
    fun reset() {
        card.animate().cancel()
        card.translationX = 0f
        card.translationY = 0f
        card.rotation = 0f
        card.alpha = 1f
        preview.fadeOut()
    }

    private fun tick() {
        val feedback =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        card.performHapticFeedback(feedback)
    }

    /** The tint from the side the card goes to, and a label of what releasing does */
    @SuppressLint("ViewConstructor")
    private class Preview(
        context: Context,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tint = Paint()
        private val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                color = context.getColor(android.R.color.white)
            }
        private val rect = RectF()
        private var outcome: Outcome? = null
        private var side = 1f
        private var progress = 0f
        private var armed = false
        private var scale = 1f
        private var bumper: ValueAnimator? = null
        private var shaderWidth = -1

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun show(
            outcome: Outcome,
            side: Float,
            progress: Float,
            armed: Boolean,
        ) {
            if (outcome != this.outcome || side != this.side || shaderWidth != width) {
                val color = context.getColor(toneColor(outcome.tone))
                val edge = if (side > 0) width.toFloat() else 0f
                val transparent = ColorUtils.setAlphaComponent(color, 0)
                tint.shader = LinearGradient(edge, 0f, edge - side * width * 0.6f, 0f, color, transparent, Shader.TileMode.CLAMP)
                shaderWidth = width
            }
            this.outcome = outcome
            this.side = side
            this.progress = progress
            this.armed = armed
            animate().cancel()
            alpha = 1f
            invalidate()
        }

        fun bump() {
            bumper?.cancel()
            if (!animatorsEnabled()) return
            bumper =
                ValueAnimator.ofFloat(1f, 1.12f, 1f).apply {
                    duration = 180
                    addUpdateListener {
                        scale = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }

        fun fadeOut() {
            if (outcome == null) return
            animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    outcome = null
                    invalidate()
                }.start()
        }

        override fun onDraw(canvas: Canvas) {
            val outcome = outcome ?: return
            val color = context.getColor(toneColor(outcome.tone))
            // tint: grows with the drag, stronger when armed
            tint.alpha = (255 * (if (armed) 0.16f else 0.10f * progress)).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)

            // label: faint while it is only a preview, solid when releasing will do it
            val label = context.getString(labelText(outcome.label))
            val padH = 18 * density
            val textWidth = text.measureText(label)
            val pillWidth = textWidth + 2 * padH
            val pillHeight = 44 * density
            val margin = 20 * density
            val left = if (side > 0) width - margin - pillWidth else margin
            // low on the card, near the thumb and clear of the word, above the one-time hint
            val top = height * 0.77f - pillHeight / 2
            rect.set(left, top, left + pillWidth, top + pillHeight)
            canvas.save()
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
            val pillAlpha = if (armed) 1f else 0.25f + 0.45f * progress
            pill.color = ColorUtils.setAlphaComponent(color, (255 * pillAlpha).toInt())
            canvas.drawRoundRect(rect, pillHeight / 2, pillHeight / 2, pill)
            text.alpha = (255 * (if (armed) 1f else 0.6f + 0.4f * progress)).toInt()
            val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, rect.left + padH, baseline, text)
            canvas.restore()
        }

        private fun toneColor(tone: Tone) =
            when (tone) {
                Tone.GOOD -> R.color.wl_swipe_good
                Tone.AGAIN -> R.color.wl_swipe_again
                Tone.NEUTRAL -> R.color.wl_ink
            }

        private fun labelText(label: Label) =
            when (label) {
                Label.SHOW_ANSWER -> R.string.wordlearner_swipe_show_answer
                Label.REMEMBERED -> R.string.wordlearner_swipe_remembered
                Label.FORGOTTEN -> R.string.wordlearner_swipe_forgotten
                Label.NEXT_CORRECT -> R.string.wordlearner_swipe_next_correct
                Label.NEXT_WRONG -> R.string.wordlearner_swipe_next_wrong
            }
    }

    private companion object {
        fun animatorsEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

        const val MAX_TILT = 4f
        const val FLING_DP_PER_S = 1000f
        const val FLY_OUT_MS = 130L
        const val ENTRANCE_MS = 120L
        const val ENTRANCE_DP = 10f
        const val SPRING_MS = 220L
        const val ENTRANCE_FALLBACK_MS = 700L
    }
}
