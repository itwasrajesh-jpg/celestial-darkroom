package com.celestial.latent.develop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Occasions (103b): birthdays, anniversaries and festivals kept for the calendar, each saved once and back every year
 * on its day. An occasion keeps a name, its day and month, its kind and the photos picked for it (the developed
 * prints' addresses; the first is its face). A calendar month shows its occasions: each one's photo in its date, a
 * row of small portraits, and on the Hearts and Portraits pages the occasions themselves are the page's heroes.
 * Kept in the preferences "latent_occasions" as one JSON list.
 */
data class Occasion(val id: Long, val name: String, val month: Int, val day: Int, val kind: String, val photos: List<String>) {
    companion object { val KINDS = listOf("birthday", "anniversary", "festival") }
}

object Occasions {
    private const val PREFS = "latent_occasions"
    private const val KEY = "list"

    fun all(context: Context): List<Occasion> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val ph = o.optJSONArray("photos") ?: JSONArray()
                Occasion(o.getLong("id"), o.optString("name"), o.getInt("month"), o.getInt("day"), o.optString("kind", "birthday"),
                    (0 until ph.length()).map { ph.getString(it) })
            }
        }.onFailure { android.util.Log.e("Latent", "occasions: could not read", it) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, list: List<Occasion>) {
        val a = JSONArray()
        for (o in list) a.put(JSONObject().put("id", o.id).put("name", o.name).put("month", o.month).put("day", o.day).put("kind", o.kind)
            .put("photos", JSONArray().apply { o.photos.forEach { put(it) } }))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Adds [o], or replaces the one with its id. */
    fun put(context: Context, o: Occasion) = write(context, all(context).filter { it.id != o.id } + o)

    fun remove(context: Context, id: Long) = write(context, all(context).filter { it.id != id })

    /** [month]'s occasions in [year], by day; the 29th of February falls on the 28th in other years. */
    fun inMonth(list: List<Occasion>, year: Int, month: Int): List<Occasion> {
        val last = java.time.YearMonth.of(year, month).lengthOfMonth()
        return list.filter { it.month == month }.map { if (it.day > last) it.copy(day = last) else it }.sortedWith(compareBy({ it.day }, { it.id }))
    }
}
