package artifex.novaris.grundstruktur

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Release-based in-app APK updates. Android always confirms installation. */
internal object AppUpdater {
    private const val API = "https://api.github.com/repos/novarisartifex/Novaris-Grundstruktur-Android/releases/latest"
    data class Available(val version: String, val url: String, val sha256: String, val code: Long)
    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
    }
    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Long = info.versionCode.toLong()
    fun check(context: Context): Available? {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val release = connection.useJson()
        val tag = release.getString("tag_name")
        val code = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(tag)
            ?: error("Unsupported release version: $tag")
        val releaseCode = code.groupValues[1].toLong() * 10000 +
            code.groupValues[2].toLong() * 100 + code.groupValues[3].toLong()
        if (releaseCode <= installedCode(context)) return null
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") != "novaris-release.apk") continue
            val url = asset.getString("browser_download_url")
            require(url.startsWith("https://github.com/novarisartifex/Novaris-Grundstruktur-Android/releases/download/"))
            val digest = asset.optString("digest").removePrefix("sha256:")
            require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { "Release APK has no SHA-256 digest" }
            return Available(tag, url, digest.lowercase(), releaseCode)
        }
        error("No novaris-release.apk in latest release")
    }
    fun downloadAndInstall(context: Context, update: Available) {
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
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settings)
                error("Allow updates from Novaris in Android settings, then check again")
            }
            context.startActivity(intent)
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }
    private fun HttpURLConnection.useJson(): JSONObject = try {
        require(responseCode == 200) { "GitHub HTTP $responseCode" }
        inputStream.bufferedReader().use { JSONObject(it.readText()) }
    } finally { disconnect() }
}
