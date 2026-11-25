// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import java.text.Normalizer
import java.util.Locale

/**
 * Forgiving comparison of a typed German word.
 *
 * Accepted: a missing or wrong article, any letter case, `ae/oe/ue/ss` for `ä/ö/ü/ß`, and one typo in
 * words of [MIN_LENGTH_FOR_TYPO] or more letters. A missing umlaut is a mistake: `schon` is not `schön`.
 */
object AnswerChecker {
    enum class Verdict {
        CORRECT,

        /** Accepted with one typo */
        TYPO,
        WRONG,
        ;

        val accepted get() = this != WRONG
    }

    /**
     * @param expected the accepted form which is closest to the typed text
     * @param typed the typed text, without a leading article and accepted differences in case and spelling
     */
    data class Result(
        val verdict: Verdict,
        val expected: String,
        val typed: String,
    )

    const val MIN_LENGTH_FOR_TYPO = 6

    private val ARTICLE = Regex("^(der|die|das|ein|eine)(\\s*/\\s*(der|die|das))?\\s+(?=\\S)", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val DIGRAPHS = listOf("ae" to "ä", "oe" to "ö", "ue" to "ü", "ss" to "ß")

    /** @param accepted forms separated by `|`, the first one is the canonical form */
    fun check(
        accepted: String,
        typed: String,
    ): Result {
        val forms = accepted.split('|').map { it.clean() }.filter { it.isNotEmpty() }
        val raw = typed.clean()
        // the article is stripped only if this is not a part of the answer itself ("die meisten")
        val candidates = listOf(raw, raw.replace(ARTICLE, "")).distinct()
        val results = forms.flatMap { form -> candidates.map { Result(verdict(form, it), form, it) } }
        val best = results.minByOrNull { it.verdict.ordinal * 1000 + distance(it.expected.fold(), it.typed.fold()) }
        return best?.copy(typed = align(best.expected, best.typed)) ?: Result(Verdict.WRONG, accepted, raw)
    }

    /** Writes [typed] like [expected] where they differ only in case, or in the spelling of umlauts */
    private fun align(
        expected: String,
        typed: String,
    ): String {
        val variants =
            DIGRAPHS.fold(listOf(typed.lowercase(Locale.GERMAN))) { forms, (digraph, letter) ->
                forms + forms.map { it.replace(digraph, letter) }
            }
        val best = variants.minBy { distance(expected.lowercase(Locale.GERMAN), it) }
        return if (expected.first().isUpperCase()) best.replaceFirstChar { it.titlecase(Locale.GERMAN) } else best
    }

    private fun verdict(
        expected: String,
        typed: String,
    ): Verdict {
        val e = expected.fold()
        val t = typed.fold()
        if (e == t) return Verdict.CORRECT
        // umlauts were dropped or added, the rest is identical
        if (expected.dropUmlauts() == typed.dropUmlauts()) return Verdict.WRONG
        val oneTypo = expected.length >= MIN_LENGTH_FOR_TYPO && distance(e, t) == 1
        return if (oneTypo && !onlyUmlautDiffers(e, t)) Verdict.TYPO else Verdict.WRONG
    }

    /** `schluessel` / `schlussel`: the only edit is the `e` which marks an umlaut */
    private fun onlyUmlautDiffers(
        a: String,
        b: String,
    ) = a.replace("ae", "a").replace("oe", "o").replace("ue", "u") ==
        b.replace("ae", "a").replace("oe", "o").replace("ue", "u")

    private fun String.clean() = Normalizer.normalize(this, Normalizer.Form.NFC).trim().replace(SPACES, " ")

    /** Case-insensitive form with umlauts spelled out */
    private fun String.fold() =
        lowercase(Locale.GERMAN)
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .trimEnd('.', '!', '?')

    private fun String.dropUmlauts() =
        lowercase(Locale.GERMAN)
            .replace('ä', 'a')
            .replace('ö', 'o')
            .replace('ü', 'u')
            .replace("ß", "ss")

    /** Optimal string alignment: insertions, deletions, substitutions and swaps of neighbours cost 1 */
    internal fun distance(
        a: String,
        b: String,
    ): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }
}
