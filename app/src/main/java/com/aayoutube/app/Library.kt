package com.aayoutube.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently played and favourite videos, plus where playback stopped. Lives in the media
 * process only: Android Auto reads it from memory, so browsing never waits for disk or network.
 */
object Library {
    class Item(val id: String, val title: String, val artist: String)
    class Last(val id: String, val posSec: Long, val title: String, val artist: String)

    private const val MAX_RECENTS = 30
    private lateinit var prefs: SharedPreferences

    @Volatile private var recents: List<Item> = emptyList()
    @Volatile private var favs: List<Item> = emptyList()

    @Volatile var last: Last? = null
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("aay_library", Context.MODE_PRIVATE)
        recents = read("recents")
        favs = read("favs")
        last = prefs.getString("lastId", null)?.takeIf { it.isNotEmpty() }?.let {
            Last(it, prefs.getLong("lastPos", 0L), prefs.getString("lastTitle", "").orEmpty(), prefs.getString("lastArtist", "").orEmpty())
        }
    }

    fun recents(): List<Item> = recents
    fun favourites(): List<Item> = favs
    fun isFavourite(id: String) = favs.any { it.id == id }

    fun find(id: String): Item? = favs.firstOrNull { it.id == id } ?: recents.firstOrNull { it.id == id }

    /** A video started playing: it goes to the top of Recently played. */
    fun noteStarted(s: Snap) {
        if (s.id.isEmpty() || s.title.isEmpty()) return
        val item = Item(s.id, s.title, s.artist)
        recents = (listOf(item) + recents.filterNot { it.id == s.id }).take(MAX_RECENTS)
        write("recents", recents)
    }

    /** Heart button: adds or removes the playing video. Returns true if it is now a favourite. */
    fun toggleFavourite(s: Snap): Boolean {
        if (s.id.isEmpty()) return false
        val now = !isFavourite(s.id)
        favs = if (now) listOf(Item(s.id, s.title.ifEmpty { "YouTube video" }, s.artist)) + favs else favs.filterNot { it.id == s.id }
        write("favs", favs)
        return now
    }

    /** Remembers the position so "play" after the web process died resumes where it was. */
    fun saveLast(s: Snap) {
        if (s.id.isEmpty()) return
        last = Last(s.id, s.posMs / 1000, s.title, s.artist)
        prefs.edit()
            .putString("lastId", s.id)
            .putLong("lastPos", s.posMs / 1000)
            .putString("lastTitle", s.title)
            .putString("lastArtist", s.artist)
            .apply()
    }

    private fun read(key: String): List<Item> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.getString("id"), o.optString("title"), o.optString("artist"))
        }
    }.getOrDefault(emptyList())

    private fun write(key: String, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
