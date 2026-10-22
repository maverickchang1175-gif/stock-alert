package com.example.stockalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Alert(
    val id: String,
    val symbol: String,
    val name: String,
    val currency: String,
    val target: Double,
    val above: Boolean,
    var lastPrice: Double?,
    var firedAt: Long?
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("symbol", symbol)
        o.put("name", name)
        o.put("currency", currency)
        o.put("target", target)
        o.put("above", above)
        val lp = lastPrice
        if (lp != null) o.put("lastPrice", lp)
        val fa = firedAt
        if (fa != null) o.put("firedAt", fa)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Alert {
            return Alert(
                o.getString("id"),
                o.getString("symbol"),
                o.optString("name", o.getString("symbol")),
                o.optString("currency", ""),
                o.getDouble("target"),
                o.getBoolean("above"),
                if (o.has("lastPrice")) o.getDouble("lastPrice") else null,
                if (o.has("firedAt")) o.getLong("firedAt") else null
            )
        }
    }
}

object Store {
    private const val PREF = "stock_alert"
    private val lock = Any()

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Alert> {
        return synchronized(lock) {
            val s = prefs(c).getString("alerts", "[]") ?: "[]"
            val arr = JSONArray(s)
            val list = mutableListOf<Alert>()
            for (i in 0 until arr.length()) {
                list.add(Alert.fromJson(arr.getJSONObject(i)))
            }
            list
        }
    }

    fun save(c: Context, list: List<Alert>) {
        synchronized(lock) {
            val arr = JSONArray()
            for (a in list) arr.put(a.toJson())
            prefs(c).edit().putString("alerts", arr.toString()).apply()
        }
    }

    fun update(c: Context, change: (MutableList<Alert>) -> Unit) {
        synchronized(lock) {
            val list = load(c)
            change(list)
            save(c, list)
        }
    }

    fun isMonitoring(c: Context): Boolean = prefs(c).getBoolean("monitoring", true)

    fun setMonitoring(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("monitoring", on).apply()
    }
}
