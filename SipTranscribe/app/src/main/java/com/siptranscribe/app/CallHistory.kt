package com.siptranscribe.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object CallHistory {

    private const val PREFS = "call_history"
    private const val KEY = "records"
    private const val MAX = 30

    fun add(context: Context, record: CallRecord) {
        val list = load(context).toMutableList()
        list.add(0, record)
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        save(context, list)
    }

    fun load(context: Context): List<CallRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, records: List<CallRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(r: CallRecord) = JSONObject().apply {
        put("id", r.id)
        put("direction", r.direction.name)
        put("callerName", r.callerName)
        put("callerNumber", r.callerNumber)
        put("startTime", r.startTime)
        put("durationSeconds", r.durationSeconds)
        put("answered", r.answered)
    }

    private fun fromJson(o: JSONObject) = CallRecord(
        id = o.getLong("id"),
        direction = CallRecord.Direction.valueOf(o.getString("direction")),
        callerName = o.getString("callerName"),
        callerNumber = o.getString("callerNumber"),
        startTime = o.getLong("startTime"),
        durationSeconds = o.getInt("durationSeconds"),
        answered = o.getBoolean("answered")
    )
}
