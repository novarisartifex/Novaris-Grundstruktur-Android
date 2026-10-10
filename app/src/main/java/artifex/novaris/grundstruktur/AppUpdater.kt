package artifex.novaris.grundstruktur

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Release-based in-app APK updates. Android always confirms installation. */
internal object AppUpdater {
    private const val API = "https://api.github.com/repos/novarisartifex/Novaris-Grundstruktur-Android/releases?per_page=100"
    private const val DOWNLOAD_PREFIX = "https://github.com/novarisartifex/Novaris-Grundstruktur-Android/releases/download/"
    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")
    private val shaPattern = Regex("[a-fA-F0-9]{64}")
    data class Available(val version: String, val url: String, val sha256: String, val code: Long)

    /** Null means a tag is not a numbered Android release (e.g. app-icon-v1). */
    internal fun versionCodeForTag(tag: String): Long? {
        val parts = versionPattern.matchEntire(tag)?.groupValues ?: return null
        val major = parts[1].toLongOrNull() ?: return null
        val minor = parts[2].toLongOrNull() ?: return null
        val patch = parts[3].toLongOrNull() ?: return null
        if (minor !in 0..99 || patch !in 0..99) return null
        return runCatching { Math.addExact(Math.addExact(Math.multiplyExact(major, 10000L), minor * 100L), patch) }.getOrNull()
    }

    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
    }
    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Long = info.versionCode.toLong()

    /** Ignore unrelated releases and only accept a release with a trusted APK and digest. */
    internal fun selectAvailable(releases: JSONArray, currentCode: Long): Available? {
        var best: Available? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            val code = versionCodeForTag(tag) ?: continue
            if (code <= currentCode || (best != null && code <= best.code)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") != "novaris-release.apk") continue
                val url = asset.optString("browser_download_url")
                val digest = asset.optString("digest")
                if (!url.startsWith(DOWNLOAD_PREFIX) || !digest.startsWith("sha256:")) continue
                val sha = digest.removePrefix("sha256:")
                if (!shaPattern.matches(sha)) continue
                best = Available(tag, url, sha.lowercase(), code)
            }
        }
        return best
    }

    fun check(context: Context): Available? {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val releases = try {
            require(connection.responseCode == 200) { "GitHub HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
        } finally { connection.disconnect() }
        return selectAvailable(releases, installedCode(context))
    }

    fun download(context: Context, update: Available): File {
        val folder = File(context.cacheDir, "app_updates").apply { mkdirs() }
        val temp = File(folder, "pending.apk")
        val target = File(folder, "novaris-release.apk")
        val connection = URL(update.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        try {
            require(connection.responseCode == 200) { "Download HTTP ${connection.responseCode}" }
            val md = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        md.update(buffer, 0, n)
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            require(hash == update.sha256) { "APK SHA-256 mismatch" }
            require(temp.length() > 0) { "Empty APK" }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Cannot activate APK" }
            return target
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    fun install(context: Context, apk: File) {
        require(apk.isFile && apk.length() > 0L)
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName))
            context.startActivity(settings)
            error("Bitte Installation aus Novaris erlauben und danach erneut prüfen")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
