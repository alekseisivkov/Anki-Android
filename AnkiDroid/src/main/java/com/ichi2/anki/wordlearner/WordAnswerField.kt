// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import com.ichi2.anki.R

/** The field for the typed word, see the style `Woerter_AnswerField` */
object WordAnswerField {
    /**
     * The typed word is shown in monospace, letter by letter like the comparison after it.
     * The hint is a sentence, so it is set smaller in the normal sans.
     */
    fun hint(context: Context): CharSequence =
        SpannableString(context.getString(R.string.wordlearner_answer_hint)).apply {
            setSpan(TypefaceSpan("sans-serif"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.8f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
}
