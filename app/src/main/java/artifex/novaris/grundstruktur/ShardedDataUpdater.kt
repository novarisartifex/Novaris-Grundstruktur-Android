package artifex.novaris.grundstruktur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manifest v1/v2: immutable file hashes; downloads changed files only.
 * Stages and verifies all files before switching the active generation.
 * Never deletes the previously active generation during installation.
 */
internal class ShardedDataUpdater(private val context: Context) {
    private val base = File(context.filesDir, "novaris-content")
    private val remote = "https://raw.githubusercontent.com/novarisartifex/Novaris-Grundstruktur-Data/main/"
    private val maxManifestBytes = 2_000_000
    private val maxAssetBytes = 15_000_000
    private fun bytes(url: String, limit: Int): ByteArray {
        require(url.startsWith(remote)) { "Untrusted origin" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= limit) { "File too large" }
                    output.write(buffer, 0, n)
                }
            }
            return output.toByteArray()
        } finally { connection.disconnect() }
    }
    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }
    private fun validatedPath(value: String): String {
        require(value.matches(Regex("(data|media)/[a-zA-Z0-9_./-]{1,180}")))
        require(!value.split('/').contains("..") && !value.contains("//"))
        return value
    }
    private fun active(): File = File(base, "active")
    private fun safeDelete(file: File) {
        if (file.exists()) require(file.deleteRecursively()) { "Could not remove staging files" }
    }
    fun activeManifest(): JSONObject? = try {
        JSONObject(File(active(), "manifest.json").readText())
    } catch (_: Exception) { null }

    /** Restore last fully validated generation if DB activation fails. */
    fun rollback(): Boolean {
        val previous = File(base, "previous")
        if (!previous.isDirectory) return false
        val current = active()
        val failed = File(base, "failed-" + System.currentTimeMillis())
        if (current.exists() && !current.renameTo(failed)) return false
        if (!previous.renameTo(current)) {
            if (failed.exists()) failed.renameTo(current)
            return false
        }
        return true
    }

    fun mediaFile(relativePath: String): File? {
        val path = validatedPath(relativePath)
        if (!path.startsWith("media/")) return null
        val file = File(active(), path)
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(active().canonicalPath + File.separator) }
    }

    fun update(): String {
        base.mkdirs()
        val raw = bytes(remote + "manifest.json", maxManifestBytes)
        val manifest = JSONObject(String(raw, Charsets.UTF_8))
        require(manifest.optInt("schema_version") in 1..2 && manifest.optString("status") == "published")
        val version = manifest.getLong("data_version")
        val old = activeManifest()
        if (old != null && version < old.optLong("data_version")) error("Remote version is older than installed data")
        if (old != null && version == old.optLong("data_version")) {
            require(old.toString() == manifest.toString()) { "Manifest changed without version increment" }
            return "Daten aktuell (v$version)"
        }
        val list = manifest.getJSONArray("files")
        require(list.length() in 1..10000) { "Invalid manifest size" }
        val staging = File(base, "staging")
        safeDelete(staging)
        require(staging.mkdirs()) { "Could not create staging directory" }
        var reused = 0
        var downloaded = 0
        try {
            val seen = mutableSetOf<String>()
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                val path = validatedPath(entry.getString("path"))
                require(seen.add(path)) { "Duplicate file" }
                val expected = entry.getString("sha256")
                require(expected.matches(Regex("[0-9a-fA-F]{64}")))
                val size = entry.getLong("size_bytes")
                require(size in 0..maxAssetBytes.toLong()) { "Invalid size" }
                val target = File(staging, path)
                require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                target.parentFile?.mkdirs()
                val existing = File(active(), path)
                val data = if (existing.isFile() && existing.length() == size &&
                    hash(existing.readBytes()).equals(expected, true)) {
                    reused++
                    existing.readBytes()
                } else {
                    downloaded++
                    bytes(remote + path, maxAssetBytes)
                }
                require(data.size.toLong() == size && hash(data).equals(expected, true)) {
                    "Checksum mismatch: $path"
                }
                if (path.endsWith(".json")) JSONObject(String(data, Charsets.UTF_8))
                target.writeBytes(data)
            }
            File(staging, "manifest.json").writeBytes(raw)
            val previous = File(base, "previous")
            // Never destroy a rollback generation until the candidate has passed all checks.
            // If a previous generation exists, keep it and stop rather than delete it.
            if (previous.exists()) {
                val archive = File(base, "archive-" + System.currentTimeMillis())
                require(previous.renameTo(archive)) { "Could not preserve old rollback generation" }
            }
            val current = active()
            if (current.exists()) require(current.renameTo(previous)) { "Could not back up active data" }
            if (!staging.renameTo(current)) {
                if (previous.exists()) previous.renameTo(current)
                error("Could not activate update")
            }
            return "Daten v$version installiert ($downloaded geladen, $reused wiederverwendet)"
        } catch (e: Exception) {
            safeDelete(staging)
            throw e
        }
    }
}
