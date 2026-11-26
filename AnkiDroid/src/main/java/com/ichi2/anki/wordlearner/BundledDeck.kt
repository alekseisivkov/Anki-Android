// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.content.Context
import androidx.core.content.edit
import anki.import_export.ImportAnkiPackageOptions
import anki.import_export.ImportAnkiPackageUpdateCondition
import anki.import_export.ImportResponse
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.observability.undoableOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Imports the deck which is shipped in the app's assets, once per deck version.
 *
 * Notes have stable GUIDs: importing a newer version corrects texts and pictures and keeps the progress.
 */
object BundledDeck {
    private const val DIR = "wordlearner"
    private const val DECK = "deck.apkg"
    private const val VERSION = "version.txt"
    private const val PREF_VERSION = "wordlearner_deck_version"

    /** Whether a version of the deck was imported completely before */
    fun wasImported(context: Context): Boolean = context.sharedPrefs().getString(PREF_VERSION, null) != null

    /** @return whether the deck was imported */
    suspend fun importIfNeeded(context: Context): Boolean {
        val version = bundledVersion(context) ?: return false
        val prefs = context.sharedPrefs()
        val installed = prefs.getString(PREF_VERSION, null)
        if (installed == version && withCol { decks.byName(WordDeck.ROOT) } != null) return false
        Timber.i("importing bundled deck %s (was %s)", version, installed)
        val file =
            withContext(Dispatchers.IO) {
                File(context.cacheDir, DECK).also { target ->
                    context.assets.open("$DIR/$DECK").use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        val complete =
            try {
                var skipped = 0
                undoableOp {
                    val response = import(this, file)
                    skipped = response.log.conflictingCount
                    response.changes
                }
                skipped == 0
            } finally {
                file.delete()
            }
        if (!complete) {
            // the version is not saved: the next start tries again
            Timber.e("bundled deck %s was not imported completely", version)
            return true
        }
        prefs.edit { putString(PREF_VERSION, version) }
        return true
    }

    private fun import(
        col: Collection,
        file: File,
    ): ImportResponse {
        val isFirstImport = col.decks.byName(WordDeck.ROOT) == null
        val options =
            ImportAnkiPackageOptions
                .newBuilder()
                // a newer deck may have additional fields
                .setMergeNotetypes(true)
                .setUpdateNotes(ImportAnkiPackageUpdateCondition.IMPORT_ANKI_PACKAGE_UPDATE_CONDITION_ALWAYS)
                .setUpdateNotetypes(ImportAnkiPackageUpdateCondition.IMPORT_ANKI_PACKAGE_UPDATE_CONDITION_ALWAYS)
                .setWithScheduling(false)
                // later imports must not reset options which were changed by the user
                .setWithDeckConfigs(isFirstImport)
                .build()
        val response = col.importAnkiPackage(file.path, options)
        if (isFirstImport) {
            col.config.set("fsrs", true)
            col.decks.byName(WordDeck.ROOT)?.let { col.decks.select(it.id) }
        }
        return response
    }

    private fun bundledVersion(context: Context): String? =
        try {
            context.assets
                .open("$DIR/$VERSION")
                .bufferedReader()
                .use { it.readText().trim() }
        } catch (_: java.io.IOException) {
            null // built without the private word data
        }
}
