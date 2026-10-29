package com.woyuxiaji.dianmo.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 地址簿：按 包名 + 类型 + 地址 唯一保存 */
object SavedStore {

    private const val PREF = "memtool_saved"
    private const val KEY = "list"

    data class Item(
        val pkg: String,
        val type: String,
        val addr: Long,
        val note: String,
        val value: String,
    )

    fun load(ctx: Context): MutableList<Item> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        pkg = o.optString("pkg"),
                        type = o.optString("type", "i32"),
                        addr = o.optLong("addr", 0L),
                        note = o.optString("note"),
                        value = o.optString("value"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            ArrayList()
        }
    }

    fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("pkg", item.pkg)
                    put("type", item.type)
                    put("addr", item.addr)
                    put("note", item.note)
                    put("value", item.value)
                }
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
