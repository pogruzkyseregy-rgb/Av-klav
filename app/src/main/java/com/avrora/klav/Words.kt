package com.avrora.klav

/** Небольшой встроенный словарь: автозамена опечаток и подсказки слов. */
internal object Words {

    /** Частые опечатки (ключ — в нижнем регистре). */
    private val FIXES: Map<String, String> = mapOf(
        // русский
        "привет" to "привет", "превет" to "привет", "приввет" to "привет",
        "спосибо" to "спасибо", "спасибио" to "спасибо", "спс" to "спасибо",
        "пожалуста" to "пожалуйста", "извени" to "извини", "здравствуте" to "здравствуйте",
        "сегодя" to "сегодня", "сегоння" to "сегодня", "сегдня" to "сегодня",
        "завтро" to "завтра", "коненчо" to "конечно", "канечно" to "конечно",
        "вобщем" to "в общем", "вообщем" to "в общем", "врядли" to "вряд ли",
        "потомучто" to "потому что", "тоесть" to "то есть", "сичас" to "сейчас",
        "щяс" to "сейчас", "ещо" to "ещё", "каждй" to "каждый", "инчае" to "иначе",
        "ненадо" to "не надо", "немогу" to "не могу", "ктото" to "кто-то",
        // английский
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

    private val RU_WORDS = listOf(
        "привет", "пожалуйста", "спасибо", "конечно", "сегодня", "завтра", "вчера", "сейчас",
        "потому", "потому что", "может", "можно", "нужно", "надо", "очень", "просто", "хорошо",
        "нормально", "давай", "всё", "будет", "сделать", "сделаю", "наверное", "понятно",
        "извини", "извините", "здравствуйте", "пока", "когда", "почему", "зачем", "сколько",
        "сообщение", "телефон", "клавиатура", "приложение", "фото", "видео", "вообще", "именно",
        "ладно", "спокойной", "ночи", "доброе", "утро", "день", "вечер", "работа", "дома",
        "друг", "люди", "время", "потом", "тоже", "также", "ещё", "уже", "если", "чтобы",
        "который", "которые", "мне", "тебе", "тебя", "меня", "нам", "вам", "они", "она", "он",
        "мы", "вы", "ты", "это", "эта", "этот", "как", "что", "где", "кто", "там", "тут", "здесь",
        "есть", "нет", "быть", "было", "была", "были", "хочу", "хочешь", "могу", "можешь",
        "знаю", "знаешь", "думаю", "думаешь", "отлично", "круто", "ладно", "вместе", "сначала"
    )

    private val EN_WORDS = listOf(
        "hello", "hi", "thanks", "thank", "please", "yes", "okay", "today", "tomorrow",
        "yesterday", "now", "because", "maybe", "should", "would", "could", "really", "great",
        "good", "morning", "night", "sorry", "message", "phone", "keyboard", "application",
        "photo", "video", "work", "home", "friend", "people", "time", "later", "also", "already",
        "always", "never", "about", "after", "again", "before", "between", "which", "where",
        "what", "when", "why", "how", "who", "want", "need", "know", "think", "going", "doing",
        "something", "nothing", "everything", "anything", "thing", "things", "example",
        "actually", "probably", "sure", "right", "well", "love", "like", "looking", "forward",
        "meeting", "tonight", "weekend", "family", "school", "money", "news", "don't", "can't",
        "I'm", "I'll", "I've", "that's", "it's", "let's", "thank you", "see you"
    )

    /** Возвращает исправление для слова или null, если исправлять нечего. */
    fun correction(word: String): String? {
        val fix = FIXES[word.lowercase()] ?: return null
        if (fix.equals(word, ignoreCase = true)) return null
        return if (word[0].isUpperCase()) fix.replaceFirstChar { it.uppercase() } else fix
    }

    /** Подсказки для начатого слова (не меньше двух букв). */
    fun suggest(prefix: String, russian: Boolean): List<String> {
        val p = prefix.lowercase()
        if (p.length < 2) return emptyList()
        val list = if (russian) RU_WORDS else EN_WORDS
        return list.filter { it.startsWith(p) && it != p }.distinct().take(3)
    }

    /** Подгоняет регистр подсказки под начало набранного слова. */
    fun applyCase(prefix: String, word: String): String =
        if (prefix.isNotEmpty() && prefix[0].isUpperCase()) word.replaceFirstChar { it.uppercase() } else word
}
