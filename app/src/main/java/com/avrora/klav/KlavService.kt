package com.avrora.klav

import android.content.ClipboardManager
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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.avrora.klav.Colors.BG
import com.avrora.klav.Colors.CYAN
import com.avrora.klav.Colors.KEY
import com.avrora.klav.Colors.MUTED
import com.avrora.klav.Colors.PINK
import com.avrora.klav.Colors.STROKE
import com.avrora.klav.Colors.TEXT

class KlavService : InputMethodService() {

    private enum class Mode { RU, EN, SYM }

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.RU
    private var lastLetterMode = Mode.RU
    private var shift = false
    private var showingClips = false

    private var cachedUri: String? = null
    private var cachedBg: Bitmap? = null

    private lateinit var rootView: FrameLayout
    private lateinit var keysArea: LinearLayout
    private lateinit var clipScroll: ScrollView
    private lateinit var clipList: LinearLayout
    private lateinit var clipButton: TextView

    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    /** Сохраняет каждое новое скопированное значение в историю буфера. */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (!text.isNullOrBlank()) {
            ClipStore.add(this, text)
            if (showingClips) refreshClips()
        }
    }

    /** Удержание backspace: удаляет символы, пока палец на кнопке. */
    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOne()
            handler.postDelayed(this, 60)
        }
    }

    private val deleteTouch = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                deleteOne()
                handler.postDelayed(repeatDelete, 400)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatDelete)
                true
            }
            else -> false
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = FrameLayout(this)
        rootView = root

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        column.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        keysArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(keysArea, LinearLayout.LayoutParams(MATCH, WRAP))

        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(clipList)
        }
        column.addView(clipScroll, LinearLayout.LayoutParams(MATCH, dp(220)))

        root.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        rebuildKeys()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::rootView.isInitialized) return
        applyAppearance()
        showClips(false)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // ---------- Внешний вид ----------

    private fun applyAppearance() {
        val prefs = ClipStore.prefs(this)
        val bmp = loadBackground(prefs.getString(ClipStore.PREF_BG, null))
        val dim = prefs.getInt(ClipStore.PREF_DIM, 55)
        if (bmp != null) {
            rootView.background = CropDrawable(bmp, Color.argb(dim * 255 / 100, 11, 15, 26))
        } else {
            rootView.setBackgroundColor(BG)
        }
    }

    private fun loadBackground(uriString: String?): Bitmap? {
        if (uriString == null) {
            cachedUri = null
            cachedBg = null
            return null
        }
        if (uriString == cachedUri) return cachedBg
        val bmp = try {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        } catch (e: Exception) {
            null
        }
        cachedUri = uriString
        cachedBg = bmp
        return bmp
    }

    // ---------- Верхняя панель и буфер ----------

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        bar.addView(
            TextView(this).apply {
                text = "✦ AVRORA"
                setTextColor(CYAN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                letterSpacing = 0.15f
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        clipButton = TextView(this).apply {
            text = "📋 Буфер"
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundRect(KEY, CYAN)
            setOnClickListener { showClips(!showingClips) }
        }
        bar.addView(clipButton, LinearLayout.LayoutParams(WRAP, WRAP))
        return bar
    }

    private fun showClips(show: Boolean) {
        showingClips = show
        clipScroll.visibility = if (show) View.VISIBLE else View.GONE
        keysArea.visibility = if (show) View.GONE else View.VISIBLE
        clipButton.text = if (show) "⌨ Клавиши" else "📋 Буфер"
        if (show) refreshClips()
    }

    private fun refreshClips() {
        clipList.removeAllViews()
        val clips = ClipStore.load(this).sortedByDescending { it.pinned }
        if (clips.isEmpty()) {
            clipList.addView(TextView(this).apply {
                text = "Буфер пуст. Скопируй текст в любом приложении, он появится здесь."
                setTextColor(MUTED)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }
        clips.forEach { clip ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(KEY, if (clip.pinned) PINK else STROKE)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    setMargins(0, dp(3), 0, dp(3))
                }
            }
            item.addView(
                TextView(this).apply {
                    text = clip.text.replace('\n', ' ')
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(TEXT)
                    setPadding(dp(10), dp(8), dp(6), dp(8))
                    setOnClickListener {
                        commit(clip.text)
                        showClips(false)
                    }
                },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            item.addView(iconButton(if (clip.pinned) "★" else "☆", CYAN) {
                ClipStore.togglePin(this, clip.text)
                refreshClips()
            })
            item.addView(iconButton("✕", MUTED) {
                ClipStore.remove(this, clip.text)
                refreshClips()
            })
            clipList.addView(item)
        }
    }

    private fun iconButton(label: String, color: Int, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onTap() }
        }

    // ---------- Раскладка ----------

    private fun rebuildKeys() {
        keysArea.removeAllViews()
        val rows = when (mode) {
            Mode.RU -> RU_ROWS
            Mode.EN -> EN_ROWS
            Mode.SYM -> SYM_ROWS
        }
        rows.forEachIndexed { index, letters ->
            val line = row()
            keysArea.addView(line)
            if (index == 2 && mode != Mode.SYM) {
                line.addView(key(if (shift) "⬆" else "⇧", 1.5f, active = shift) { toggleShift() })
            }
            letters.forEach { ch ->
                val c = ch.toString()
                line.addView(key(if (shift) c.uppercase() else c) { onChar(c) })
            }
            if (index == 2) {
                line.addView(key("⌫", 1.5f) { }.also { it.setOnTouchListener(deleteTouch) })
            }
        }
        keysArea.addView(bottomRow())
    }

    private fun bottomRow(): View {
        val line = row()
        val letterTarget = if (lastLetterMode == Mode.RU) Mode.EN else Mode.RU
        val switchLabel = when {
            mode == Mode.SYM && lastLetterMode == Mode.RU -> "АБВ"
            mode == Mode.SYM -> "ABC"
            else -> "?123"
        }
        line.addView(key(switchLabel, 1.4f) {
            setMode(if (mode == Mode.SYM) lastLetterMode else Mode.SYM)
        })
        line.addView(key(if (letterTarget == Mode.RU) "RU" else "EN", 1.2f) { setMode(letterTarget) })
        line.addView(key(",", 0.8f) { commit(",") })
        line.addView(key(if (lastLetterMode == Mode.RU) "пробел" else "space", 3.6f) { commit(" ") })
        line.addView(key(".", 0.8f) { commit(".") })
        line.addView(key("⏎", 1.6f, active = true) { enter() })
        return line
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun key(label: String, weight: Float = 1f, active: Boolean = false, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(if (active) BG else TEXT)
            background = roundRect(if (active) CYAN else KEY, if (active) CYAN else STROKE)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener { onTap() }
        }

    private fun roundRect(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    // ---------- Действия ----------

    private fun setMode(m: Mode) {
        if (m != Mode.SYM) lastLetterMode = m
        mode = m
        shift = false
        rebuildKeys()
    }

    private fun toggleShift() {
        shift = !shift
        rebuildKeys()
    }

    private fun onChar(c: String) {
        commit(if (shift) c.uppercase() else c)
        if (shift) {
            shift = false
            rebuildKeys()
        }
    }

    private fun commit(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    private fun deleteOne() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noEnterAction || action == EditorInfo.IME_ACTION_NONE) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(action)
        }
    }

    // ---------- Утилиты ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val RU_ROWS = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val SYM_ROWS = listOf("1234567890", "@#\$_&-+()/", "*\"':;!?")
    }
}

/** Рисует фоновую картинку с центрированием (как CENTER_CROP) и затемнением поверх. */
private class CropDrawable(private val bmp: Bitmap, dimColor: Int) : Drawable() {
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
