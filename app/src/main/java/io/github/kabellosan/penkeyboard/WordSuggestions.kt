package io.github.kabellosan.penkeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Text already in the field that the guesses are about: the word the cursor
 * touches, or the selection. [core] is what gets replaced; [prefix] and [suffix]
 * are marks around it that stay (the "?" of "highlight?").
 *
 * For a word, [before] and [after] are how many characters on each side of the
 * cursor make up prefix + core + suffix. For a selection both are 0: the app
 * replaces the selection itself.
 */
data class FieldText(
    val core: String,
    val prefix: String,
    val suffix: String,
    val before: Int,
    val after: Int,
    val selection: Boolean,
) {
    companion object {
        private fun wordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

        /**
         * The word the cursor is in or right next to, from the text on each side of
         * it. A cursor just after "word " or "word? " counts as touching "word".
         * Null if none.
         */
        fun atCursor(before: String, after: String): FieldText? {
            var b = before.takeLastWhile(::wordChar)
            val a = after.takeWhile(::wordChar)
            var gap = ""
            if (b.isEmpty() && a.isEmpty()) {
                var i = before.length
                if (i > 0 && before[i - 1] == ' ') i--
                while (i > 0 && before[i - 1] in ".,!?;:)…\"”") i--
                b = before.substring(0, i).takeLastWhile(::wordChar)
                gap = before.substring(i)
            }
            val full = b + a
            val cursor = b.length
            var start = 0
            var end = full.length
            while (start < end && !full[start].isLetterOrDigit()) start++
            while (end > start && !full[end - 1].isLetterOrDigit()) end--
            if (start == end) return null
            val lo = minOf(start, cursor)
            val hi = maxOf(end, cursor)
            return FieldText(
                core = full.substring(start, end),
                prefix = full.substring(lo, start),
                suffix = full.substring(end, hi) + gap,
                before = cursor - lo + gap.length,
                after = hi - cursor,
                selection = false,
            )
        }

        /** The highlighted text, less any marks and spaces at its ends. Null if nothing is in it. */
        fun ofSelection(selected: String): FieldText? {
            var start = 0
            var end = selected.length
            while (start < end && !selected[start].isLetterOrDigit()) start++
            while (end > start && !selected[end - 1].isLetterOrDigit()) end--
            if (start == end) return null
            return FieldText(selected.substring(start, end), selected.substring(0, start),
                selected.substring(end), 0, 0, selection = true)
        }

        /** Other capitalisations of [text]: Capitalised and lower case. */
        fun caseVariants(text: String): List<String> {
            val lower = text.lowercase()
            val capital = lower.replaceFirstChar { it.titlecase() }
            return listOf(capital, lower).distinct().filter { it != text }
        }
    }
}

/**
 * What ML Kit offered for words written with the pen, kept after they are typed:
 * tap such a word later and its own handwriting guesses come back. Each guess is
 * filed under every reading, so the list is the same whichever one was picked.
 * A phrase written in one go is also split into words, where the guesses have
 * the same number of words. Kept across sessions, the latest [MAX] words.
 */
class WordMemory(context: Context) {

    private val prefs = context.getSharedPreferences("word_memory", Context.MODE_PRIVATE)
    /** Key (lower case, no edge marks) → language → guesses, oldest first. */
    private val entries = object : LinkedHashMap<String, Map<String, List<String>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, List<String>>>) = size > MAX
    }

    init {
        runCatching {
            val json = JSONObject(prefs.getString(KEY, null) ?: return@runCatching)
            for (key in json.keys()) {
                val langs = json.getJSONObject(key)
                entries[key] = langs.keys().asSequence().associateWith { lang ->
                    val arr = langs.getJSONArray(lang)
                    List(arr.length()) { arr.getString(it) }
                }
            }
        }
    }

    /** Files [results] (each language's guesses, best first) for the words they read. */
    fun remember(results: Map<String, List<String>>) {
        val words = HashMap<Int, MutableMap<String, MutableList<String>>>()
        val phrases = HashMap<String, MutableList<String>>()
        val counts = results.values.flatten().map { tokens(it).size }
        // Split phrases where most guesses agree on the number of words.
        val n = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
        for ((lang, guesses) in results) {
            for (g in guesses) {
                val t = tokens(g)
                if (t.isEmpty()) continue
                if (t.size > 1) phrases.getOrPut(lang) { ArrayList() }.addIfNew(strip(g))
                if (t.size != n) continue
                t.forEachIndexed { i, w -> words.getOrPut(i) { HashMap() }.getOrPut(lang) { ArrayList() }.addIfNew(w) }
            }
        }
        for (group in words.values + listOfNotNull(phrases.takeIf { it.isNotEmpty() })) {
            for (word in group.values.flatten()) entries[key(word)] = group
        }
        save()
    }

    /** The guesses filed for [text], without [text] itself or its other capitalisations. */
    fun lookup(text: String): Map<String, List<String>> {
        val group = entries[key(text)] ?: return emptyMap()
        return group.mapValues { (_, list) -> list.filter { !it.equals(text, ignoreCase = true) } }
    }

    private fun save() {
        val json = JSONObject()
        for ((key, langs) in entries) {
            json.put(key, JSONObject().apply { for ((lang, list) in langs) put(lang, JSONArray(list)) })
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun MutableList<String>.addIfNew(s: String) { if (s.isNotEmpty() && s !in this) add(s) }

    private companion object {
        const val KEY = "words"
        const val MAX = 600

        fun strip(s: String) = s.trim { !it.isLetterOrDigit() }
        fun tokens(s: String) = s.split(Regex("\\s+")).map(::strip).filter { it.isNotEmpty() }
        fun key(s: String) = strip(s).lowercase()
    }
}

/**
 * Android's spell checker, when the device has one: spellings close to a word,
 * or a highlighted sentence with its misspellings fixed. Asks in Danish and
 * English. Boox may ship none; then [ask] answers with nothing.
 */
class SpellCheck(private val context: Context, private val languages: List<String>) {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = HashMap<String, SpellCheckerSession?>()
    private val waiting = HashMap<String, (List<String>) -> Unit>()
    private val pending = HashMap<String, String>()

    private fun session(lang: String): SpellCheckerSession? {
        if (lang in sessions) return sessions[lang]
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        val locale = if (lang == "da") Locale("da", "DK") else Locale("en", "US")
        val s = runCatching {
            tsm?.newSpellCheckerSession(null, locale, listener(lang), false)
        }.getOrNull()
        PenButtonProbe.record(context, "Spell checker ($lang): " + (s?.spellChecker?.packageName ?: "none"))
        sessions[lang] = s
        return s
    }

    private fun listener(lang: String) = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val text = pending.remove(lang) ?: return
            val answer = waiting.remove(lang) ?: return
            answer(read(text, results.orEmpty().filterNotNull()))
        }
    }

    /**
     * Spellings for [text] in [lang], to [answer] on the main thread: for one word,
     * close words; for several, the whole text with each misspelling fixed. Gives
     * up after [TIMEOUT_MS] with nothing.
     */
    fun ask(lang: String, text: String, answer: (List<String>) -> Unit) {
        val s = if (lang in languages) session(lang) else null
        if (s == null) { answer(emptyList()); return }
        waiting.remove(lang)?.invoke(emptyList())
        pending[lang] = text
        waiting[lang] = answer
        main.postDelayed({
            if (pending[lang] === text) { pending.remove(lang); waiting.remove(lang)?.invoke(emptyList()) }
        }, TIMEOUT_MS)
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(text)), 5) }
            .onFailure { pending.remove(lang); waiting.remove(lang)?.invoke(emptyList()) }
    }

    private fun read(text: String, results: List<SentenceSuggestionsInfo>): List<String> {
        val words = !text.any { it.isWhitespace() }
        val fixes = ArrayList<Triple<Int, Int, String>>()
        val out = LinkedHashSet<String>()
        for (sentence in results) {
            for (i in 0 until sentence.suggestionsCount) {
                val info = sentence.getSuggestionsInfoAt(i) ?: continue
                val typo = (info.suggestionsAttributes and
                    (SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS)) != 0
                val found = (0 until maxOf(0, info.suggestionsCount)).map { info.getSuggestionAt(it) }
                if (words) out += found
                else if (typo && found.isNotEmpty()) fixes += Triple(sentence.getOffsetAt(i), sentence.getLengthAt(i), found[0])
            }
        }
        if (!words && fixes.isNotEmpty()) {
            val sb = StringBuilder(text)
            for ((offset, length, fix) in fixes.sortedByDescending { it.first }) {
                if (offset >= 0 && offset + length <= sb.length) sb.replace(offset, offset + length, fix)
            }
            out += sb.toString()
        }
        return out.filter { it.isNotBlank() && it != text }
    }

    fun close() {
        sessions.values.forEach { it?.close() }
        sessions.clear()
    }

    private companion object { const val TIMEOUT_MS = 600L }
}
