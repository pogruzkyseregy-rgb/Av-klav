package com.avrora.klav

import android.content.Context
import kotlin.math.abs
import kotlin.math.min

/**
 * Словарь клавиатуры.
 *
 * - Частотные списки (25 000 слов на язык, по убыванию частоты).
 * - Личный словарь: слова, которые пользователь оставил сам. Их не исправляем и поднимаем в подсказках.
 * - Расстояние с учётом раскладки: опечатка в соседней клавише стоит дешевле промаха.
 * - Строка подсказок: набранное слово, лучшее исправление или дополнение и второй вариант.
 */
internal object Words {

    /** Строка подсказок: набранное слово и до двух вариантов. */
    class Bar(val typed: String, val candidates: List<String>)

    private class Dict(val words: List<String>, val rank: Map<String, Int>)

    private const val PERSONAL_KEY = "personal_words"
    private const val PERSONAL_MAX = 500

    private val RU_KB = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
    private val EN_KB = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private var appCtx: Context? = null
    private val personal = LinkedHashSet<String>()

    private val ru: Dict by lazy { load(R.raw.words_ru) }
    private val en: Dict by lazy { load(R.raw.words_en) }

    private val adjacency: Map<Char, Set<Char>> by lazy { buildAdjacency() }

    /** Ручные исправления: они важнее словаря. */
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

    /** Вызвать один раз при старте сервиса. Загружает личный словарь. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        Prefs.file(app).getStringSet(PERSONAL_KEY, null)?.let { personal.addAll(it) }
    }

    /** Прогрев словарей в фоновом потоке. Не трогает личный словарь. */
    fun warm() {
        ru.words.size
        en.words.size
    }

    /** Запоминает слово, которое пользователь оставил сам. */
    fun learn(word: String) {
        val lw = word.lowercase()
        if (lw.length < 2 || personal.size >= PERSONAL_MAX || !personal.add(lw)) return
        val ctx = appCtx ?: return
        Prefs.file(ctx).edit().putStringSet(PERSONAL_KEY, personal.toSet()).apply()
    }

    /** Исправление для слова или null, если исправлять не надо. */
    fun correction(word: String): String? {
        val lw = word.lowercase()
        FIXES[lw]?.let { return matchCase(word, it) }
        if (lw.length < 4 || lw.contains('\'') || isKnown(lw)) return null
        val max = if (lw.length <= 5) 1.0 else 1.6
        val best = fuzzy(lw, dictFor(lw), max, 1).firstOrNull() ?: return null
        return matchCase(word, best)
    }

    /** Строка подсказок для набранного слова. */
    fun bar(prefix: String, russian: Boolean): Bar {
        val p = prefix.lowercase()
        if (p.isEmpty()) return Bar("", emptyList())
        val dict = if (russian) ru else en
        val out = LinkedHashSet<String>()

        if (p.length >= 4) correction(p)?.let { out.add(it.lowercase()) }
        if (p.length >= 2) {
            personal.filter { it.startsWith(p) && it != p }.take(2).forEach { out.add(it) }
            for (w in dict.words) {
                if (out.size >= 4) break
                if (w.startsWith(p) && w != p) out.add(w)
            }
        }
        if (out.size < 2 && p.length >= 3) {
            fuzzy(p, dict, 1.6, 2).forEach { out.add(it) }
        }
        val list = out.filter { it != p && it.isNotEmpty() }.take(2).map { applyCase(prefix, it) }
        return Bar(prefix, list)
    }

    /** Подгоняет регистр подсказки под начало набранного слова. */
    fun applyCase(prefix: String, word: String): String =
        if (prefix.isNotEmpty() && prefix[0].isUpperCase()) word.replaceFirstChar { it.uppercase() } else word

    private fun isKnown(lw: String) = personal.contains(lw) || dictFor(lw).rank.containsKey(lw)

    private fun dictFor(lw: String): Dict = if (lw.any { it in 'а'..'я' || it == 'ё' }) ru else en

    private fun matchCase(orig: String, fix: String): String =
        if (orig.isNotEmpty() && orig[0].isUpperCase()) fix.replaceFirstChar { it.uppercase() } else fix

    /** Ближайшие слова по взвешенному расстоянию; при равенстве выигрывает частотное слово. */
    private fun fuzzy(w: String, dict: Dict, max: Double, count: Int): List<String> {
        val found = ArrayList<Pair<Double, String>>()
        val n = dict.words.size.toDouble()
        for ((rank, c) in dict.words.withIndex()) {
            if (c.length < 2 || abs(c.length - w.length) > max) continue
            val d = weightedDistance(w, c, max)
            if (d == 0.0 || d > max) continue
            found.add(Pair(d + 0.2 * rank / n, c))
        }
        found.sortBy { it.first }
        return found.take(count).map { it.second }
    }

    private fun cost(a: Char, b: Char): Double = when {
        a == b -> 0.0
        adjacency[a]?.contains(b) == true -> 0.6
        else -> 1.0
    }

    /** Расстояние Дамерау–Левенштейна с весами по раскладке. Возвращает INF, если дальше max. */
    private fun weightedDistance(a: String, b: String, max: Double): Double {
        val n = a.length
        val m = b.length
        if (abs(n - m) > max) return Double.POSITIVE_INFINITY
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) d[i][0] = i.toDouble()
        for (j in 0..m) d[0][j] = j.toDouble()
        for (i in 1..n) {
            var rowMin = Double.POSITIVE_INFINITY
            for (j in 1..m) {
                var v = min(min(d[i - 1][j] + 1.0, d[i][j - 1] + 1.0), d[i - 1][j - 1] + cost(a[i - 1], b[j - 1]))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = min(v, d[i - 2][j - 2] + 0.8)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Double.POSITIVE_INFINITY
        }
        return d[n][m]
    }

    /** Соседние клавиши в каждой раскладке. */
    private fun buildAdjacency(): Map<Char, Set<Char>> {
        val map = HashMap<Char, MutableSet<Char>>()
        for (rows in listOf(RU_KB, EN_KB)) {
            for ((r, row) in rows.withIndex()) {
                for ((c, ch) in row.withIndex()) {
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val other = rows.getOrNull(r + dr)?.getOrNull(c + dc) ?: continue
                        map.getOrPut(ch) { mutableSetOf() }.add(other)
                    }
                }
            }
        }
        return map
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
