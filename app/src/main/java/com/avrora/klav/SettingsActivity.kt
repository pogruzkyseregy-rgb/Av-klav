package com.avrora.klav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.avrora.klav.Colors.CYAN
import com.avrora.klav.Colors.KEY
import com.avrora.klav.Colors.MUTED
import com.avrora.klav.Colors.TEXT

class SettingsActivity : Activity() {

    companion object {
        private const val REQ_BG = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = ClipStore.prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        col.addView(styledText("✦ AvrorA Klav", 24f, CYAN))
        col.addView(styledText("Клавиатура с буфером обмена и фоном в стиле Авроры.", 14f, MUTED))

        col.addView(button("1. Включить клавиатуру") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(button("2. Выбрать клавиатуру") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        col.addView(button("Выбрать фон") { pickBackground() })
        col.addView(button("Убрать фон") {
            prefs.edit().remove(ClipStore.PREF_BG).apply()
            toast("Фон сброшен")
        })

        val dimLabel = styledText("Затемнение фона: ${prefs.getInt(ClipStore.PREF_DIM, 55)}%", 14f, TEXT)
        col.addView(dimLabel)
        col.addView(
            SeekBar(this).apply {
                max = 90
                progress = prefs.getInt(ClipStore.PREF_DIM, 55)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        prefs.edit().putInt(ClipStore.PREF_DIM, p).apply()
                        dimLabel.text = "Затемнение фона: $p%"
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        col.addView(button("Очистить буфер") {
            ClipStore.clear(this)
            toast("Буфер очищен")
        })

        setContentView(ScrollView(this).apply { addView(col) })
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
            // Провайдер не поддерживает постоянный доступ, фон будет работать до перезапуска.
        }
        ClipStore.prefs(this).edit().putString(ClipStore.PREF_BG, uri.toString()).apply()
        toast("Фон установлен")
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(TEXT)
            setBackgroundColor(KEY)
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
