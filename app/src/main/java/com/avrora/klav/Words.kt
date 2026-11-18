package com.avrora.klav

import android.content.Context
import org.json.JSONObject
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

    /** Автозамена только на частые слова: первые 12 000 в списке. */
    private const val COMMON_RANK = 12000

    /** Насколько лучший вариант должен быть лучше второго, чтобы заменить слово. */
    private const val MARGIN = 0.15

    private val RU_KB = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
    private val EN_KB = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private var appCtx: Context? = null
    private val personal = LinkedHashSet<String>()

    /** Пары слов «предыдущее следующее» → сколько раз пользователь так набирал. */
    private val pairs = HashMap<String, Int>()
    private const val PAIR_KEY = "bigrams"
    private const val PAIR_MAX = 1500

    /** Небольшой встроенный набор частых пар, чтобы подсказки работали с первого запуска. */
    private val BUILTIN: Map<String, String> = mapOf(
        "спасибо" to "большое", "доброе" to "утро", "добрый" to "день", "до" to "свидания",
        "как" to "дела", "не" to "могу", "хорошо" to "бы", "то" to "есть", "так" to "что",
        "очень" to "рад", "спокойной" to "ночи", "всё" to "равно", "что" to "же",
        "thank" to "you", "good" to "morning", "see" to "you", "let" to "me", "how" to "are",
        "i" to "am", "of" to "course", "a" to "lot", "want" to "to", "going" to "to",
        "you" to "know", "is" to "it", "have" to "a", "in" to "the"
    )

    private val ru: Dict by lazy { load(R.raw.words_ru) }
    private val en: Dict by lazy { load(R.raw.words_en) }

    private val adjacency: Map<Char, Set<Char>> by lazy { buildAdjacency() }

    /** Ручные исправления: они важнее словаря. */
    private val BASE_FIXES: Map<String, String> = mapOf(
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

    /**
     * Расширенный список опечаток и вариантов написания. Ключи — ошибочные написания,
     * а не настоящие слова; сокращения вроде «didnt» исправляются до проверки словаря.
     */
    private val FIXES: Map<String, String> = BASE_FIXES + mapOf(
        "пожолуйста" to "пожалуйста", "пажалуйста" to "пожалуйста", "пожалуйтса" to "пожалуйста",
        "пжлст" to "пожалуйста", "спасипо" to "спасибо", "сапсибо" to "спасибо",
        "спасибр" to "спасибо", "спасибка" to "спасибо", "здраствуйте" to "здравствуйте",
        "здраствуй" to "здравствуй", "здравствуте" to "здравствуйте", "конечна" to "конечно",
        "чтоли" to "что ли", "никода" to "никогда", "вобще" to "вообще",
        "вообщее" to "вообще", "вдргу" to "вдруг", "прасто" to "просто",
        "посмотеть" to "посмотреть", "можеть" to "может", "мошет" to "может",
        "очинь" to "очень", "потам" to "потом", "немношко" to "немножко",
        "тожа" to "тоже", "вроди" to "вроде", "сечас" to "сейчас",
        "интирисно" to "интересно", "кватира" to "квартира", "хочеш" to "хочешь",
        "ничево" to "ничего", "харашо" to "хорошо", "харошо" to "хорошо",
        "чуствую" to "чувствую", "завтро" to "завтра", "сигодня" to "сегодня",
        "срачу" to "сразу", "дургой" to "другой", "друго" to "другое",
        "нирмально" to "нормально", "нармально" to "нормально", "нормално" to "нормально",
        "обяза" to "обязательно", "сапсем" to "совсем", "совсим" to "совсем",
        "магу" to "могу", "могю" to "могу", "знаеш" to "знаешь",
        "делоть" to "делать", "сдела" to "сделать", "понемаю" to "понимаю",
        "понимаеш" to "понимаешь", "вместа" to "вместо", "еслли" to "если",
        "приветт" to "привет", "спакойной" to "спокойной", "спокойнойночи" to "спокойной ночи",
        "добрава" to "доброго", "вобшем" to "в общем", "вапше" to "вообще",
        "ваще" to "вообще", "тлф" to "телефон", "фсе" to "все",
        "щя" to "сейчас", "сичасс" to "сейчас", "неть" to "нет",
        "ладна" to "ладно", "спс" to "спасибо", "accomodate" to "accommodate",
        "acheive" to "achieve", "beleive" to "believe", "beleif" to "belief",
        "calender" to "calendar", "cheif" to "chief", "collegue" to "colleague",
        "comming" to "coming", "commitee" to "committee", "concious" to "conscious",
        "dissapoint" to "disappoint", "enviroment" to "environment", "existance" to "existence",
        "foriegn" to "foreign", "goverment" to "government", "gaurd" to "guard",
        "happend" to "happened", "independant" to "independent", "knowlege" to "knowledge",
        "maintainance" to "maintenance", "neccessary" to "necessary", "occassion" to "occasion",
        "parlament" to "parliament", "persistant" to "persistent", "posession" to "possession",
        "priviledge" to "privilege", "recomend" to "recommend", "refered" to "referred",
        "relevent" to "relevant", "sucess" to "success", "suprise" to "surprise",
        "tendancy" to "tendency", "truely" to "truly", "wich" to "which",
        "wether" to "whether", "writting" to "writing", "accross" to "across",
        "agian" to "again", "alredy" to "already", "baout" to "about",
        "buisness" to "business", "chnage" to "change", "diffrent" to "different",
        "eveyone" to "everyone", "exampel" to "example", "frist" to "first",
        "hapen" to "happen", "imediately" to "immediately", "meating" to "meeting",
        "mesage" to "message", "nothng" to "nothing", "peice" to "piece",
        "wirte" to "write", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "couldnt" to "couldn't", "havent" to "haven't", "hasnt" to "hasn't",
        "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "thats" to "that's", "theres" to "there's", "hes" to "he's",
        "shes" to "she's", "theyre" to "they're", "weve" to "we've",
        "recieved" to "received", "occurence" to "occurrence", "begining" to "beginning",
        "beautifull" to "beautiful", "tommorrow" to "tomorrow", "wierd" to "weird",
        "reccomend" to "recommend", "untill" to "until", "thru" to "through",
        "probly" to "probably", "pleese" to "please", "sorri" to "sorry",
        "tiem" to "time", "wnat" to "want", "waht" to "what",
        "dya" to "do you", "didnt" to "didn't", "doesnt" to "doesn't",
        "isnt" to "isn't"
    )

    /** Вызвать один раз при старте сервиса. Загружает личный словарь. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        Prefs.file(app).getStringSet(PERSONAL_KEY, null)?.let { personal.addAll(it) }
        loadPairs(app)
    }

    private fun loadPairs(app: Context) {
        try {
            val s = Prefs.file(app).getString(PAIR_KEY, null) ?: return
            val o = JSONObject(s)
            o.keys().forEach { k -> pairs[k] = o.getInt(k) }
        } catch (e: Exception) {
            pairs.clear()
        }
    }

    /** Запоминает, какое слово пользователь набрал после какого. Старые редкие пары удаляются. */
    fun learnPair(prev: String, next: String) {
        val p = prev.lowercase()
        val n = next.lowercase()
        if (p.isEmpty() || n.isEmpty()) return
        val key = "$p $n"
        pairs[key] = (pairs[key] ?: 0) + 1
        if (pairs.size > PAIR_MAX) {
            pairs.entries.sortedBy { it.value }
                .take(pairs.size - PAIR_MAX * 4 / 5)
                .map { it.key }
                .forEach { pairs.remove(it) }
        }
        val ctx = appCtx ?: return
        Prefs.file(ctx).edit().putString(PAIR_KEY, JSONObject(pairs.toMap()).toString()).apply()
    }

    /** До двух слов, которые чаще всего идут за [prev]: сначала свои, потом встроенные. */
    fun predict(prev: String): List<String> {
        val p = prev.lowercase()
        if (p.isEmpty()) return emptyList()
        val head = "$p "
        val learned = pairs.entries
            .filter { it.key.startsWith(head) }
            .sortedByDescending { it.value }
            .map { it.key.substring(head.length) }
        return (learned + listOfNotNull(BUILTIN[p])).distinct().take(2)
    }

    /**
     * Слово по следу свайпа: первая и последняя буква совпадают, а буквы близки по раскладке.
     * Среди подходящих выигрывает более частое слово. Возвращает null, если ничего не подошло.
     */
    fun swipe(path: String, russian: Boolean): String? {
        if (path.length < 3) return null
        val dict = if (russian) ru else en
        val first = path.first()
        val last = path.last()
        val max = 1.0 + path.length * 0.35
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        val n = dict.words.size.toDouble()
        for ((rank, w) in dict.words.withIndex()) {
            if (w.length < 2 || w.first() != first || w.last() != last) continue
            if (abs(w.length - path.length) > 3) continue
            val d = weightedDistance(w, path, max)
            if (d > max) continue
            val score = d + 0.2 * rank / n
            if (score < bestScore) {
                bestScore = score
                best = w
            }
        }
        return best
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

    /**
     * Исправление для слова или null, если исправлять не надо.
     * Строго: только частые слова, только если лучший вариант явно лучше второго,
     * имена и аббревиатуры не трогаем.
     */
    fun correction(word: String, sentenceStart: Boolean = true): String? {
        val lw = word.lowercase()
        FIXES[lw]?.let { return matchCase(word, it) }
        if (lw.length < 4 || lw.contains('\'') || lw.any { it.isDigit() }) return null
        if (word.drop(1).any { it.isUpperCase() }) return null
        if (word[0].isUpperCase() && !sentenceStart) return null
        if (isKnown(lw)) return null
        val dict = dictFor(lw)
        if (dict === ru) yoIndex[lw]?.let { return matchCase(word, it) }
        val max = if (lw.length <= 5) 1.0 else 1.6
        val ranked = fuzzy(lw, dict, max, 2, COMMON_RANK)
        if (ranked.isEmpty()) return null
        if (ranked.size > 1 && ranked[1].score - ranked[0].score < MARGIN) return null
        return matchCase(word, ranked[0].word)
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
            fuzzy(p, dict, 1.6, 2).forEach { out.add(it.word) }
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

    private class Scored(val score: Double, val word: String)

    /** Ближайшие слова по взвешенному расстоянию; при равенстве выигрывает частотное слово. */
    private fun fuzzy(w: String, dict: Dict, max: Double, count: Int, maxRank: Int = Int.MAX_VALUE): List<Scored> {
        val found = ArrayList<Scored>()
        val n = dict.words.size.toDouble()
        for ((rank, c) in dict.words.withIndex()) {
            if (rank >= maxRank) break
            if (c.length < 2 || abs(c.length - w.length) > max) continue
            val d = weightedDistance(w, c, max)
            if (d == 0.0 || d > max) continue
            found.add(Scored(d + 0.2 * rank / n, c))
        }
        found.sortBy { it.score }
        return found.take(count)
    }

    /** Ё в слове и слова с ё: «еще» → «ещё». Ключ — слово без ё. */
    private val yoIndex: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (w in ru.words) if (w.contains('ё')) m.putIfAbsent(w.replace('ё', 'е'), w)
        m
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
