package com.avrora.klav

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.ImageView
import android.widget.Toast
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.avrora.klav.Colors.BG
import com.avrora.klav.Colors.CYAN
import com.avrora.klav.Colors.MUTED
import com.avrora.klav.Colors.PINK
import com.avrora.klav.Colors.STROKE
import com.avrora.klav.Colors.TEXT
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class KlavService : InputMethodService() {

    private enum class Mode { RU, EN, SYM }
    private enum class Panel { KEYS, CLIPS, SETTINGS, PUNCT, EMOJI }

    /** Подсказка при касании к слову, которое исправила автозамена: можно вернуть исходное. */
    private class Hint(val fixed: String, val original: String, val beforeLen: Int, val afterLen: Int)

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.RU
    private var lastLetterMode = Mode.RU
    private var shift = false
    private var panel = Panel.KEYS
    private var tone: ToneGenerator? = null
    private var lastSpaceAt = 0L

    /** Последняя автозамена пробелом: (что набрал, на что заменили). Нужна для отмены Backspace. */
    private var lastAuto: Pair<String, String>? = null

    /** Исправления этой сессии: исправленное слово (в нижнем регистре) → исходное. */
    private val recentFixes = LinkedHashMap<String, String>()
    private var activeHint: Hint? = null
    private var lastEditAt = 0L

    /** Последнее законченное слово (в нижнем регистре). От него строятся подсказки следующего слова. */
    private var prevWord = ""

    // Свайп-ввод: буквы, над которыми прошёл палец, и состояние текущего касания.
    private val letterViews = ArrayList<View>()
    private val swipePath = StringBuilder()
    private var swipeMoved = false
    private var downX = 0f
    private var downY = 0f
    private var downChar = ""

    /** Долгое нажатие на букву: показывает варианты (ё, é, ñ…) в строке подсказок. */
    private var longFired = false
    private val longPressRunnable = Runnable {
        val variants = ALTS[downChar]
        if (variants != null) {
            longFired = true
            showVariants(variants)
        }
    }

    // Эмодзи-панель: выбранная вкладка.
    private var emojiCat = 1

    // Состояние длинного нажатия пробела (курсор).
    private var spaceX = 0f
    private var spaceLong = false
    private val spaceLongRunnable = Runnable {
        spaceLong = true
        buzz()
    }

    // Idle-анимация: клавиатура глитчит, когда её долго не трогают.
    private val idleRunnable = Runnable {
        playIdleGlitch()
        scheduleIdle()
    }

    private lateinit var rootView: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var suggestBar: LinearLayout
    private lateinit var suggestChips: List<TextView>
    private lateinit var keysArea: LinearLayout
    private lateinit var clipScroll: ScrollView
    private lateinit var clipList: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var punctScroll: ScrollView
    private lateinit var emojiPanelView: View
    private lateinit var emojiTabs: LinearLayout
    private lateinit var emojiBox: LinearLayout
    private lateinit var clipButton: TextView
    private lateinit var settingsButton: TextView

    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** Сохраняет каждое новое скопированное значение в историю буфера. */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Стикеры кладутся в буфер как картинки: в историю текста они не попадают.
        if (clipboard.primaryClipDescription?.hasMimeType("image/*") == true) return@OnPrimaryClipChangedListener
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (!text.isNullOrBlank()) {
            ClipStore.add(this, text)
            if (panel == Panel.CLIPS) refreshClips()
        }
    }

    /** Удержание backspace: удаляет символы, пока палец на кнопке. */
    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOne()
            handler.postDelayed(this, 60)
        }
    }

    private val deleteTouch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                feedback(v)
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

    /**
     * Буквы: короткое нажатие печатает букву, а проведённый по буквам палец
     * превращается в слово (свайп-ввод).
     */
    private val letterTouch = View.OnTouchListener { v, e ->
        val ch = v.tag as? String ?: return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                feedback(v)
                downChar = ch
                swipePath.setLength(0)
                swipePath.append(ch)
                downX = e.rawX
                downY = e.rawY
                swipeMoved = false
                longFired = false
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeMoved && hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dp(14).toDouble()) {
                    swipeMoved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (swipeMoved && Prefs.swipe(this)) {
                    val k = letterAt(e.rawX, e.rawY)
                    if (k != null && k != swipePath.last().toString()) swipePath.append(k)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longFired) {
                    // Варианты уже показаны в строке подсказок: буква не печатается.
                    longFired = false
                } else if (Prefs.swipe(this) && swipeMoved && swipePath.length >= 3) {
                    swipeCommit(swipePath.toString())
                } else {
                    onChar(downChar)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                swipeMoved = false
            }
            else -> return@OnTouchListener false
        }
        true
    }

    /**
     * Пробел: короткое нажатие печатает пробел, удержание и движение пальца
     * двигают курсор по тексту.
     */
    private val spaceTouch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spaceX = e.x
                spaceLong = false
                feedback(v)
                handler.postDelayed(spaceLongRunnable, 350)
            }
            MotionEvent.ACTION_MOVE -> {
                if (spaceLong) {
                    val step = dp(14).toFloat()
                    val n = ((e.x - spaceX) / step).toInt()
                    if (n != 0) {
                        val code = if (n > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                        repeat(abs(n)) { sendDownUpKeyEvents(code) }
                        spaceX += n * step
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(spaceLongRunnable)
                if (!spaceLong) spaceCommit()
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(spaceLongRunnable)
            else -> return@OnTouchListener false
        }
        true
    }

    override fun onCreate() {
        super.onCreate()
        Words.init(this)
        Thread { Words.warm() }.start()
        clipboard.addPrimaryClipChangedListener(clipListener)
        tone = try {
            ToneGenerator(AudioManager.STREAM_SYSTEM, 35)
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        Colors.applyTheme(Prefs.theme(this))
        val root = FrameLayout(this)
        rootView = root

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        column.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        // Строка подсказок всегда занимает место, поэтому клавиши не прыгают при наборе.
        suggestBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestChips = List(3) { buildSuggestChip() }
        suggestChips.forEach { suggestBar.addView(it) }
        column.addView(suggestBar, LinearLayout.LayoutParams(MATCH, dp(38)))

        keysArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(keysArea, LinearLayout.LayoutParams(MATCH, WRAP))

        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(clipList)
        }
        column.addView(clipScroll, LinearLayout.LayoutParams(MATCH, dp(240)))

        settingsScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(buildSettingsPanel())
        }
        column.addView(settingsScroll, LinearLayout.LayoutParams(MATCH, dp(240)))

        punctScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(buildPunctPanel())
        }
        column.addView(punctScroll, LinearLayout.LayoutParams(MATCH, dp(240)))

        emojiPanelView = buildEmojiPanel().apply { visibility = View.GONE }
        column.addView(emojiPanelView, LinearLayout.LayoutParams(MATCH, dp(240)))

        root.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        applyAppearance()
        rebuildKeys()
        showPanel(panel)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::rootView.isInitialized) return
        applyAppearance()
        activeHint = null
        prevWord = ""
        showPanel(Panel.KEYS)
        scheduleIdle()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(idleRunnable)
        super.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Курсор переехал (например, пользователь тапнул по тексту). Если это слово исправила
     * автозамена, в строке подсказок появится исходное слово.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!::suggestChips.isInitialized) return
        activeHint = null
        val ic = currentInputConnection
        val selfEdit = System.currentTimeMillis() - lastEditAt < 400
        if (ic != null && newSelStart == newSelEnd && !selfEdit && recentFixes.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(40, 0)?.toString() ?: ""
            val bw = before.takeLastWhile { it.isLetter() }
            val aw = after.takeWhile { it.isLetter() }
            val word = bw + aw
            val orig = if (word.isNotEmpty()) recentFixes[word.lowercase()] else null
            if (orig != null && orig != word) activeHint = Hint(word, orig, bw.length, aw.length)
        }
        updateSuggestions()
    }

    // ---------- Внешний вид ----------

    /**
     * Фон клавиатуры и нижняя системная панель берутся из одной картинки,
     * поэтому полоса с кнопками «свернуть» и «сменить клавиатуру» выглядит частью фона.
     */
    private fun applyAppearance() {
        val uri = Prefs.bgUri(this)
        val dim = Prefs.dim(this)
        rootView.background = Appearance.backgroundDrawable(this, uri, dim)
        window.window?.let { w ->
            val color = Appearance.navColor(this, uri, dim)
            w.navigationBarColor = color
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            if (Build.VERSION.SDK_INT >= 30) {
                // Светлая полоса — тёмные значки, тёмная — светлые.
                val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
                val flag = if (lum > 140) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0
                w.insetsController?.setSystemBarsAppearance(flag, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        }
    }

    /** Перерисовывает всю клавиатуру, чтобы применить новую тему. Открытая панель остаётся открытой. */
    private fun restyle() {
        Colors.applyTheme(Prefs.theme(this))
        setInputView(onCreateInputView())
    }

    // ---------- Верхняя панель ----------

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        // Логотип AV перекрашивается в цвет темы, поэтому сделан из маски, а не картинкой как есть.
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.av_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(CYAN, PorterDuff.Mode.SRC_IN)
        }
        titleView = TextView(this).apply {
            text = "AVRORA"
            setTextColor(CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.15f
            setPadding(dp(6), 0, 0, 0)
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(WRAP, dp(16)))
            addView(titleView, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        bar.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        clipButton = chip("📋 Буфер") { togglePanel(Panel.CLIPS) }
        settingsButton = chip("⚙") { togglePanel(Panel.SETTINGS) }
        bar.addView(clipButton, LinearLayout.LayoutParams(WRAP, WRAP))
        bar.addView(settingsButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) })
        return bar
    }

    private fun chip(label: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = roundRect(Appearance.keyFill(Prefs.keyAlpha(this@KlavService)), CYAN)
        setOnClickListener { onTap() }
    }

    private fun togglePanel(p: Panel) = showPanel(if (panel == p) Panel.KEYS else p)

    private fun showPanel(p: Panel) {
        panel = p
        keysArea.visibility = if (p == Panel.KEYS) View.VISIBLE else View.GONE
        clipScroll.visibility = if (p == Panel.CLIPS) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (p == Panel.SETTINGS) View.VISIBLE else View.GONE
        punctScroll.visibility = if (p == Panel.PUNCT) View.VISIBLE else View.GONE
        emojiPanelView.visibility = if (p == Panel.EMOJI) View.VISIBLE else View.GONE
        clipButton.setTextColor(if (p == Panel.CLIPS) CYAN else TEXT)
        settingsButton.setTextColor(if (p == Panel.SETTINGS) CYAN else TEXT)
        if (p == Panel.EMOJI) {
            renderEmojiTabs()
            renderEmoji()
        }
        if (p == Panel.CLIPS) refreshClips()
        updateSuggestions()
    }

    // ---------- Панель знаков препинания (долгое нажатие на точку) ----------

    private fun buildPunctPanel(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        col.addView(hint("Знаки препинания"))
        PUNCT_MARKS.chunked(6).forEach { chunk ->
            val line = row()
            chunk.forEach { m ->
                line.addView(key(m, 1f) {
                    punct(m)
                    showPanel(Panel.KEYS)
                })
            }
            // Добьём последнюю строку пустыми ячейками, чтобы кнопки были одного размера.
            repeat(6 - chunk.size) { line.addView(key(" ", 1f) { }) }
            col.addView(line)
        }
        col.addView(key("ABC", 1f, active = true) { showPanel(Panel.KEYS) }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(46)).apply { setMargins(dp(2), dp(6), dp(2), dp(3)) }
        })
        return col
    }

    // ---------- Эмодзи-панель ----------

    private fun buildEmojiPanel(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        emojiTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(this).apply { addView(emojiTabs) }, LinearLayout.LayoutParams(MATCH, WRAP))
        emojiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(this).apply { addView(emojiBox) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(key("ABC", 1f, active = true) { showPanel(Panel.KEYS) }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(40)).apply { setMargins(dp(2), dp(4), dp(2), dp(2)) }
        })
        return col
    }

    /** Вкладки категорий. Первая — недавние. */
    private fun renderEmojiTabs() {
        emojiTabs.removeAllViews()
        EMOJI_TABS.forEachIndexed { i, pair ->
            emojiTabs.addView(TextView(this).apply {
                text = pair.first
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = if (i == emojiCat) {
                    roundRect(Appearance.keyFill(Prefs.keyAlpha(this@KlavService)), CYAN)
                } else {
                    null
                }
                setOnClickListener {
                    emojiCat = i
                    renderEmojiTabs()
                    renderEmoji()
                }
            })
        }
    }

    /** Сетка смайликов выбранной категории. Панель остаётся открытой, пока её не закроют. */
    private fun renderEmoji() {
        emojiBox.removeAllViews()
        if (emojiCat == EMOJI_TABS.lastIndex) {
            renderStickers()
            return
        }
        if (EMOJI_TABS[emojiCat].first == "❤️") {
            // Текстовый рисунок логотипа AV: одна кнопка сверху, вставляет всё целиком.
            emojiBox.addView(TextView(this).apply {
                text = AV_ART.joinToString("\n")
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f)
                setTextColor(CYAN)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
                background = roundRect(Appearance.keyFill(Prefs.keyAlpha(this@KlavService)), STROKE)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(6)) }
                setOnClickListener { commit(AV_ART.joinToString("\n")) }
            })
        }
        val items =if (emojiCat == 0) Prefs.recentEmoji(this) else EMOJI_TABS[emojiCat].second.split(" ")
        if (items.isEmpty()) {
            emojiBox.addView(hint("Пока пусто. Выбери смайлик, и он появится здесь."))
            return
        }
        items.chunked(8).forEach { line ->
            val r = row()
            line.forEach { e ->
                if (e == "AV") {
                    // В панели показываем сам логотип; в поле ввода уходит текст «AV».
                    r.addView(ImageView(this).apply {
                        setImageResource(R.drawable.av_logo)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setColorFilter(CYAN, PorterDuff.Mode.SRC_IN)
                        setPadding(dp(6), dp(10), dp(6), dp(10))
                        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                        setOnClickListener { insertEmoji(e) }
                    })
                } else {
                    r.addView(TextView(this).apply {
                        text = e
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                        setOnClickListener { insertEmoji(e) }
                    })
                }
            }
            repeat(8 - line.size) { r.addView(View(this), LinearLayout.LayoutParams(0, dp(46), 1f)) }
            emojiBox.addView(r)
        }
    }

    /** Сетка стикеров Авроры из assets. Нажатие отправляет картинку в поле ввода. */
    private fun renderStickers() {
        val files = (assets.list(STICKER_DIR) ?: emptyArray()).filter { it.endsWith(".png") }.sorted()
        if (files.isEmpty()) {
            emojiBox.addView(hint("Стикеры не найдены."))
            return
        }
        files.chunked(3).forEach { line ->
            val r = row()
            line.forEach { file ->
                val bmp = try {
                    assets.open("$STICKER_DIR/$file").use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
                r.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bmp)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f)
                    setOnClickListener { insertSticker(file) }
                })
            }
            repeat(3 - line.size) { r.addView(View(this), LinearLayout.LayoutParams(0, dp(96), 1f)) }
            emojiBox.addView(r)
        }
    }

    /**
     * Отправляет стикер картинкой через commitContent. Если приложение картинки не принимает,
     * стикер кладётся в буфер, и его можно вставить вручную.
     */
    private fun insertSticker(file: String) {
        val uri = Uri.parse("content://${StickerProvider.AUTHORITY}/$file")
        val ic = currentInputConnection
        val accepts = currentInputEditorInfo?.contentMimeTypes?.any { it == "image/png" || it == "image/*" } == true
        if (ic != null && accepts && Build.VERSION.SDK_INT >= 25 && commitImage(ic, uri)) return
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "sticker", uri))
        Toast.makeText(this, "Стикер в буфере: вставь его в поле ввода", Toast.LENGTH_SHORT).show()
    }

    @Suppress("NewApi")
    private fun commitImage(ic: InputConnection, uri: Uri): Boolean {
        val info = InputContentInfo(uri, ClipDescription("sticker", arrayOf("image/png")))
        return ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    private fun insertEmoji(e: String) {
        Prefs.addRecentEmoji(this, e)
        if (emojiCat == 0) renderEmoji()
        commit(e)
    }

    // ---------- Панель настроек визуала ----------

    private fun buildSettingsPanel(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        col.addView(hint("Визуал"))
        col.addView(slider("Затемнение фона", 90, Prefs.dim(this), 0, "%") {
            Prefs.setDim(this, it)
            applyAppearance()
        })
        col.addView(slider("Прозрачность клавиш", 100, Prefs.keyAlpha(this), 0, "%") {
            Prefs.setKeyAlpha(this, it)
            rebuildKeys()
        })
        col.addView(slider("Размер букв", 14, Prefs.keySp(this), 12, " sp") {
            Prefs.setKeySp(this, it)
            rebuildKeys()
        })
        col.addView(slider("Высота клавиш", 28, Prefs.keyH(this), 36, " dp") {
            Prefs.setKeyH(this, it)
            rebuildKeys()
        })
        col.addView(hint("Тема"))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val current = Prefs.theme(this)
        THEMES.forEachIndexed { i, t ->
            themeRow.addView(chip(t.name) {
                Prefs.setTheme(this, i)
                restyle()
            }.apply {
                setTextColor(if (i == current) CYAN else TEXT)
            })
        }
        col.addView(HorizontalScrollView(this).apply { addView(themeRow) })
        col.addView(hint("Клавиатура"))
        col.addView(toggle("Ряд цифр над буквами", Prefs.numRow(this)) {
            Prefs.setNumRow(this, it)
            rebuildKeys()
        })
        col.addView(toggle("Свайп-ввод по буквам", Prefs.swipe(this)) { Prefs.setSwipe(this, it) })
        col.addView(hint("Отклик и анимации"))
        col.addView(toggle("Звук при нажатии", Prefs.sound(this)) { Prefs.setSound(this, it) })
        col.addView(toggle("Вибрация", Prefs.haptic(this)) { Prefs.setHaptic(this, it) })
        col.addView(toggle("Глитч при нажатии", Prefs.glitch(this)) { Prefs.setGlitch(this, it) })
        col.addView(toggle("Анимация простоя", Prefs.idleAnim(this)) {
            Prefs.setIdleAnim(this, it)
            if (it) scheduleIdle() else handler.removeCallbacks(idleRunnable)
        })
        col.addView(hint("Текст"))
        col.addView(toggle("Заглавная после точки", Prefs.autoCap(this)) { Prefs.setAutoCap(this, it) })
        col.addView(toggle("Автозамена опечаток", Prefs.autoCorrect(this)) { Prefs.setAutoCorrect(this, it) })
        col.addView(toggle("Подсказки слов", Prefs.suggest(this)) {
            Prefs.setSuggest(this, it)
            updateSuggestions()
        })
        col.addView(toggle("Точка двойным пробелом", Prefs.doubleSpace(this)) { Prefs.setDoubleSpace(this, it) })
        col.addView(chip("Готово") { showPanel(Panel.KEYS) }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, dp(8), 0, 0) }
        })
        return col
    }

    /**
     * Ползунок. [value] — текущее значение, [offset] — наименьшее значение ползунка,
     * [suffix] — единица в подписи.
     */
    private fun slider(
        title: String,
        maxValue: Int,
        value: Int,
        offset: Int,
        suffix: String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val label = hint("$title: $value$suffix")
        box.addView(label)
        box.addView(SeekBar(this).apply {
            max = maxValue
            progress = value - offset
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title: ${p + offset}$suffix"
                    if (fromUser) onChange(p + offset)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        return box
    }

    private fun toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = title
            setTextColor(TEXT)
            isChecked = checked
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }

    private fun hint(s: String): TextView = TextView(this).apply {
        text = s
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(6), 0, dp(2))
    }

    // ---------- Строка подсказок ----------

    private fun buildSuggestChip(): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = roundRect(Appearance.keyFill(Prefs.keyAlpha(this@KlavService)), STROKE)
        layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    /**
     * Три постоянные ячейки. Слева набранное слово, по центру главный вариант, справа второй.
     * Если курсор стоит на слове, которое исправила автозамена, по центру — исходное слово.
     * Если слово ещё не набирается, а предыдущее закончено, — подсказки следующего слова.
     * Пустые ячейки не меняют высоту строки.
     */
    private fun updateSuggestions() {
        if (!::suggestChips.isInitialized) return
        val visible = Prefs.suggest(this) && panel == Panel.KEYS
        suggestBar.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) return

        val h = activeHint
        if (h != null) {
            val typed = suggestChips[0]
            typed.text = h.fixed
            typed.setTextColor(MUTED)
            typed.setOnClickListener(null)
            val orig = suggestChips[1]
            orig.text = h.original
            orig.setTextColor(CYAN)
            orig.setOnClickListener { applyHint(h) }
            suggestChips[2].text = ""
            suggestChips[2].setOnClickListener(null)
            return
        }

        val before = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        val prefix = before.takeLastWhile { it.isLetter() }
        val bar = if (prefix.isEmpty()) {
            Words.Bar("", predictions(before))
        } else {
            Words.bar(prefix, lastLetterMode == Mode.RU)
        }

        val typed = suggestChips[0]
        typed.text = bar.typed
        typed.setTextColor(MUTED)
        typed.setOnClickListener(if (prefix.isEmpty()) null else View.OnClickListener { acceptTyped(prefix) })

        for (i in 1..2) {
            val chip = suggestChips[i]
            val cand = bar.candidates.getOrNull(i - 1)
            chip.text = cand ?: ""
            chip.setTextColor(if (i == 1) CYAN else TEXT)
            chip.setOnClickListener(
                if (cand == null) null else View.OnClickListener { acceptCandidate(prefix, cand) }
            )
        }
    }

    /** Предсказание следующего слова: только если курсор стоит сразу после предыдущего слова и пробела. */
    private fun predictions(before: String): List<String> {
        if (!before.endsWith(" ") || prevWord.isEmpty()) return emptyList()
        val last = before.trimEnd(' ').takeLastWhile { it.isLetter() }.lowercase()
        return if (last == prevWord) Words.predict(prevWord) else emptyList()
    }

    /** Запоминает законченное слово: пара «предыдущее → это» пойдёт в предсказания. */
    private fun recordWord(w: String) {
        if (w.isEmpty()) return
        Words.learnPair(prevWord, w)
        prevWord = w.lowercase()
    }

    private fun lastWordBefore(): String =
        currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()?.takeLastWhile { it.isLetter() } ?: ""

    /** Оставляет набранное слово как есть и запоминает его. */
    private fun acceptTyped(prefix: String) {
        Words.learn(prefix)
        recordWord(prefix)
        currentInputConnection?.deleteSurroundingText(prefix.length, 0)
        commit("$prefix ")
    }

    private fun acceptCandidate(prefix: String, word: String) {
        currentInputConnection?.deleteSurroundingText(prefix.length, 0)
        recordWord(word)
        commit("$word ")
    }

    /** Возвращает исходное слово вместо исправленного и запоминает его. */
    private fun applyHint(h: Hint) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(h.beforeLen, h.afterLen)
        recentFixes.remove(h.fixed.lowercase())
        Words.learn(h.original)
        activeHint = null
        recordWord(h.original)
        commit(h.original)
    }

    // ---------- Буфер обмена ----------

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
                background = roundRect(Appearance.keyFill(Prefs.keyAlpha(this@KlavService)), if (clip.pinned) PINK else STROKE)
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
                        showPanel(Panel.KEYS)
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

    /** Высота клавиши растёт вместе с размером букв, чтобы крупный текст не обрезался. */
    private fun keyHeightDp(): Int = Prefs.keyH(this) + maxOf(0, Prefs.keySp(this) - 17) * 2

    private fun rebuildKeys() {
        keysArea.removeAllViews()
        letterViews.clear()
        val rows = when (mode) {
            Mode.RU -> RU_ROWS
            Mode.EN -> EN_ROWS
            Mode.SYM -> SYM_ROWS
        }
        // Ряд цифр над буквами. В режиме символов цифры уже есть.
        if (Prefs.numRow(this) && mode != Mode.SYM) {
            val digits = row()
            "1234567890".forEach { d ->
                digits.addView(key(d.toString()) { onChar(d.toString()) })
            }
            keysArea.addView(digits)
        }
        rows.forEachIndexed { index, letters ->
            val line = row()
            keysArea.addView(line)
            if (index == 2 && mode != Mode.SYM) {
                line.addView(key(if (shift) "⬆" else "⇧", 1.5f, active = shift) { toggleShift() })
            }
            letters.forEach { ch ->
                val c = ch.toString()
                if (mode == Mode.SYM) {
                    line.addView(key(c) { onChar(c) })
                } else {
                    val v = key(if (shift) c.uppercase() else c) { }
                    v.tag = c
                    v.setOnTouchListener(letterTouch)
                    letterViews.add(v)
                    line.addView(v)
                }
            }
            if (index == 2) {
                line.addView(key("⌫", 1.5f) { }.also { it.setOnTouchListener(deleteTouch) })
            }
        }
        keysArea.addView(bottomRow())
        updateSuggestions()
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
        line.addView(key("😀", 1f) { togglePanel(Panel.EMOJI) })
        line.addView(key(",", 0.8f) { punct(",") })
        line.addView(
            key(if (lastLetterMode == Mode.RU) "пробел" else "space", 3.2f) { }
                .also { it.setOnTouchListener(spaceTouch) }
        )
        // Долгое нажатие на точку открывает панель знаков препинания.
        line.addView(key(".", 0.8f, onLong = { showPanel(Panel.PUNCT) }) { punct(".") })
        line.addView(key("⏎", 1.6f, active = true) { enter() })
        return line
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun key(
        label: String,
        weight: Float = 1f,
        active: Boolean = false,
        onLong: (() -> Unit)? = null,
        onTap: () -> Unit
    ): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.keySp(this@KlavService).toFloat())
            setTextColor(if (active) BG else TEXT)
            background = roundRect(
                if (active) CYAN else Appearance.keyFill(Prefs.keyAlpha(this@KlavService)),
                if (active) CYAN else STROKE
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(keyHeightDp()), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) feedback(v)
                false
            }
            setOnClickListener { onTap() }
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }

    /** Показывает до трёх вариантов буквы в строке подсказок. Нажатие на вариант вставляет его. */
    private fun showVariants(variants: List<String>) {
        if (!::suggestChips.isInitialized) return
        buzz()
        suggestBar.visibility = View.VISIBLE
        val shown = variants.take(suggestChips.size)
        suggestChips.forEachIndexed { i, chip ->
            val v = shown.getOrNull(i)
            chip.text = v?.let { if (shift) it.uppercase() else it } ?: ""
            chip.setTextColor(if (i == 0) CYAN else TEXT)
            chip.setOnClickListener(
                if (v == null) null else View.OnClickListener { commit(if (shift) v.uppercase() else v) }
            )
        }
    }

    /** Буква, над которой сейчас палец (для свайпа). */
    private fun letterAt(x: Float, y: Float): String? {
        val loc = IntArray(2)
        for (v in letterViews) {
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height) {
                return v.tag as? String
            }
        }
        return null
    }

    private fun roundRect(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    // ---------- Отклик: звук, вибрация, глитч ----------

    private fun feedback(v: View) {
        scheduleIdle()
        if (Prefs.sound(this)) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 10)
        buzz()
        glitch(v)
    }

    private fun buzz() {
        if (!Prefs.haptic(this)) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(12L)
        }
    }

    /** Глитч при нажатии, если он включён в настройках. */
    private fun glitch(v: View) {
        flashKey(v)
    }

    /** Короткий сдвиг клавиши и розовая тень. */
    private fun flashKey(v: View) {
        // Вспышка холодного флюорита: контур и свечение клавиши на короткое время становятся голубыми.
        val tv = v as? TextView ?: return
        val bg = tv.background as? GradientDrawable
        tv.animate().cancel()
        if (Prefs.glitch(this)) {
            v.translationX = dp(2).toFloat()
            v.animate().translationX(0f).setDuration(120).start()
        }
        bg?.setStroke(dp(2), CYAN)
        tv.setShadowLayer(dp(10).toFloat(), 0f, 0f, CYAN)
        tv.postDelayed({
            tv.setShadowLayer(0f, 0f, 0f, 0)
            bg?.setStroke(dp(1), STROKE)
        }, 180)
    }

    // ---------- Idle-анимация ----------

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        if (!Prefs.idleAnim(this)) return
        handler.postDelayed(idleRunnable, IDLE_MIN_MS + Random.nextLong(IDLE_RANGE_MS))
    }

    /** Несколько коротких сбоев: дёргаются клавиши, сдвигается ряд и мигает заголовок. */
    private fun playIdleGlitch() {
        if (panel != Panel.KEYS || !::keysArea.isInitialized) return
        val keys = ArrayList<TextView>()
        collectTextViews(keysArea, keys)
        if (keys.isEmpty()) return
        repeat(3) { i ->
            handler.postDelayed({
                flashKey(keys.random())
                val shiftPx = if (i % 2 == 0) dp(3) else -dp(3)
                keysArea.translationX = shiftPx.toFloat()
                keysArea.animate().translationX(0f).setDuration(70).start()
                titleView.animate().alpha(0.3f).setDuration(60).withEndAction {
                    titleView.animate().alpha(1f).setDuration(120).start()
                }.start()
            }, i * 110L)
        }
    }

    private fun collectTextViews(v: View, out: MutableList<TextView>) {
        if (v is TextView && v.text.isNotEmpty()) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectTextViews(v.getChildAt(i), out)
        }
    }

    // ---------- Ввод текста ----------

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
        val upper = shift || autoCapNow()
        commit(if (upper) c.uppercase() else c)
        if (shift) {
            shift = false
            rebuildKeys()
        }
    }

    /**
     * Свайп: слово выбирается по пути пальца. Заглавная ставится так же, как при обычном наборе.
     */
    private fun swipeCommit(path: String) {
        val word = Words.swipe(path, lastLetterMode == Mode.RU) ?: return
        val upper = shift || autoCapNow()
        if (shift) {
            shift = false
            rebuildKeys()
        }
        val out = if (upper) word.replaceFirstChar { it.uppercase() } else word
        Words.learn(out)
        recordWord(out)
        commit("$out ")
    }

    /**
     * Заглавная нужна в начале текста и сразу после точки, восклицательного или вопросительного знака,
     * с пробелом или без него.
     */
    private fun autoCapNow(): Boolean {
        if (!Prefs.autoCap(this)) return false
        val before = currentInputConnection?.getTextBeforeCursor(4, 0)?.toString() ?: return false
        if (before.isEmpty() || before.endsWith("\n")) return true
        val trimmed = before.trimEnd(' ')
        return trimmed.isNotEmpty() && trimmed.last() in ".!?"
    }

    /** Знаки препинания: сначала автозамена последнего слова, потом сам знак. */
    private fun punct(c: String) {
        autoCorrectWord()
        recordWord(lastWordBefore())
        commit(c)
        if (c == "." || c == "!" || c == "?") prevWord = ""
    }

    private fun spaceCommit() {
        val now = System.currentTimeMillis()
        val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val doubleTap = Prefs.doubleSpace(this) && now - lastSpaceAt < 400L
        if (doubleTap && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            commit(". ")
            lastSpaceAt = 0L
            prevWord = ""
        } else {
            val fixed = autoCorrectWord()
            recordWord(lastWordBefore())
            commit(" ")
            lastAuto = fixed
            lastSpaceAt = now
        }
    }

    /**
     * Заменяет последнее слово, если автозамена уверена.
     * Возвращает (что было набрано, на что заменили) или null.
     */
    private fun autoCorrectWord(): Pair<String, String>? {
        if (!Prefs.autoCorrect(this)) return null
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return null
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.isEmpty()) return null
        val prior = before.dropLast(word.length).trimEnd(' ')
        val sentenceStart = prior.isEmpty() || prior.last() in ".!?\n"
        val fix = Words.correction(word, sentenceStart) ?: return null
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fix, 1)
        recentFixes[fix.lowercase()] = word
        if (recentFixes.size > 100) recentFixes.remove(recentFixes.keys.first())
        return Pair(word, fix)
    }

    private fun commit(s: String) {
        lastAuto = null
        activeHint = null
        lastEditAt = System.currentTimeMillis()
        currentInputConnection?.commitText(s, 1)
        updateSuggestions()
    }

    /**
     * Backspace сразу после автозамены возвращает набранное слово и запоминает его:
     * такое слово больше не будет исправляться.
     */
    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val undo = lastAuto
        lastAuto = null
        activeHint = null
        lastEditAt = System.currentTimeMillis()
        if (undo != null) {
            val fixedWithSpace = undo.second + " "
            if (ic.getTextBeforeCursor(fixedWithSpace.length, 0)?.toString() == fixedWithSpace) {
                ic.deleteSurroundingText(fixedWithSpace.length, 0)
                ic.commitText(undo.first, 1)
                recentFixes.remove(undo.second.lowercase())
                Words.learn(undo.first)
                updateSuggestions()
                return
            }
        }
        ic.deleteSurroundingText(1, 0)
        updateSuggestions()
    }

    private fun enter() {
        autoCorrectWord()
        prevWord = ""
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        lastEditAt = System.currentTimeMillis()
        if (noEnterAction || action == EditorInfo.IME_ACTION_NONE) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(action)
        }
        updateSuggestions()
    }

    // ---------- Утилиты ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val STICKER_DIR = "stickers"

        private const val LONG_PRESS_MS = 450L

        /** Варианты букв при долгом нажатии, самые частые первыми. */
        private val ALTS: Map<String, List<String>> = mapOf(
            "е" to listOf("ё"), "ь" to listOf("ъ"),
            "a" to listOf("à", "á", "ä"), "e" to listOf("é", "è", "ê"), "i" to listOf("í", "ì", "ï"),
            "o" to listOf("ó", "ö", "ô"), "u" to listOf("ú", "ü", "ù"), "c" to listOf("ç"),
            "n" to listOf("ñ"), "s" to listOf("ß", "ś"), "y" to listOf("ÿ")
        )

        private const val IDLE_MIN_MS = 5000L
        private const val IDLE_RANGE_MS = 4000L

        private val RU_ROWS = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val SYM_ROWS = listOf("1234567890", "@#\$_&-+()/", "*\"':;!?")

        private val PUNCT_MARKS = listOf(
            ".", ",", "!", "?", ":", ";",
            "-", "—", "…", "\"", "'", "«",
            "»", "(", ")", "@", "#", "/",
            "&", "%", "*", "+", "=", "№"
        )

        /** Вкладки эмодзи: значок и смайлики через пробел. Первая вкладка — недавние. */
        /** Логотип AV из символов ASCII (# + .), вставляется как обычный текст. */
        private val AV_ART = listOf(
            "   ..                              ..",
            "    .#+                          +#.",
            "      ###.                    .###",
            " .++   +###.                 ####   .+.",
            "   .##+ .###.  ### ###     .###. .##+",
            "     +###+### ####+.##+   .###+###+",
            "       ##### ###### ###  .#######",
            "        .#+.### .##+.###.###+##.",
            "          .### .#### ###### #+",
            "         .###    .##+.###+.#",
            "        .#+.  .   ### ##...",
            "                  .###",
            "                   ###",
            "                   .+"
        )

        private val EMOJI_TABS = listOf(
            "🕘" to "",
            "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😎 🤔 😐 😴 😭 😡 🥺 😱 🤯 🥳",
            "👋" to "👍 👎 👌 ✌️ 🤞 🤘 👏 🙌 🙏 👋 💪 🫶 ✋ 🤝 👀 🧠 💅",
            "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦄 🐝 🦋 🐢 🐙 🐬",
            "🍎" to "🍏 🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥝 🍅 🥑 🍕 🍔 🍟 🌭 🍿 🍩 🍪 🎂 🍰 🍫 ☕ 🍵 🍺",
            "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🎱 🏓 🥊 🎮 🎯 🎲 🎳 🏆 🥇 🎨 🎵 🎸 🎧 🎬 📷 🎤 🚴 🏃 🏊",
            "❤️" to "AV ❤️ 🧡 💛 💚 💙 💜 🖤 💔 ✨ ⭐ 🔥 💥 💫 🌈 ☀️ 🌙 ⚡ ❄️ 💧 🌸 🌹 🎉 🎁 💡 📌 📎 ✅ ❌ ⚠️ 💯",
            // Последняя вкладка — стикеры Авроры (см. renderEmoji).
            "🦊" to "STICKERS"
        )
    }
}
