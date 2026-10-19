package com.andrerinas.openheadunit.utils

import android.content.Context
import android.media.AudioManager
import android.net.Uri

/**
 * Finds what one of the car's own volume sliders really changes. BYD adds vendor stream types
 * (the Navi slider is stream 15 on DiLink 5) and keeps other sliders in its settings tables, so a
 * slider that no test tone answers may still move a stream above 20 or a settings value. This
 * reads every Android stream volume and every settings value with "vol" in its name; moving the
 * slider between two reads shows what it drives.
 */
class CarSliderFinder(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The streams this head unit accepts, found once: Android rejects stream numbers it lacks. */
    val streams: List<Int> = (0 until MAX_STREAM).filter { stream ->
        runCatching { audioManager.getStreamMaxVolume(stream) > 0 }.getOrDefault(false)
    }

    /** Stream volumes and volume-like settings, keyed as "stream 15" or "system/<name>". */
    fun snapshot(): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (stream in streams) {
            runCatching { audioManager.getStreamVolume(stream) }.getOrNull()?.let {
                values["stream $stream"] = "$it/${runCatching { audioManager.getStreamMaxVolume(stream) }.getOrDefault(0)}"
            }
        }
        for (table in SETTINGS_TABLES) readVolumeSettings(table, values)
        return values
    }

    private fun readVolumeSettings(table: String, into: MutableMap<String, String>) {
        runCatching {
            appContext.contentResolver.query(
                Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (!name.contains("vol", ignoreCase = true)) continue
                    into["$table/$name"] = cursor.getString(1) ?: ""
                }
            }
        }
    }

    /**
     * Which slider each stream number and usage really plays on here, as Android's audio
     * configuration maps them: "17→17" means stream 17 reaches its own slider, "17→3" that it
     * plays as media. Shown under the finder and logged, so a report shows the whole mapping.
     */
    fun routes(): String {
        val groups = com.andrerinas.openheadunit.aap.CarVolumeGroups
        val legacy = streams.filter { it > 0 }.joinToString(" ") { "$it→${groups.volumeStreamOfLegacy(it) ?: "?"}" }
        val usages = groups.FALLBACK_USAGES.joinToString(" ") { "u$it→${groups.volumeStreamOfUsage(it) ?: "?"}" }
        return "Streams: $legacy\nUsages: $usages"
    }

    companion object {
        /** Android's own streams end at 11; BYD's vendor streams sit above that. */
        const val MAX_STREAM = 64
        private val SETTINGS_TABLES = listOf("system", "global", "secure")

        /** What changed between two snapshots, as "key: old → new", in key order. */
        fun changes(before: Map<String, String>, after: Map<String, String>): List<String> =
            (before.keys + after.keys).distinct().mapNotNull { key ->
                val old = before[key]
                val new = after[key]
                if (old == new) null else "$key: ${old ?: "–"} → ${new ?: "–"}"
            }
    }
}
