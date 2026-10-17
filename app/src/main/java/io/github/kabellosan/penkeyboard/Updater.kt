package io.github.kabellosan.penkeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs the newest build from GitHub Releases. Every push to main publishes
 * `build-N` with `pen-keyboard-N.apk`, and N is also the app's versionCode, so
 * comparing them tells whether an update is waiting. All builds share one signing
 * key, so Android installs the download over the running app.
 * Blocking calls: run them off the main thread.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/Kabellosan/boox-pen/releases"
    private const val LATEST = "$RELEASES/latest"

    class Release(val build: Long, val apkUrl: String, val notes: String, val title: String)

    fun installedBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun latest(): Release = parse(JSONObject(get(LATEST)))

    /** Pre-releases CI makes for open PRs (tag `prX-build-N`), newest first. */
    fun testBuilds(): List<Release> {
        val all = JSONArray(get("$RELEASES?per_page=30"))
        return (0 until all.length()).map { all.getJSONObject(it) }
            .filter { it.optBoolean("prerelease") }
            .mapNotNull { runCatching { parse(it) }.getOrNull() }
            .sortedByDescending { it.build }
    }

    private fun parse(json: JSONObject): Release {
        val build = json.getString("tag_name").substringAfterLast("build-").toLong()
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .first { it.getString("name").endsWith(".apk") }
        return Release(build, apk.getString("browser_download_url"), json.optString("body"),
            json.optString("name"))
    }

    /** Downloads the APK into the cache, reporting progress as 0..100. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "pen-keyboard-${release.build}.apk")
        val conn = open(release.apkUrl)
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    // Whole tens only: every redraw costs an e-ink refresh.
                    val pct = if (total > 0) (done * 10 / total).toInt() * 10 else -1
                    if (pct != shown) { shown = pct; progress(pct) }
                }
            }
        }
        return file
    }

    /** True once the user has let this app install other apps (asked once per device). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context) = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun get(url: String) = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "pen-keyboard")
            if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode from GitHub")
        }
}
