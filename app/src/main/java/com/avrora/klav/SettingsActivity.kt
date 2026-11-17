package com.avrora.klav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.avrora.klav.Colors.CYAN
import com.avrora.klav.Colors.MUTED
import com.avrora.klav.Colors.STROKE
import com.avrora.klav.Colors.TEXT

class SettingsActivity : Activity() {

    companion object {
        private const val REQ_BG = 42
    }

    private lateinit var preview: FrameLayout
    private lateinit var previewKeys: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        col.addView(styledText("✦ AvrorA Klav", 24f, CYAN))
        col.addView(styledText("Клавиатура с буфером обмена и фоном в стиле Авроры.", 14f, MUTED))

        col.addView(styledText("Предпросмотр", 14f, TEXT))
        preview = FrameLayout(this)
        previewKeys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        preview.addView(previewKeys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        )

        col.addView(button("Выбрать фон") { pickBackground() })
        col.addView(button("Убрать фон") {
            Prefs.setBgUri(this, null)
            refreshPreview()
            toast("Фон сброшен")
        })
        col.addView(
            styledText(
                "Затемнение, прозрачность клавиш, звук, вибрация и глитч настраиваются прямо в клавиатуре: нажми ⚙ на её верхней панели.",
                13f,
                MUTED
            )
        )

        col.addView(button("1. Включить клавиатуру") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(button("2. Выбрать клавиатуру") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        col.addView(button("Очистить буфер") {
            ClipStore.clear(this)
            toast("Буфер очищен")
        })

        setContentView(ScrollView(this).apply { addView(col) })
        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshPreview()
    }

    /** Показывает фон и образец клавиш так, как они будут выглядеть в клавиатуре. */
    private fun refreshPreview() {
        preview.background = Appearance.backgroundDrawable(this, Prefs.bgUri(this), Prefs.dim(this))
        previewKeys.removeAllViews()
        val alpha = Prefs.keyAlpha(this)
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { letters ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            letters.forEach { ch -> line.addView(sampleKey(ch.toString(), alpha)) }
            previewKeys.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun sampleKey(label: String, alpha: Int): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        textSize = 15f
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Appearance.keyFill(alpha))
            setStroke(dp(1), STROKE)
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    private fun pickBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_BG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_BG || resultCode != RESULT_OK) return
        try {
            // Сохраняем доступ к файлу, чтобы фон работал после перезапуска телефона.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Провайдер не даёт постоянный доступ: фон сохранится до перезапуска.
        }
        Prefs.setBgUri(this, uri.toString())
        refreshPreview()
        toast("Фон установлен")
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(TEXT)
            setBackgroundColor(Appearance.keyFill(60))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }

    private fun styledText(s: String, sp: Float, color: Int): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
