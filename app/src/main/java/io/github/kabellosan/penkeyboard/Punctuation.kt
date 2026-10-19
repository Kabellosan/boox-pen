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

    /** Lone marks that are meant as themselves; any other lone non-letter is a comma. */
    private val keep = setOf('.', '!', '?', ':', ';', '-', '–', '—', '&', '@', '#', '%', '+', '=', '*', '"', '(', ')')

    /**
     * True if [read] is one mark ML Kit made of a lone comma: any apostrophe, quote
     * mark, accent, slash or tick (ML Kit has many look-alikes), but not a letter,
     * digit, or a mark from [keep].
     */
    private fun commaLike(read: String): Boolean {
        val t = read.trim()
        return t.length == 1 && !t[0].isLetterOrDigit() && t[0] !in keep
    }

    /**
     * Reads [strokes] as a lone comma or period if it is one: a single stroke much
     * smaller than the user's words ([wordHeight], in the same pixels). A dot is a
     * period, a flat line a hyphen, anything else a comma. Null if this isn't a lone
     * small mark.
     */
    private fun loneMark(strokes: List<Stroke>, wordHeight: Float): String? {
        if (strokes.size != 1) return null
        val s = strokes[0]
        if (s.size == 0) return null
        val w = s.xs.max() - s.xs.min()
        val h = s.ys.max() - s.ys.min()
        if (maxOf(w, h) > 0.45f * wordHeight) return null
        if (maxOf(w, h) < 0.12f * wordHeight) return "."
        if (w > 2.5f * h) return "-"
        return ","
    }

    /** What ML Kit makes of a lone exclamation mark. */
    private val bangLike = setOf("!", "i", "I", "l", "1", "|", "j", "í", "¡")

    /**
     * True if [strokes] is an exclamation mark: two strokes, a line and a dot
     * under it. ("i" has its dot on top, so the dot's place tells them apart.)
     */
    private fun isBang(strokes: List<Stroke>): Boolean {
        if (strokes.size != 2 || strokes.any { it.size == 0 }) return false
        fun extent(s: Stroke) = maxOf(s.xs.max() - s.xs.min(), s.ys.max() - s.ys.min())
        val (dot, line) = strokes.sortedBy(::extent)
        val lineTop = line.ys.min()
        val lineBottom = line.ys.max()
        if (lineBottom - lineTop < 2 * extent(dot)) return false
        return dot.ys.average() > (lineTop + lineBottom) / 2
    }

    /**
     * What to type for [strokes], given ML Kit's best guess [read]. A lone small mark
     * is a comma (or a period, if a dot) unless ML Kit saw a real letter in it, and a
     * lone mark ML Kit reads as a slash, apostrophe or the like is a comma too:
     * those are rare on their own, commas are everywhere. A line with a dot under
     * it is "!", whatever letter ML Kit made of it.
     */
    fun pick(strokes: List<Stroke>, wordHeight: Float, read: String?): String? {
        if (read != null && read.trim() in bangLike && isBang(strokes)) return "!"
        val mark = loneMark(strokes, wordHeight)
        if (mark != null && (read == null || read.trim() in confusable)) return mark
        if (read != null && commaLike(read)) return ","
        return read
    }

    /** Commas first, so a wrong guess is one tap away; shown when [best] is a mark. */
    fun markChoices(best: String?): List<String> =
        if (best == "," || best == "." || best == "-") listOf(",", ".", "-") else emptyList()

    /** Label of the candidate that glues the word just written onto the one before it. */
    const val JOIN = "←join"

    /**
     * How to glue [last] (the text just typed, as it sits before the cursor) onto the
     * word before it, given [before], the text before the cursor ending in [last]:
     * how many characters to delete, and the text to write. Null if there is no
     * space in between to take out.
     */
    fun join(before: String, last: String): Pair<Int, String>? {
        if (!before.endsWith(last)) return null
        val prefix = before.dropLast(last.length)
        val spacesBefore = prefix.length - prefix.trimEnd(' ').length
        val leadInLast = last.length - last.trimStart(' ').length
        if (spacesBefore + leadInLast == 0 || prefix.isBlank()) return null
        return (last.length + spacesBefore) to last.trim() + " "
    }

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
