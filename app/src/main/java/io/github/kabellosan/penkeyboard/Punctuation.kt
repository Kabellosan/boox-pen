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

    /**
     * Reads [strokes] as a lone comma, period or apostrophe if it is one: a single
     * stroke much smaller than the user's words ([wordHeight], in the same pixels).
     * [baselineY] is the line written on, if the writing area shows one: a mark
     * well above it is an apostrophe. Null if this isn't a lone small mark.
     */
    fun loneMark(strokes: List<Stroke>, wordHeight: Float, baselineY: Float?): String? {
        if (strokes.size != 1) return null
        val s = strokes[0]
        if (s.size == 0) return null
        val w = s.xs.max() - s.xs.min()
        val h = s.ys.max() - s.ys.min()
        if (maxOf(w, h) > 0.45f * wordHeight) return null
        if (maxOf(w, h) < 0.12f * wordHeight) return "."
        if (baselineY != null && s.ys.max() < baselineY - 0.35f * wordHeight) return "'"
        return ","
    }

    /** True if ML Kit's best guess may well be a misread small mark, so the shape decides. */
    fun mayOverride(best: String?) = best == null || best.trim() in confusable

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
