package com.xauaitrader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DiaryStore(context: Context) {
    data class Entry(val date: String, val pnl: Double, val note: String)
    private val prefs = context.getSharedPreferences("diary", Context.MODE_PRIVATE)
    private val key = "entries"
    fun all(): List<Entry> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        List(a.length()) { i -> val o = a.getJSONObject(i); Entry(o.getString("date"), o.getDouble("pnl"), o.optString("note")) }
    }.getOrDefault(emptyList())
    fun add(entry: Entry) {
        val list = all().filterNot { it.date == entry.date }.toMutableList(); list += entry
        val a = JSONArray(); list.sortedBy { it.date }.forEach { o -> a.put(JSONObject().apply { put("date", o.date); put("pnl", o.pnl); put("note", o.note) }) }
        prefs.edit().putString(key, a.toString()).apply()
    }
}
