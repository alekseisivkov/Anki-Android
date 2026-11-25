// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import com.ichi2.anki.wordlearner.AnswerChecker.Verdict
import com.ichi2.anki.wordlearner.AnswerChecker.Verdict.CORRECT
import com.ichi2.anki.wordlearner.AnswerChecker.Verdict.TYPO
import com.ichi2.anki.wordlearner.AnswerChecker.Verdict.WRONG
import org.junit.Test
import kotlin.test.assertEquals

class AnswerCheckerTest {
    private fun assertVerdict(
        verdict: Verdict,
        accepted: String,
        vararg typed: String,
    ) = typed.forEach { assertEquals(verdict, AnswerChecker.check(accepted, it).verdict, "'$it' for '$accepted'") }

    @Test
    fun `article is optional and may be wrong`() =
        assertVerdict(CORRECT, "Schlüssel", "Schlüssel", "der Schlüssel", "die Schlüssel", "Das  Schlüssel ", "ein Schlüssel")

    @Test
    fun `case and spelled out umlauts are accepted`() =
        assertVerdict(CORRECT, "Schlüssel", "schlüssel", "schluessel", "SCHLÜSSEL", "der schluessel")

    @Test
    fun `sharp s may be spelled out`() = assertVerdict(CORRECT, "außerhalb", "ausserhalb", "außerhalb")

    @Test
    fun `missing or added umlaut is a mistake`() {
        assertVerdict(WRONG, "Schlüssel", "schlussel", "der Schlussel")
        assertVerdict(WRONG, "schön", "schon")
        assertVerdict(WRONG, "schon", "schön", "schoen")
        assertVerdict(WRONG, "enttäuscht", "enttauscht")
    }

    @Test
    fun `one typo is accepted in long words only`() {
        assertVerdict(TYPO, "Schlüssel", "Schlüsel", "Schlüsssel", "Schlüssle", "Schlxssel".replace("x", "ü").dropLast(1))
        assertVerdict(WRONG, "Schlüssel", "Schüsle", "Schlusel")
        assertVerdict(WRONG, "Wort", "Wert", "Wor")
    }

    @Test
    fun `any of the accepted forms is enough`() {
        assertVerdict(CORRECT, "Architekt|Architektin", "Architekt", "die Architektin")
        assertVerdict(CORRECT, "jeder|jedes|jede", "jede", "jedes")
        assertVerdict(CORRECT, "freuen|sich freuen", "freuen", "sich freuen")
    }

    @Test
    fun `an article which belongs to the answer is kept`() = assertVerdict(CORRECT, "die meisten", "die meisten")

    @Test
    fun `decomposed umlauts are accepted`() = assertVerdict(CORRECT, "üben", "üben")

    @Test
    fun `empty answer is wrong`() = assertVerdict(WRONG, "üben", "", "  ", "der")

    @Test
    fun `punctuation at the end is ignored`() = assertVerdict(CORRECT, "Alles Gute!", "alles gute")

    @Test
    fun `the closest form is reported`() =
        assertEquals("Architektin", AnswerChecker.check("Architekt|Architektin", "Architektinn").expected)

    @Test
    fun `accepted differences are not reported as mistakes`() {
        assertEquals("Abteilungsleitar", AnswerChecker.check("Abteilungsleiter", "abteilungsleitar").typed)
        assertEquals("Schlüsel", AnswerChecker.check("Schlüssel", "schluesel").typed)
        assertEquals("üben", AnswerChecker.check("üben", "ueben").typed)
        assertEquals("Schlussel", AnswerChecker.check("Schlüssel", "schlussel").typed)
        assertEquals("Abenteuer", AnswerChecker.check("Abenteuer", "abenteuer").typed)
    }
}
