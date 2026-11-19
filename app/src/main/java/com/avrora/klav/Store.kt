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

/** Тема: акцент, второй акцент и цвет текста. */
internal class Theme(val name: String, val accent: Int, val accent2: Int, val text: Int)

internal val THEMES: List<Theme> = listOf(
    Theme("Аврора", 0xFF3EE8FFL.toInt(), 0xFFFF4FD8L.toInt(), 0xFFE6F7FFL.toInt()),
    Theme("Лёд", 0xFF9BE7FFL.toInt(), 0xFFFFFFFFL.toInt(), 0xFFF0FAFFL.toInt()),
    Theme("Лава", 0xFFFF9A3EL.toInt(), 0xFFFF3D5AL.toInt(), 0xFFFFF1E6L.toInt()),
    Theme("Изумруд", 0xFF3EFFA6L.toInt(), 0xFFB6FF3EL.toInt(), 0xFFE8FFF3L.toInt()),
    Theme("Монохром", 0xFFFFFFFFL.toInt(), 0xFF9AA3B5L.toInt(), 0xFFF2F2F2L.toInt())
)

/**
 * Цвета интерфейса. Меняются при смене темы; клавиатура пересоздаёт вид, чтобы применить их.
 */
internal object Colors {
    var BG = 0xFF0B0F1AL.toInt()
    var STROKE = 0x663EE8FFL.toInt()
    var CYAN = 0xFF3EE8FFL.toInt()
    var PINK = 0xFFFF4FD8L.toInt()
    var TEXT = 0xFFE6F7FFL.toInt()
    var MUTED = 0xFF7F8AA3L.toInt()

    fun applyTheme(index: Int) {
        val t = THEMES[index.coerceIn(0, THEMES.size - 1)]
        CYAN = t.accent
        PINK = t.accent2
        TEXT = t.text
        STROKE = (t.accent and 0x00FFFFFF) or (0x66 shl 24)
    }
}

/** Все пользовательские настройки. */
object Prefs {
    private const val NAME = "avrora_klav"

    fun file(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun b(ctx: Context, k: String, def: Boolean) = file(ctx).getBoolean(k, def)
    private fun setB(ctx: Context, k: String, v: Boolean) = file(ctx).edit().putBoolean(k, v).apply()
    private fun i(ctx: Context, k: String, def: Int) = file(ctx).getInt(k, def)
    private fun setI(ctx: Context, k: String, v: Int) = file(ctx).edit().putInt(k, v).apply()

    fun bgUri(ctx: Context): String? = file(ctx).getString("bg_uri", null)
    fun setBgUri(ctx: Context, v: String?) = file(ctx).edit().putString("bg_uri", v).apply()

    /** Затемнение фона, 0..90 процентов. */
    fun dim(ctx: Context) = i(ctx, "bg_dim", 55)
    fun setDim(ctx: Context, v: Int) = setI(ctx, "bg_dim", v)

    /** Масштаб фона, 100..300 процентов. */
    fun bgZoom(ctx: Context) = i(ctx, "bg_zoom", 100)
    fun setBgZoom(ctx: Context, v: Int) = setI(ctx, "bg_zoom", v)

    /** Смещение фона по осям, от -1 до 1 (0 — по центру). */
    fun bgOx(ctx: Context): Float = file(ctx).getFloat("bg_ox", 0f)
    fun bgOy(ctx: Context): Float = file(ctx).getFloat("bg_oy", 0f)
    fun setBgOffset(ctx: Context, x: Float, y: Float) =
        file(ctx).edit().putFloat("bg_ox", x).putFloat("bg_oy", y).apply()

    /** Размер букв на клавишах, 12..26 sp. */
    fun keySp(ctx: Context) = i(ctx, "key_sp", 17)
    fun setKeySp(ctx: Context, v: Int) = setI(ctx, "key_sp", v)

    /** Базовая высота клавиши в dp, 36..64. */
    fun keyH(ctx: Context) = i(ctx, "key_h", 44)
    fun setKeyH(ctx: Context, v: Int) = setI(ctx, "key_h", v)

    /** Непрозрачность заливки клавиш, 0..100 процентов. 0 — полностью прозрачные. */
    fun keyAlpha(ctx: Context) = i(ctx, "key_alpha", 20)
    fun setKeyAlpha(ctx: Context, v: Int) = setI(ctx, "key_alpha", v)

    /** Индекс темы из [THEMES]. */
    fun theme(ctx: Context) = i(ctx, "theme", 0)
    fun setTheme(ctx: Context, v: Int) = setI(ctx, "theme", v)

    fun numRow(ctx: Context) = b(ctx, "num_row", true)
    fun setNumRow(ctx: Context, v: Boolean) = setB(ctx, "num_row", v)

    fun swipe(ctx: Context) = b(ctx, "swipe", true)
    fun setSwipe(ctx: Context, v: Boolean) = setB(ctx, "swipe", v)

    fun sound(ctx: Context) = b(ctx, "sfx", true)
    fun setSound(ctx: Context, v: Boolean) = setB(ctx, "sfx", v)

    fun haptic(ctx: Context) = b(ctx, "haptic", true)
    fun setHaptic(ctx: Context, v: Boolean) = setB(ctx, "haptic", v)

    fun glitch(ctx: Context) = b(ctx, "glitch", true)
    fun setGlitch(ctx: Context, v: Boolean) = setB(ctx, "glitch", v)

    fun autoCap(ctx: Context) = b(ctx, "autocap", true)
    fun setAutoCap(ctx: Context, v: Boolean) = setB(ctx, "autocap", v)

    fun autoCorrect(ctx: Context) = b(ctx, "autocorrect", true)
    fun setAutoCorrect(ctx: Context, v: Boolean) = setB(ctx, "autocorrect", v)

    fun suggest(ctx: Context) = b(ctx, "suggest", true)
    fun setSuggest(ctx: Context, v: Boolean) = setB(ctx, "suggest", v)

    /** Вопросительный знак вместо точки, если предложение начинается с вопросительного слова. */
    fun autoQuestion(ctx: Context) = b(ctx, "auto_q", true)
    fun setAutoQuestion(ctx: Context, v: Boolean) = setB(ctx, "auto_q", v)

    fun doubleSpace(ctx: Context) = b(ctx, "double_space", true)
    fun setDoubleSpace(ctx: Context, v: Boolean) = setB(ctx, "double_space", v)

    fun idleAnim(ctx: Context) = b(ctx, "idle_anim", true)
    fun setIdleAnim(ctx: Context, v: Boolean) = setB(ctx, "idle_anim", v)

    /** Недавние эмодзи, по порядку от новых к старым. */
    fun recentEmoji(ctx: Context): List<String> =
        file(ctx).getString("emoji_recent", "").orEmpty().split(' ').filter { it.isNotEmpty() }

    fun addRecentEmoji(ctx: Context, e: String) {
        val list = (listOf(e) + recentEmoji(ctx).filter { it != e }).take(24)
        file(ctx).edit().putString("emoji_recent", list.joinToString(" ")).apply()
    }
}

/** Общие правила оформления: клавиатура и экран настроек рисуют фон одинаково. */
object Appearance {
    private var cachedUri: String? = null
    private var cachedBitmap: Bitmap? = null

    /** Заливка клавиши: тёмно-синий с настраиваемой непрозрачностью. */
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
        return CropDrawable(
            bmp,
            Color.argb(dim * 255 / 100, 11, 15, 26),
            Prefs.bgOx(ctx),
            Prefs.bgOy(ctx),
            Prefs.bgZoom(ctx) / 100f
        )
    }

    /**
     * Цвет системной нижней панели: средний цвет нижней части картинки
     * с учётом затемнения, чтобы полоса совпадала с фоном.
     */
    fun navColor(ctx: Context, uri: String?, dim: Int): Int {
        val bmp = bitmap(ctx, uri) ?: return Colors.BG
        val startY = (bmp.height * 0.92f).toInt().coerceIn(0, bmp.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        var y = startY
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
                x += 8
            }
            y += 8
        }
        if (n == 0L) return Colors.BG
        val a = dim / 100f
        fun mix(sum: Long, dark: Int): Int = ((sum / n) * (1 - a) + dark * a).toInt()
        return Color.rgb(mix(r, 11), mix(g, 15), mix(b, 26))
    }

    /** Насколько картинку можно сдвинуть в каждую сторону, в пикселях. */
    fun panRange(bmpW: Int, bmpH: Int, viewW: Float, viewH: Float, zoom: Float): Pair<Float, Float> {
        val s = maxOf(viewW / bmpW, viewH / bmpH) * zoom
        return Pair(maxOf(0f, (bmpW * s - viewW) / 2f), maxOf(0f, (bmpH * s - viewH) / 2f))
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

/** Рисует картинку с заполнением (как CENTER_CROP), масштабом, смещением и затемнением. */
internal class CropDrawable(
    private val bmp: Bitmap,
    dimColor: Int,
    private val ox: Float,
    private val oy: Float,
    private val zoom: Float
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = dimColor }
    private val src = Rect(0, 0, bmp.width, bmp.height)
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bw = b.width().toFloat()
        val bh = b.height().toFloat()
        val (rx, ry) = Appearance.panRange(bmp.width, bmp.height, bw, bh, zoom)
        val s = maxOf(bw / bmp.width, bh / bmp.height) * zoom
        val w = bmp.width * s
        val h = bmp.height * s
        val left = b.left + (bw - w) / 2f + ox.coerceIn(-1f, 1f) * rx
        val top = b.top + (bh - h) / 2f + oy.coerceIn(-1f, 1f) * ry
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.drawRect(b, dimPaint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
