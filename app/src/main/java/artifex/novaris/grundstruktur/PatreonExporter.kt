package artifex.novaris.grundstruktur

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Self-contained, offline Patreon package with the original illustration and English story. */
internal object PatreonExporter {
    fun share(context: Context, sceneId: String, english: JSONObject) {
        require(sceneId.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "Invalid scene ID" }
        require(english.optString("id") == sceneId && english.optString("language") == "en")
        val active = File(context.filesDir, "novaris-content/active")
        val scene = JSONObject(File(active, "data/scenes/$sceneId.json").readText())
        val relativeImage = scene.getString("offline_image")
        require(relativeImage.matches(Regex("media/[a-zA-Z0-9_.-]{1,160}\\.png")))
        val image = File(active, relativeImage)
        require(image.isFile && image.canonicalPath.startsWith(active.canonicalPath + File.separator)) {
            "Offline illustration is missing"
        }
        val title = english.getString("title")
        val story = english.getString("story")
        require(title.isNotBlank() && story.isNotBlank())
        val outputDir = File(context.cacheDir, "patreon_exports").apply { mkdirs() }
        val output = File(outputDir, "$sceneId-en.zip")
        val markdown = "# $title\n\n$story\n"
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("story-en.md"))
            zip.write(markdown.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("illustration.png"))
            image.inputStream().use { input ->
                zip.copyFrom(input)
            }
            zip.closeEntry()
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", output
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Patreon package"))
    }

    private fun ZipOutputStream.copyFrom(input: java.io.InputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            write(buffer, 0, count)
        }
    }
}
