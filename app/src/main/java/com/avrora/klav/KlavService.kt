package com.avrora.klav

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
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
import kotlin.random.Random

class KlavService : InputMethodService() {

    private enum class Mode { RU, EN, SYM }
    private enum class Panel { KEYS, CLIPS, SETTINGS }

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.RU
    private var lastLetterMode = Mode.RU
    private var shift = false
    private var panel = Panel.KEYS
    private var tone: ToneGenerator? = null
    private var lastSpaceAt = 0L

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
        // Словари грузятся в фоне, чтобы первое нажатие не тормозило.
        Thread {
            Words.suggestions("пр", true)
            Words.suggestions("th", false)
        }.start()
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

        root.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        applyAppearance()
        rebuildKeys()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::rootView.isInitialized) return
        applyAppearance()
        showPanel(Panel.KEYS)
        scheduleIdle()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(idleRunnable)
        super.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

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

    // ---------- Верхняя панель ----------

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        titleView = TextView(this).apply {
            text = "✦ AVRORA"
            setTextColor(CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.15f
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
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
        clipButton.setTextColor(if (p == Panel.CLIPS) CYAN else TEXT)
        settingsButton.setTextColor(if (p == Panel.SETTINGS) CYAN else TEXT)
        if (p == Panel.CLIPS) refreshClips()
        updateSuggestions()
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

    // ---------- Подсказки слов ----------

    private fun buildSuggestChip(): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = roundRect(Appearance.keyFill(Prefs.keyAlpha(this@KlavService)), STROKE)
        layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    /** Заполняет три постоянные ячейки; пустые ячейки не меняют высоту строки. */
    private fun updateSuggestions() {
        if (!::suggestChips.isInitialized) return
        val visible = Prefs.suggest(this) && panel == Panel.KEYS
        suggestBar.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) return
        val before = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        val prefix = before.takeLastWhile { it.isLetter() }
        val words = Words.suggestions(prefix, lastLetterMode == Mode.RU)
        suggestChips.forEachIndexed { i, chip ->
            val w = words.getOrNull(i)
            if (w == null) {
                chip.text = ""
                chip.setOnClickListener(null)
            } else {
                val shown = Words.applyCase(prefix, w)
                chip.text = shown
                chip.setOnClickListener { acceptSuggestion(prefix, shown) }
            }
        }
    }

    private fun acceptSuggestion(prefix: String, word: String) {
        currentInputConnection?.deleteSurroundingText(prefix.length, 0)
        commit("$word ")
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
    private fun keyHeightDp(): Int = 44 + maxOf(0, Prefs.keySp(this) - 17) * 2

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
        line.addView(key(",", 0.8f) { punct(",") })
        line.addView(
            key(if (lastLetterMode == Mode.RU) "пробел" else "space", 3.6f) { }
                .also { it.setOnTouchListener(spaceTouch) }
        )
        line.addView(key(".", 0.8f) { punct(".") })
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
        if (Prefs.glitch(this)) flashKey(v)
    }

    /** Короткий сдвиг клавиши и розовая тень. */
    private fun flashKey(v: View) {
        v.animate().cancel()
        v.translationX = dp(2).toFloat()
        v.animate().translationX(0f).setDuration(120).start()
        val tv = v as? TextView ?: return
        tv.setShadowLayer(dp(2).toFloat(), -dp(2).toFloat(), 0f, PINK)
        tv.postDelayed({ tv.setShadowLayer(0f, 0f, 0f, 0) }, 120)
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
        commit(c)
    }

    private fun spaceCommit() {
        val now = System.currentTimeMillis()
        val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val doubleTap = Prefs.doubleSpace(this) && now - lastSpaceAt < 400L
        if (doubleTap && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            commit(". ")
            lastSpaceAt = 0L
        } else {
            autoCorrectWord()
            commit(" ")
            lastSpaceAt = now
        }
    }

    /** Заменяет последнее слово, если оно похоже на опечатку из словаря. */
    private fun autoCorrectWord() {
        if (!Prefs.autoCorrect(this)) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.isEmpty()) return
        val fix = Words.correction(word) ?: return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fix, 1)
    }

    private fun commit(s: String) {
        currentInputConnection?.commitText(s, 1)
        updateSuggestions()
    }

    private fun deleteOne() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        updateSuggestions()
    }

    private fun enter() {
        autoCorrectWord()
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
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

        private const val IDLE_MIN_MS = 5000L
        private const val IDLE_RANGE_MS = 4000L

        private val RU_ROWS = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val SYM_ROWS = listOf("1234567890", "@#\$_&-+()/", "*\"':;!?")
    }
}
