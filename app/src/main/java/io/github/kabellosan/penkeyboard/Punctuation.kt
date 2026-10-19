package io.github.kabellosan.penkeyboard

/**
 * Commas and other small marks. Written on their own, after the word before has
 * already turned into text, a comma is a tiny tick with nothing around it, and
 * ML Kit reads it as ' or 1 or l or / about as often as a comma. Its size and
 * place say much more: small, one stroke, sitting on the line.
 */
object Punctuation {

    /** Marks that stick to the word before them, with no space in between. */
    fun sticks(c: Char) = c in ".,!?;:)…"

    /** What ML Kit makes of a lone comma, period or apostrophe, besides the right answer. */
    private val confusable = setOf(
        ",", ".", "'", "’", "‘", "`", "´", "‚", "/", "\\", "|", "1", "l", "I", "i", "j", "J",
        ")", "(", "-", "_", ";", ":", "!", "\"", "„", "”", "7", "°", "^", "~", "¸",
    )

    /** Lone marks ML Kit reads that are almost always a comma when written on their own. */
    private val commaLike = setOf(
        ",", "'", "’", "‘", "`", "´", "‚", "/", "\\", "|", ")", "(", ";", "\"", "„", "”", "°", "^", "~", "¸",
    )

    /**
     * Reads [strokes] as a lone comma or period if it is one: a single stroke much
     * smaller than the user's words ([wordHeight], in the same pixels). A dot is a
     * period, anything else a comma. Null if this isn't a lone small mark.
     */
    private fun loneMark(strokes: List<Stroke>, wordHeight: Float): String? {
        if (strokes.size != 1) return null
        val s = strokes[0]
        if (s.size == 0) return null
        val w = s.xs.max() - s.xs.min()
        val h = s.ys.max() - s.ys.min()
        if (maxOf(w, h) > 0.45f * wordHeight) return null
        if (maxOf(w, h) < 0.12f * wordHeight) return "."
        return ","
    }

    /**
     * What to type for [strokes], given ML Kit's best guess [read]. A lone small mark
     * is a comma (or a period, if a dot) unless ML Kit saw a real letter in it, and a
     * lone stroke ML Kit reads as a slash, apostrophe or the like is a comma too:
     * those are rare on their own, commas are everywhere.
     */
    fun pick(strokes: List<Stroke>, wordHeight: Float, read: String?): String? {
        val mark = loneMark(strokes, wordHeight)
        if (mark != null && (read == null || read.trim() in confusable)) return mark
        if (strokes.size == 1 && read != null && read.trim() in commaLike) return ","
        return read
    }

    /** Commas first, so a wrong guess is one tap away; shown when [best] is a mark. */
    fun markChoices(best: String?): List<String> =
        if (best == "," || best == ".") listOf(",", ".") else emptyList()

    /**
     * How to put [word] after [pre] (the text before the cursor): how many characters
     * to delete first, and the text to write. Words get a space before them when
     * needed and one after. Punctuation takes the space after the word before it,
     * so "hej ," never happens, and gets its own space after.
     */
    fun place(pre: String, word: String): Pair<Int, String> {
        val w = word.trim().ifEmpty { return 0 to "" }
        if (sticks(w.first())) {
            val drop = pre.length >= 2 && pre.last() == ' ' && !pre[pre.length - 2].isWhitespace()
            return (if (drop) 1 else 0) to "$w "
        }
        val lead = pre.isNotEmpty() && !pre.last().isWhitespace() && pre.last() != '('
        return 0 to (if (lead) " " else "") + w + " "
    }

    /** Height of [strokes] together, top to bottom. */
    fun height(strokes: List<Stroke>): Float {
        val ys = strokes.flatMap { it.ys }
        return if (ys.isEmpty()) 0f else ys.max() - ys.min()
    }
}
