package com.avrora.klav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Палитра в стиле Авроры: тёмный фон, неоновый циан и розовый акцент. */
internal object Colors {
    val BG = 0xFF0B0F1A.toInt()
    val KEY = 0xE61A2236.toInt()
    val STROKE = 0x663EE8FF.toInt()
    val CYAN = 0xFF3EE8FF.toInt()
    val PINK = 0xFFFF4FD8.toInt()
    val TEXT = 0xFFE6F7FF.toInt()
    val MUTED = 0xFF7F8AA3.toInt()
}

data class Clip(val text: String, val pinned: Boolean)

/** Хранилище буфера обмена и настроек (SharedPreferences + JSON). */
object ClipStore {
    private const val PREFS = "avrora_klav"
    private const val KEY_CLIPS = "clips"
    private const val MAX_UNPINNED = 60

    const val PREF_BG = "bg_uri"
    const val PREF_DIM = "bg_dim"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Clip> {
        val raw = prefs(ctx).getString(KEY_CLIPS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Clip(o.getString("t"), o.optBoolean("p", false))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, clips: List<Clip>) {
        val arr = JSONArray()
        clips.forEach { arr.put(JSONObject().put("t", it.text).put("p", it.pinned)) }
        prefs(ctx).edit().putString(KEY_CLIPS, arr.toString()).apply()
    }

    /** Добавляет скопированный текст наверх. Закреплённые записи не теряются. */
    fun add(ctx: Context, text: String) {
        if (text.isBlank() || text.length > 5000) return
        val list = load(ctx)
        val wasPinned = list.any { it.text == text && it.pinned }
        list.removeAll { it.text == text }
        list.add(0, Clip(text, wasPinned))
        var unpinned = 0
        val trimmed = list.filter { it.pinned || ++unpinned <= MAX_UNPINNED }
        save(ctx, trimmed)
    }

    fun togglePin(ctx: Context, text: String) {
        val updated = load(ctx).map { if (it.text == text) it.copy(pinned = !it.pinned) else it }
        save(ctx, updated)
    }

    fun remove(ctx: Context, text: String) {
        val list = load(ctx)
        list.removeAll { it.text == text }
        save(ctx, list)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CLIPS).apply()
    }
}
