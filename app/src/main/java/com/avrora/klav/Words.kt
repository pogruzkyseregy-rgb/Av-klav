package com.avrora.klav

import android.content.Context
import kotlin.math.abs
import kotlin.math.min

/**
 * Словарь клавиатуры: частотные списки слов (25 000 на язык, по убыванию частоты),
 * исправление опечаток по расстоянию Дамерау–Левенштейна и подсказки по префиксу.
 */
internal object Words {

    private class Dict(val words: List<String>, val rank: Map<String, Int>)

    private var appCtx: Context? = null

    private val ru: Dict by lazy { load(R.raw.words_ru) }
    private val en: Dict by lazy { load(R.raw.words_en) }

    /** Ручные исправления, они важнее словаря. */
    private val FIXES: Map<String, String> = mapOf(
        "спосибо" to "спасибо", "спасибио" to "спасибо", "спс" to "спасибо",
        "пожалуста" to "пожалуйста", "извени" to "извини", "здравствуте" to "здравствуйте",
        "сегодя" to "сегодня", "сегоння" to "сегодня", "сегдня" to "сегодня",
        "завтро" to "завтра", "коненчо" to "конечно", "канечно" to "конечно",
        "вобщем" to "в общем", "вообщем" to "в общем", "врядли" to "вряд ли",
        "потомучто" to "потому что", "тоесть" to "то есть", "сичас" to "сейчас",
        "щяс" to "сейчас", "ещо" to "ещё", "инчае" to "иначе", "ненадо" to "не надо",
        "немогу" to "не могу", "ктото" to "кто-то", "каждй" to "каждый",
        "teh" to "the", "hte" to "the", "adn" to "and", "taht" to "that", "wiht" to "with",
        "recieve" to "receive", "wierd" to "weird", "definately" to "definitely",
        "seperate" to "separate", "occured" to "occurred", "tommorow" to "tomorrow",
        "tomorow" to "tomorrow", "untill" to "until", "becuase" to "because",
        "beacuse" to "because", "thier" to "their", "freind" to "friend", "alot" to "a lot",
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "doesnt" to "doesn't",
        "didnt" to "didn't", "isnt" to "isn't", "youre" to "you're", "im" to "I'm",
        "ive" to "I've", "jsut" to "just", "realy" to "really", "plese" to "please",
        "thnks" to "thanks", "helo" to "hello", "whats" to "what's", "lets" to "let's",
        "i" to "I"
    )

    /** Вызвать один раз при старте сервиса: задаёт контекст для загрузки словарей. */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Исправление для слова или null, если исправлять не надо. */
    fun correction(word: String): String? {
        val lw = word.lowercase()
        FIXES[lw]?.let { return matchCase(word, it) }
        if (lw.length < 3 || lw.contains('\'')) return null
        val dict = if (isRussian(lw)) ru else en
        if (dict.rank.containsKey(lw)) return null
        val maxDist = if (lw.length <= 6) 1 else 2
        val best = nearest(lw, dict, maxDist, 1).firstOrNull() ?: return null
        return matchCase(word, best)
    }

    /**
     * Варианты для строки над клавишами: сначала слова с таким началом (по частоте),
     * а если их мало, похожие слова, чтобы поймать опечатку.
     */
    fun suggestions(prefix: String, russian: Boolean, limit: Int = 3): List<String> {
        val p = prefix.lowercase()
        if (p.length < 2) return emptyList()
        val dict = if (russian) ru else en
        val out = LinkedHashSet<String>()
        for (w in dict.words) {
            if (out.size >= limit) break
            if (w.startsWith(p) && w != p) out.add(w)
        }
        if (out.size < limit && p.length >= 3) {
            for (w in nearest(p, dict, 2, limit)) {
                if (out.size >= limit) break
                if (w != p) out.add(w)
            }
        }
        return out.toList()
    }

    /** Подгоняет регистр подсказки под начало набранного слова. */
    fun applyCase(prefix: String, word: String): String =
        if (prefix.isNotEmpty() && prefix[0].isUpperCase()) word.replaceFirstChar { it.uppercase() } else word

    private fun matchCase(orig: String, fix: String): String =
        if (orig.isNotEmpty() && orig[0].isUpperCase()) fix.replaceFirstChar { it.uppercase() } else fix

    private fun isRussian(w: String) = w.any { it in 'а'..'я' || it == 'ё' }

    /** Ближайшие слова по расстоянию, при равенстве — по частоте. */
    private fun nearest(w: String, dict: Dict, maxDist: Int, count: Int): List<String> {
        val found = ArrayList<Triple<Int, Int, String>>()
        for ((rank, c) in dict.words.withIndex()) {
            if (c.length < 2 || abs(c.length - w.length) > maxDist) continue
            val d = distance(w, c, maxDist)
            if (d in 1..maxDist) found.add(Triple(d, rank, c))
        }
        found.sortWith(compareBy<Triple<Int, Int, String>>({ it.first }, { it.second }))
        return found.take(count).map { it.third }
    }

    /** Расстояние Дамерау–Левенштейна (с перестановкой соседних букв), с ранним выходом. */
    private fun distance(a: String, b: String, max: Int): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > max) return max + 1
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = min(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
        }
        return d[n][m]
    }

    private fun load(resId: Int): Dict {
        val ctx = checkNotNull(appCtx) { "Words.init() не вызван" }
        val words = ctx.resources.openRawResource(resId).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        val rank = HashMap<String, Int>(words.size * 2)
        words.forEachIndexed { i, w -> rank.putIfAbsent(w, i) }
        return Dict(words, rank)
    }
}
