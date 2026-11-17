package com.avrora.klav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Палитра в стиле Авроры: тёмный фон, неоновый циан и розовый акцент. */
internal object Colors {
    val BG = 0xFF0B0F1A.toInt()
    val STROKE = 0x663EE8FF.toInt()
    val CYAN = 0xFF3EE8FF.toInt()
    val PINK = 0xFFFF4FD8.toInt()
    val TEXT = 0xFFE6F7FF.toInt()
    val MUTED = 0xFF7F8AA3.toInt()
}

/** Все пользовательские настройки. */
object Prefs {
    private const val NAME = "avrora_klav"

    fun file(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun bgUri(ctx: Context): String? = file(ctx).getString("bg_uri", null)
    fun setBgUri(ctx: Context, v: String?) = file(ctx).edit().putString("bg_uri", v).apply()

    /** Затемнение фона, 0..90 процентов. */
    fun dim(ctx: Context): Int = file(ctx).getInt("bg_dim", 55)
    fun setDim(ctx: Context, v: Int) = file(ctx).edit().putInt("bg_dim", v).apply()

    /** Непрозрачность заливки клавиш, 0..100 процентов. 0 — полностью прозрачные. */
    fun keyAlpha(ctx: Context): Int = file(ctx).getInt("key_alpha", 20)
    fun setKeyAlpha(ctx: Context, v: Int) = file(ctx).edit().putInt("key_alpha", v).apply()

    fun sound(ctx: Context): Boolean = file(ctx).getBoolean("sfx", true)
    fun setSound(ctx: Context, v: Boolean) = file(ctx).edit().putBoolean("sfx", v).apply()

    fun haptic(ctx: Context): Boolean = file(ctx).getBoolean("haptic", true)
    fun setHaptic(ctx: Context, v: Boolean) = file(ctx).edit().putBoolean("haptic", v).apply()

    fun glitch(ctx: Context): Boolean = file(ctx).getBoolean("glitch", true)
    fun setGlitch(ctx: Context, v: Boolean) = file(ctx).edit().putBoolean("glitch", v).apply()

    fun autoCap(ctx: Context): Boolean = file(ctx).getBoolean("autocap", true)
    fun setAutoCap(ctx: Context, v: Boolean) = file(ctx).edit().putBoolean("autocap", v).apply()
}

/** Общие правила оформления, которые используют клавиатура и экран настроек. */
object Appearance {
    private var cachedUri: String? = null
    private var cachedBitmap: Bitmap? = null

    /** Заливка клавиши: тот же тёмно-синий, но с настраиваемой непрозрачностью. */
    fun keyFill(alpha: Int): Int = Color.argb(alpha * 255 / 100, 26, 34, 54)

    fun bitmap(ctx: Context, uri: String?): Bitmap? {
        if (uri == null) {
            cachedUri = null
            cachedBitmap = null
            return null
        }
        if (uri == cachedUri) return cachedBitmap
        val bmp = try {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        } catch (e: Exception) {
            null
        }
        cachedUri = uri
        cachedBitmap = bmp
        return bmp
    }

    fun backgroundDrawable(ctx: Context, uri: String?, dim: Int): Drawable {
        val bmp = bitmap(ctx, uri) ?: return ColorDrawable(Colors.BG)
        return CropDrawable(bmp, Color.argb(dim * 255 / 100, 11, 15, 26))
    }
}

data class Clip(val text: String, val pinned: Boolean)

/** История буфера обмена (JSON в SharedPreferences). */
object ClipStore {
    private const val KEY_CLIPS = "clips"
    private const val MAX_UNPINNED = 60

    fun load(ctx: Context): MutableList<Clip> {
        val raw = Prefs.file(ctx).getString(KEY_CLIPS, null) ?: return mutableListOf()
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
        Prefs.file(ctx).edit().putString(KEY_CLIPS, arr.toString()).apply()
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
        Prefs.file(ctx).edit().remove(KEY_CLIPS).apply()
    }
}

/** Рисует картинку по центру с заполнением (как CENTER_CROP) и затемнением поверх. */
internal class CropDrawable(private val bmp: Bitmap, dimColor: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = dimColor }
    private val src = Rect(0, 0, bmp.width, bmp.height)
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = b.left + (b.width() - w) / 2f
        val top = b.top + (b.height() - h) / 2f
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.drawRect(b, dimPaint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
