package io.github.kabellosan.penkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

/**
 * Boox's own tools (FreeMark, Quick Notes, its screenshot modes) as shortcut actions.
 *
 * Boox doesn't document how its smart button starts them, so this looks inside
 * Boox's system apps for the screens, services and broadcasts that sound like
 * them and lets the user pick one to try. The key log says what happened.
 */
object BooxTools {

    /** One thing in a Boox app that can be started: [kind] is activity, service or receiver. */
    class Tool(val kind: String, val component: ComponentName, val action: String?) {
        val code get() = PREFIX + kind + ":" + component.flattenToShortString() + (action?.let { "#$it" } ?: "")
        val label get() = component.className.substringAfterLast('.') +
            (action?.let { " · " + it.substringAfterLast('.') } ?: "") + " ($kind)"
    }

    private const val PREFIX = "boox:"
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    /** Words in a class or action name that point at the tools worth trying. */
    private val KEYWORDS = Regex("(?i)screen_?shot|capture|free_?mark|quick|scribble|refresh|marquee|scroll_?shot|crop")

    private fun isBoox(pkg: String) = pkg.startsWith("com.onyx") || pkg.startsWith("com.boox") || pkg == "com.android.systemui"

    fun isTool(action: Action) = action.code.startsWith(PREFIX)

    fun parse(action: Action): Tool? {
        val rest = action.code.removePrefix(PREFIX)
        val kind = rest.substringBefore(':')
        val target = rest.substringAfter(':')
        val component = ComponentName.unflattenFromString(target.substringBefore('#')) ?: return null
        return Tool(kind, component, target.substringAfter('#', "").ifEmpty { null })
    }

    fun label(action: Action) = "Boox: " + (parse(action)?.label ?: action.code)

    /** Everything in Boox's apps that sounds like one of its tools and other apps may start. */
    fun find(context: Context): List<Tool> {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        val tools = ArrayList<Tool>()
        for (pkg in pm.getInstalledPackages(0).map { it.packageName }.filter(::isBoox)) {
            val info = runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull() ?: continue
            val actions = runCatching { manifestActions(context, pkg) }.getOrDefault(emptyMap())
            fun add(kind: String, name: String, exported: Boolean) {
                if (!exported) return
                val acts = actions[name].orEmpty().filter { !it.startsWith("android.intent.action.MAIN") }
                val component = ComponentName(pkg, name)
                if (KEYWORDS.containsMatchIn(name)) {
                    if (acts.isEmpty() || kind == "activity") tools += Tool(kind, component, acts.firstOrNull())
                    else acts.forEach { tools += Tool(kind, component, it) }
                } else {
                    acts.filter { KEYWORDS.containsMatchIn(it) }.forEach { tools += Tool(kind, component, it) }
                }
            }
            info.activities?.forEach { add("activity", it.name, it.exported) }
            info.services?.forEach { add("service", it.name, it.exported) }
            info.receivers?.forEach { add("receiver", it.name, it.exported) }
        }
        return tools.distinctBy { it.code }.sortedBy { it.label.lowercase() }
    }

    /** Boox's "new quick note" screen, the one the smart button's Quick Notes opens. */
    fun openQuickNote(context: Context): Boolean {
        quickNote?.let { return run(context, it) }
        val tools = find(context).filter { it.kind == "activity" }
        val name = { t: Tool -> t.component.className.substringAfterLast('.') }
        val tool = tools.firstOrNull { name(it).contains(Regex("(?i)quick.?note.*creat|creat.*quick.?note")) }
            ?: tools.firstOrNull { name(it).contains(Regex("(?i)quick.?note")) }
        if (tool == null) {
            PenButtonProbe.record(context, "Boox Quick Note: not found on this device")
            return false
        }
        return run(context, Action(tool.code).also { quickNote = it })
    }

    /** Found once per start: looking through Boox's apps takes a moment. */
    private var quickNote: Action? = null

    /** Starts [action]'s tool. Says what happened in the key log; false if Android refused. */
    fun run(context: Context, action: Action): Boolean {
        val tool = parse(action) ?: return false
        val intent = (tool.action?.let { Intent(it) } ?: Intent()).setComponent(tool.component)
        val result = runCatching {
            when (tool.kind) {
                "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "service" -> context.startService(intent)
                else -> context.sendBroadcast(intent)
            }
        }
        PenButtonProbe.record(context, "Boox tool ${tool.label}: " +
            (result.exceptionOrNull()?.let { "refused (${it.javaClass.simpleName}: ${it.message})" } ?: "started"))
        return result.isSuccess
    }

    /** Intent-filter actions per component class, read from [pkg]'s manifest. */
    private fun manifestActions(context: Context, pkg: String): Map<String, List<String>> {
        val assets = context.createPackageContext(pkg, 0).assets
        // The package's own manifest is one of the first few asset paths; find it by name.
        val parser = (1..8).firstNotNullOfOrNull { cookie ->
            val p = runCatching { assets.openXmlResourceParser(cookie, "AndroidManifest.xml") }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            var event = p.next()
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = p.next()
            if (event == XmlPullParser.START_TAG && p.getAttributeValue(null, "package") == pkg) p else { p.close(); null }
        } ?: return emptyMap()
        val result = HashMap<String, MutableList<String>>()
        var current: String? = null
        val p = parser
        try {
            while (true) {
                val event = p.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                if (event != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "activity", "activity-alias", "service", "receiver" -> {
                        val name = p.getAttributeValue(ANDROID, "name") ?: continue
                        current = when {
                            name.startsWith(".") -> pkg + name
                            '.' !in name -> "$pkg.$name"
                            else -> name
                        }
                    }
                    "action" -> current?.let { c ->
                        p.getAttributeValue(ANDROID, "name")?.let { result.getOrPut(c) { ArrayList() } += it }
                    }
                    "provider" -> current = null
                }
            }
        } finally {
            p.close()
        }
        return result
    }
}
