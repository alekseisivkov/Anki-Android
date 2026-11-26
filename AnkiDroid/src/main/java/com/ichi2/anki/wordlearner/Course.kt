// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.wordlearner

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * Every word of the course, including the lessons which are not in the app yet.
 *
 * Written by the deck builder next to the deck (`course.json` in the app's assets).
 */
data class Course(
    val words: Int,
    val levels: List<Level>,
) {
    data class Level(
        val name: String,
        val words: Int,
        val lessons: List<Lesson>,
    )

    data class Lesson(
        val number: Int,
        val words: Int,
        val inApp: Int,
    )

    companion object {
        private const val FILE = "wordlearner/course.json"

        fun parse(json: String): Course {
            val root = JSONObject(json)
            val levels = root.getJSONArray("levels")
            return Course(
                words = root.getInt("words"),
                levels =
                    (0 until levels.length()).map { i ->
                        val level = levels.getJSONObject(i)
                        val lessons = level.getJSONArray("lessons")
                        Level(
                            name = level.getString("level"),
                            words = level.getInt("words"),
                            lessons =
                                (0 until lessons.length()).map { j ->
                                    val lesson = lessons.getJSONObject(j)
                                    Lesson(lesson.getInt("lesson"), lesson.getInt("words"), lesson.optInt("inApp"))
                                },
                        )
                    },
            )
        }

        /** `null` when the app was built without the private word data */
        fun load(context: Context): Course? =
            try {
                parse(
                    context.assets
                        .open(FILE)
                        .bufferedReader()
                        .use { it.readText() },
                )
            } catch (_: IOException) {
                null
            } catch (e: JSONException) {
                Timber.w(e, "course.json")
                null
            }
    }
}
