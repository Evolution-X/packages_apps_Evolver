/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** A Google Fonts family with the [variants] of it that are kept on the device. */
internal class SavedFont(val font: OnlineFont, val variants: Set<String>)

/**
 * Styles of Google Fonts that are kept until the person removes them, so they can be previewed
 * and applied without a network. Unlike the download cache these live in app data and are never
 * trimmed. The family details are saved with them, so the list shows even when the online one
 * can't be loaded.
 */
internal class SavedFontStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR)
    private val index = File(dir, INDEX)

    /** Every saved family, the one saved last first. Entries whose file is gone are left out. */
    @Synchronized
    fun all(): List<SavedFont> {
        if (!index.isFile) return emptyList()
        val parsed = runCatching { parse(index.readText()) }
            .onFailure { Log.w(TAG, "Saved font list unreadable", it) }
            .getOrNull()
            ?: return emptyList()
        return parsed.mapNotNull { saved ->
            val present = saved.variants.filterTo(HashSet()) { fileOf(saved.font.family, it).isUsable() }
            if (present.isEmpty()) null else SavedFont(saved.font, present)
        }
    }

    /** The file of a saved style, or null when it isn't saved. */
    @Synchronized
    fun file(family: String, variant: String): File? {
        val saved = all().firstOrNull { it.font.family == family } ?: return null
        return if (variant in saved.variants) fileOf(family, variant) else null
    }

    /** The file of any saved style of [family], or null when none is saved. */
    @Synchronized
    fun anyFile(family: String): File? {
        val saved = all().firstOrNull { it.font.family == family } ?: return null
        return saved.variants.firstOrNull()?.let { fileOf(family, it) }
    }

    /** Copies [source] in as [variant] of [font]. Returns the list as it is now. */
    @Synchronized
    fun save(font: OnlineFont, variant: String, source: File): List<SavedFont> {
        require(variant in font.variants) { "${font.family} has no style $variant" }
        dir.mkdirs()
        val target = fileOf(font.family, variant)
        // Copied beside the target and moved in, so a reader never sees half a file.
        val temp = File(dir, "${target.name}.part")
        try {
            source.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) throw IOException("Can't move ${target.name}")
        } finally {
            temp.delete()
        }
        val list = all().toMutableList()
        val old = list.firstOrNull { it.font.family == font.family }
        list.remove(old)
        list.add(0, SavedFont(font, old?.variants.orEmpty() + variant))
        write(list)
        return list
    }

    /** Deletes a saved style. Returns the list as it is now. */
    @Synchronized
    fun remove(family: String, variant: String): List<SavedFont> {
        fileOf(family, variant).delete()
        // all() leaves out what has no file, so this drops the entry too.
        return all().also(::write)
    }

    private fun fileOf(family: String, variant: String) =
        File(dir, "${onlineFontFileName(family)}-$variant.ttf")

    private fun File.isUsable() = isFile && length() > 0L

    private fun write(list: List<SavedFont>) {
        val fonts = JSONArray()
        list.forEach { saved ->
            fonts.put(
                JSONObject()
                    .put("family", saved.font.family)
                    .put("category", saved.font.category)
                    .put("popularity", saved.font.popularity)
                    .put("subsets", JSONArray(saved.font.subsets))
                    .put("variants", JSONArray(saved.font.variants))
                    .put("saved", JSONArray(saved.variants.toList()))
            )
        }
        dir.mkdirs()
        val temp = File(dir, "$INDEX.tmp")
        temp.writeText(JSONObject().put("fonts", fonts).toString())
        if (!temp.renameTo(index)) temp.delete()
    }

    private fun parse(raw: String): List<SavedFont> {
        val fonts = JSONObject(raw).getJSONArray("fonts")
        val seen = HashSet<String>()
        return (0 until fonts.length()).mapNotNull { i ->
            runCatching { parseEntry(fonts.getJSONObject(i)) }
                .onFailure { Log.w(TAG, "Skipping saved font $i", it) }
                .getOrNull()
                ?.takeIf { seen.add(it.font.family) }
        }
    }

    private fun parseEntry(o: JSONObject): SavedFont {
        val family = o.getString("family")
        require(family.isNotBlank()) { "Blank family" }
        val variants = strings(o.getJSONArray("variants")).filter { VARIANT.matches(it) }
        val saved = strings(o.getJSONArray("saved")).filter { it in variants }.toSet()
        require(saved.isNotEmpty()) { "Nothing saved for $family" }
        val font = OnlineFont(
            family = family,
            category = o.optString("category"),
            variants = variants.sortedWith(compareBy<String> { it.weight() }.thenBy { it.isItalic() }),
            popularity = o.optInt("popularity", Int.MAX_VALUE),
            subsets = strings(o.optJSONArray("subsets") ?: JSONArray()).filter { SUBSET.matches(it) },
        )
        return SavedFont(font, saved)
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private companion object {
        const val TAG = "SavedFontStore"
        const val DIR = "online_fonts_saved"
        const val INDEX = "saved.json"
        val VARIANT = Regex("[1-9]00i?")
        val SUBSET = Regex("[a-z0-9-]+")
    }
}
