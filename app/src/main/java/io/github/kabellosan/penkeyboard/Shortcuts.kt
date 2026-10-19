package io.github.kabellosan.penkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a pen shortcut does, stored as a short code ("send", "app:pkg/cls"…). */
data class Action(val code: String) {

    /** The app this action opens, if it opens one. */
    val app: ComponentName? get() =
        if (code.startsWith(APP)) ComponentName.unflattenFromString(code.removePrefix(APP)) else null

    fun label(pm: PackageManager): String = when (this) {
        NONE -> "Nothing"
        SEND -> "Send / Enter"
        LANG -> "Switch DA/EN"
        QUICK_PAD -> "Open Pad Launch"
        DELETE_WORD -> "Delete last word"
        BACK -> "Go back"
        PEN_PAD -> "Open Pen Pad"
        FULL_SCREEN -> "Full-screen writing on/off"
        NEW_LINE -> "New line"
        HIDE_KEYBOARD -> "Hide the keyboard"
        UNDO -> "Undo"
        CURSOR_START -> "Cursor to the start"
        CURSOR_END -> "Cursor to the end"
        SELECT_ALL -> "Select all"
        COPY -> "Copy"
        PASTE -> "Paste"
        HOME -> "Go home"
        RECENTS -> "Recent apps"
        NOTIFICATIONS -> "Notifications"
        SCREENSHOT -> "Screenshot"
        QUICK_NOTE -> "Boox Quick Note"
        PEN_SCROLL -> "Pen scroll on/off"
        SCROLL_UP -> "Scroll up"
        SCROLL_DOWN -> "Scroll down"
        else -> if (BooxTools.isTool(this)) BooxTools.label(this) else app?.let { c ->
            val name = runCatching { pm.getActivityInfo(c, 0).loadLabel(pm).toString() }.getOrNull()
            "Open ${name ?: c.packageName}"
        } ?: code
    }

    companion object {
        private const val APP = "app:"
        val NONE = Action("none")
        val SEND = Action("send")
        val LANG = Action("lang")
        val QUICK_PAD = Action("quickpad")
        val DELETE_WORD = Action("delete_word")
        val BACK = Action("back")
        val PEN_PAD = Action("pen_pad")
        val FULL_SCREEN = Action("full_screen")
        val NEW_LINE = Action("new_line")
        val HIDE_KEYBOARD = Action("hide_keyboard")
        val UNDO = Action("undo")
        val CURSOR_START = Action("cursor_start")
        val CURSOR_END = Action("cursor_end")
        val SELECT_ALL = Action("select_all")
        val COPY = Action("copy")
        val PASTE = Action("paste")
        val HOME = Action("home")
        val RECENTS = Action("recents")
        val NOTIFICATIONS = Action("notifications")
        val SCREENSHOT = Action("screenshot")
        val QUICK_NOTE = Action("quick_note")
        val PEN_SCROLL = Action("pen_scroll")
        val SCROLL_UP = Action("scroll_up")
        val SCROLL_DOWN = Action("scroll_down")
        val BUILT_IN = listOf(
            NONE, SEND, NEW_LINE, LANG, QUICK_PAD, PEN_PAD, FULL_SCREEN, HIDE_KEYBOARD,
            DELETE_WORD, UNDO, CURSOR_START, CURSOR_END, SELECT_ALL, COPY, PASTE,
            BACK, HOME, RECENTS, NOTIFICATIONS, SCREENSHOT, QUICK_NOTE,
            PEN_SCROLL, SCROLL_UP, SCROLL_DOWN,
        )

        /** Edits on the text field, done the same way wherever the shortcut came from. */
        val FIELD = setOf(UNDO, CURSOR_START, CURSOR_END, SELECT_ALL, COPY, PASTE)

        /** Android's own buttons and two-finger scrolling, done by the helper. */
        val GLOBAL = setOf(HOME, RECENTS, NOTIFICATIONS, SCREENSHOT, PEN_SCROLL, SCROLL_UP, SCROLL_DOWN)
        fun openApp(c: ComponentName) = Action(APP + c.flattenToString())

        fun launchIntent(c: ComponentName): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(c)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

/** The fixed things the pen's button can do, each with an assignable action. */
enum class Trigger(val key: String, val label: String, val default: Action) {
    DOUBLE_TAP("double_tap", "Tap twice", Action.NONE),
    TRIPLE_TAP("triple_tap", "Tap three times", Action.LANG),
    LONG_PRESS("long_press", "Press and hold", Action.QUICK_PAD),
    SWIPE("swipe", "Swipe sideways", Action.DELETE_WORD),
    LINE_DOWN("line_down", "Draw a line down", Action.NEW_LINE),
    LINE_UP("line_up", "Draw a line up", Action.SEND),
}

/** The Go 7's page buttons (Boox sends them as volume keys; the keyboard reads them). */
enum class PageButton { UP, DOWN }

/** Pressing a page button on its own, without the pen. */
enum class ButtonPress(val key: String, val label: String, val default: Action) {
    UP_PRESS("up_press", "Page Up press", Action.NONE),
    UP_DOUBLE("up_double", "Page Up double press", Action.NONE),
    DOWN_PRESS("down_press", "Page Down press", Action.NONE),
    DOWN_DOUBLE("down_double", "Page Down double press", Action.NONE),
}

/**
 * Holding a page button and tapping the screen with the pen tip, anywhere outside
 * the keyboard's strip. Seen when the button is let go (that's when Boox sends it).
 */
enum class PageTap(val key: String, val label: String, val default: Action) {
    UP_DOUBLE("up_tap2", "Hold page Up, tap twice", Action.NONE),
    UP_TRIPLE("up_tap3", "Hold page Up, tap three times", Action.NONE),
    DOWN_DOUBLE("down_tap2", "Hold page Down, tap twice", Action.NONE),
    DOWN_TRIPLE("down_tap3", "Hold page Down, tap three times", Action.NONE),
}

/**
 * Eraser taps (the pen's button held) on any screen, outside the keyboard's strip.
 * The helper only learns that the eraser touched down somewhere, not where or for
 * how long, so taps are all it can tell apart. The app underneath gets the taps too.
 */
enum class AnywhereTap(val key: String, val label: String, val default: Action) {
    DOUBLE("anywhere_double", "Tap twice", Action.BACK),
    TRIPLE("anywhere_triple", "Tap three times", Action.QUICK_PAD),
}

/** A shape the user recorded, drawn one or more times, and what it does. */
class Gesture(val id: String, val samples: List<List<Stroke>>, val action: Action)

/** What a drawn shape turned out to be. [score] is the match distance, lower is closer. */
class ShapeMatch(val label: String, val action: Action, val score: Float?)

/**
 * The user's shortcut settings: an action per [Trigger] and their own [Gesture]s.
 * Kept in SharedPreferences, so the keyboard sees changes straight away.
 */
class Shortcuts(context: Context) {

    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    init {
        // "Hold a page button and write" never worked (Boox sends a press only on
        // release); drop what older builds saved for it.
        val old = Trigger.values().flatMap { listOf("up_" + it.key, "down_" + it.key) }.filter(prefs::contains)
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach(::remove) }.apply()
    }

    fun action(t: Trigger): Action = prefs.getString(t.key, null)?.let(::Action) ?: t.default

    fun setAction(t: Trigger, a: Action) = prefs.edit().putString(t.key, a.code).apply()

    fun gestures(): List<Gesture> {
        val json = prefs.getString(GESTURES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val samples = o.getJSONArray("samples")
                Gesture(
                    o.getString("id"),
                    (0 until samples.length()).map { strokesFromJson(samples.getJSONArray(it)) },
                    Action(o.getString("action")),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveGestures(list: List<Gesture>) {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject()
                .put("id", g.id)
                .put("action", g.action.code)
                .put("samples", JSONArray().apply { g.samples.forEach { put(strokesToJson(it)) } }))
        }
        prefs.edit().putString(GESTURES, arr.toString()).apply()
    }

    fun action(b: ButtonPress): Action = prefs.getString(b.key, null)?.let(::Action) ?: b.default

    fun setAction(b: ButtonPress, a: Action) = prefs.edit().putString(b.key, a.code).apply()

    /**
     * True if any shortcut uses [button]. Otherwise the keyboard leaves its presses
     * alone, so reading apps still turn pages with it.
     */
    fun usesPageButton(button: PageButton): Boolean {
        val up = button == PageButton.UP
        val presses = ButtonPress.values().filter { it.key.startsWith("up") == up }.map(::action)
        val taps = PageTap.values().filter { it.key.startsWith("up") == up }.map(::action)
        return (presses + taps).any { it != Action.NONE }
    }

    fun action(t: PageTap): Action = prefs.getString(t.key, null)?.let(::Action) ?: t.default

    fun setAction(t: PageTap, a: Action) = prefs.edit().putString(t.key, a.code).apply()

    fun action(t: AnywhereTap): Action = prefs.getString(t.key, null)?.let(::Action) ?: t.default

    fun setAction(t: AnywhereTap, a: Action) = prefs.edit().putString(t.key, a.code).apply()

    /**
     * Matches a shape drawn with the button held: the user's gestures first,
     * then a plain sideways swipe or a line up or down. Null when nothing fits.
     */
    fun match(strokes: List<Stroke>, pm: PackageManager): ShapeMatch? {
        matchGesture(strokes)?.let { (g, score) -> return ShapeMatch(g.action.label(pm), g.action, score) }
        if (isSwipe(strokes)) return ShapeMatch(Trigger.SWIPE.label, action(Trigger.SWIPE), null)
        line(strokes)?.let { return ShapeMatch(it.label, action(it), null) }
        return null
    }

    /** The closest recorded gesture and its distance, if it is close enough and not ambiguous. */
    fun matchGesture(strokes: List<Stroke>): Pair<Gesture, Float>? {
        val ranked = rank(strokes)
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (best.second > PointCloud.MATCH_MAX) return null
        if (second != null && best.second > second.second * 0.9f) return null
        return best
    }

    /** Every recorded gesture with its distance to [strokes], closest first. */
    fun rank(strokes: List<Stroke>): List<Pair<Gesture, Float>> {
        val cloud = PointCloud.of(strokes) ?: return emptyList()
        return gestures().mapNotNull { g ->
            g.samples.mapNotNull { PointCloud.of(it) }
                .minOfOrNull { PointCloud.distance(cloud, it) }
                ?.let { g to it }
        }.sortedBy { it.second }
    }

    /**
     * One tall, narrow stroke: [Trigger.LINE_DOWN] or [Trigger.LINE_UP], by whether
     * its last points lie below its first. Generous on purpose: only its outline
     * counts (some firmware hands over a path with points repeated), it may lean,
     * and about 8 mm is enough.
     * Drawn with the button held, so over waiting ink it wins over rubbing out.
     */
    fun line(strokes: List<Stroke>): Trigger? {
        val s = strokes.singleOrNull() ?: return null
        if (s.size < 2) return null
        val top = s.ys.min()
        val bottom = s.ys.max()
        val h = bottom - top
        if (h < 50 * density || s.xs.max() - s.xs.min() > 0.6f * h) return null
        val k = maxOf(1, s.size / 5)
        val start = (0 until k).map { s.ys[it] }.average()
        val end = (s.size - k until s.size).map { s.ys[it] }.average()
        return if (end > start) Trigger.LINE_DOWN else Trigger.LINE_UP
    }

    /** One long, flat stroke: a sweep to the side. */
    private fun isSwipe(strokes: List<Stroke>): Boolean {
        val s = strokes.singleOrNull() ?: return false
        if (s.size < 2) return false
        val w = s.xs.max() - s.xs.min()
        val h = s.ys.max() - s.ys.min()
        return w >= 40 * density && w >= 3 * h
    }

    private fun strokesToJson(strokes: List<Stroke>) = JSONArray().apply {
        for (s in strokes) put(JSONArray().apply {
            for (i in 0 until s.size) { put(s.xs[i].toDouble()); put(s.ys[i].toDouble()) }
        })
    }

    private fun strokesFromJson(arr: JSONArray): List<Stroke> = (0 until arr.length()).map { i ->
        val pts = arr.getJSONArray(i)
        Stroke().apply {
            for (j in 0 until pts.length() / 2) add(pts.getDouble(2 * j).toFloat(), pts.getDouble(2 * j + 1).toFloat(), 0L)
        }
    }

    companion object {
        private const val GESTURES = "gestures"
        private const val LANG = "lang"

        /** The language whose best guess is typed, shared by the keyboard and Pen Pad. */
        fun language(context: Context): String =
            context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE).getString(LANG, null) ?: "da"

        fun setLanguage(context: Context, lang: String) =
            context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE).edit().putString(LANG, lang).apply()
    }
}

/**
 * The $P point-cloud recognizer (Vatavu, Anthony, Wobbrock 2012). A drawing is
 * resampled to [N] points, scaled and centred; two drawings are compared by
 * pairing up their points. Stroke order and direction don't matter, so a T
 * drawn top bar first or stem first matches the same template.
 */
object PointCloud {

    const val N = 32

    /**
     * Largest average point distance (in a unit-sized box) still counted as a match.
     * The same shape redrawn usually lands well under 0.1; different shapes above 0.15.
     */
    const val MATCH_MAX = 0.12f

    class Pt(val x: Float, val y: Float, val id: Int)

    /** The normalized cloud for [strokes], or null if they are just a dot. */
    fun of(strokes: List<Stroke>): List<Pt>? {
        val pts = ArrayList<Pt>()
        strokes.forEachIndexed { id, s -> for (i in 0 until s.size) pts += Pt(s.xs[i], s.ys[i], id) }
        if (pts.size < 2) return null
        val minX = pts.minOf { it.x }
        val minY = pts.minOf { it.y }
        val size = max(pts.maxOf { it.x } - minX, pts.maxOf { it.y } - minY)
        if (size < 1f) return null
        val scaled = resample(pts).map { Pt((it.x - minX) / size, (it.y - minY) / size, it.id) }
        val cx = scaled.sumOf { it.x.toDouble() }.toFloat() / N
        val cy = scaled.sumOf { it.y.toDouble() }.toFloat() / N
        return scaled.map { Pt(it.x - cx, it.y - cy, it.id) }
    }

    /** Average distance between paired points of two clouds; 0 is identical. */
    fun distance(a: List<Pt>, b: List<Pt>): Float {
        val step = floor(sqrt(N.toDouble())).toInt()
        var best = Float.MAX_VALUE
        for (start in 0 until N step step) {
            best = min(best, min(cloudDistance(a, b, start), cloudDistance(b, a, start)))
        }
        // The weights in cloudDistance add up to (N + 1) / 2.
        return best / ((N + 1) / 2f)
    }

    private fun cloudDistance(a: List<Pt>, b: List<Pt>, start: Int): Float {
        val matched = BooleanArray(N)
        var sum = 0f
        var i = start
        do {
            var index = -1
            var d = Float.MAX_VALUE
            for (j in 0 until N) {
                if (matched[j]) continue
                val dj = hypot(a[i].x - b[j].x, a[i].y - b[j].y)
                if (dj < d) { d = dj; index = j }
            }
            matched[index] = true
            sum += (1f - ((i - start + N) % N).toFloat() / N) * d
            i = (i + 1) % N
        } while (i != start)
        return sum
    }

    /** Spreads [N] points evenly along the strokes (not across the gaps between them). */
    private fun resample(input: List<Pt>): List<Pt> {
        val pts = ArrayList(input)
        var length = 0f
        for (i in 1 until pts.size) if (pts[i].id == pts[i - 1].id) length += dist(pts[i - 1], pts[i])
        val out = ArrayList<Pt>(N)
        out += pts[0]
        if (length <= 0f) {
            while (out.size < N) out += pts[0]
            return out
        }
        val interval = length / (N - 1)
        var acc = 0f
        var i = 1
        while (i < pts.size && out.size < N) {
            val p = pts[i - 1]
            val q = pts[i]
            if (q.id == p.id) {
                val d = dist(p, q)
                if (d > 0f && acc + d >= interval) {
                    val t = (interval - acc) / d
                    val r = Pt(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y), q.id)
                    out += r
                    pts.add(i, r)
                    acc = 0f
                } else {
                    acc += d
                }
            }
            i++
        }
        while (out.size < N) out += pts.last()
        return out
    }

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
}
