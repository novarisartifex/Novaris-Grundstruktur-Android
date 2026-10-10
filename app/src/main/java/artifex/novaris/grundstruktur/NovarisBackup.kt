package artifex.novaris.grundstruktur

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable Novaris backup. SQLite is exported as logical rows, never copied while open. */
internal object NovarisBackup {
    private const val MAX_TOTAL = 300L * 1024 * 1024
    private const val MAX_FILE = 20L * 1024 * 1024
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun database(context: Context): JSONObject {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("novaris.db").path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val out = JSONObject()
            for (table in listOf("entries", "metadata")) {
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val item = JSONArray()
                        for (i in 0 until cursor.columnCount) item.put(cursor.getString(i))
                        rows.put(item)
                    }
                }
                out.put(table, rows)
            }
            return out
        } finally { db.close() }
    }

    fun export(context: Context, uri: Uri) {
        val root = File(context.filesDir, "novaris-content")
        val items = linkedMapOf<String, ByteArray>()
        items["database.json"] = database(context).toString().toByteArray(Charsets.UTF_8)
        if (root.isDirectory) root.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            require(!rel.split('/').contains(".."))
            require(file.length() <= MAX_FILE)
            items["content/$rel"] = file.readBytes()
        }
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs")
        if (prefs.isDirectory) prefs.walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.length() <= MAX_FILE)
            items["preferences/${file.name}"] = file.readBytes()
        }
        require(items.values.sumOf { it.size.toLong() } <= MAX_TOTAL)
        val manifest = JSONObject().put("format", "novaris-backup").put("schema", 1)
        val records = JSONArray()
        for ((name, bytes) in items) records.put(JSONObject().put("path", name).put("size", bytes.size).put("sha256", sha(bytes)))
        manifest.put("files", records)
        context.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
            ZipOutputStream(stream).use { zip ->
                for ((name, bytes) in items) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("backup-manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun load(context: Context, uri: Uri): Map<String, ByteArray> {
        val items = linkedMapOf<String, ByteArray>()
        var total = 0L
        context.contentResolver.openInputStream(uri)!!.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && name.matches(Regex("[A-Za-z0-9_./-]{1,220}")))
                    require(!name.startsWith("/") && !name.split('/').contains("..") && !name.contains("//"))
                    require(!items.containsKey(name))
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16384)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        require(buffer.size().toLong() + n <= MAX_FILE)
                        total += n
                        require(total <= MAX_TOTAL)
                        buffer.write(chunk, 0, n)
                    }
                    items[name] = buffer.toByteArray()
                    zip.closeEntry()
                }
            }
        }
        val manifest = JSONObject(String(items.remove("backup-manifest.json") ?: error("Backup manifest missing"), Charsets.UTF_8))
        require(manifest.getString("format") == "novaris-backup" && manifest.getInt("schema") == 1)
        val listed = manifest.getJSONArray("files")
        require(listed.length() == items.size)
        val seen = mutableSetOf<String>()
        for (i in 0 until listed.length()) {
            val entry = listed.getJSONObject(i)
            val name = entry.getString("path")
            require(seen.add(name))
            val bytes = items[name] ?: error("Missing $name")
            require(bytes.size.toLong() == entry.getLong("size"))
            require(sha(bytes).equals(entry.getString("sha256"), true))
        }
        require(items.containsKey("database.json"))
        return items
    }

    /** Restore only after full validation; stage content and keep old data for rollback. */
    fun restore(context: Context, uri: Uri) {
        val items = load(context, uri)
        val dbJson = JSONObject(String(items.getValue("database.json"), Charsets.UTF_8))
        val entries = dbJson.getJSONArray("entries")
        val metadata = dbJson.getJSONArray("metadata")
        require(entries.length() <= 50000 && metadata.length() <= 50000)
        for (i in 0 until entries.length()) require(entries.getJSONArray(i).length() == 5)
        for (i in 0 until metadata.length()) require(metadata.getJSONArray(i).length() == 2)
        for (name in items.keys.filter { it.startsWith("preferences/") }) require(name.matches(Regex("preferences/[A-Za-z0-9_.-]{1,120}\\.xml")))
        val base = File(context.filesDir, "novaris-content")
        val staging = File(context.filesDir, "novaris-restore-staging")
        require(!staging.exists()) { "Old restore staging exists" }
        require(staging.mkdirs())
        try {
            for ((name, bytes) in items) {
                if (!name.startsWith("content/")) continue
                val file = File(staging, name.removePrefix("content/"))
                require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
            // Persist a recoverable snapshot of the old database before changing files.
            val oldDatabase = database(context).toString().toByteArray(Charsets.UTF_8)
            val dbRecovery = File(context.filesDir, "novaris-restore-previous-database.json")
            require(!dbRecovery.exists()) { "Previous database recovery snapshot exists" }
            dbRecovery.writeBytes(oldDatabase)
            val previous = File(context.filesDir, "novaris-restore-previous")
            require(!previous.exists()) { "Previous restore backup exists" }
            if (base.exists()) require(base.renameTo(previous))
            try {
                require(staging.renameTo(base))
                val db = SQLiteDatabase.openDatabase(context.getDatabasePath("novaris.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    db.beginTransaction()
                    try {
                        db.delete("entries", null, null)
                        db.delete("metadata", null, null)
                        for (i in 0 until entries.length()) {
                            val row = entries.getJSONArray(i)
                            val values = android.content.ContentValues()
                            for ((j, key) in listOf("section", "id", "title", "category", "detail").withIndex()) values.put(key, row.getString(j))
                            require(db.insertOrThrow("entries", null, values) >= 0)
                        }
                        for (i in 0 until metadata.length()) {
                            val row = metadata.getJSONArray(i)
                            val values = android.content.ContentValues()
                            values.put("key", row.getString(0)); values.put("value", row.getString(1))
                            require(db.insertOrThrow("metadata", null, values) >= 0)
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                } finally { db.close() }
            } catch (error: Exception) {
                // Restore logical database snapshot if the content switch or DB update fails.
                runCatching {
                    val snapshot = JSONObject(String(dbRecovery.readBytes(), Charsets.UTF_8))
                    val oldEntries = snapshot.getJSONArray("entries")
                    val oldMetadata = snapshot.getJSONArray("metadata")
                    val db = SQLiteDatabase.openDatabase(context.getDatabasePath("novaris.db").path, null, SQLiteDatabase.OPEN_READWRITE)
                    try {
                        db.beginTransaction()
                        try {
                            db.delete("entries", null, null)
                            db.delete("metadata", null, null)
                            for (i in 0 until oldEntries.length()) {
                                val row = oldEntries.getJSONArray(i)
                                val values = android.content.ContentValues()
                                for ((j, key) in listOf("section", "id", "title", "category", "detail").withIndex()) values.put(key, row.getString(j))
                                db.insertOrThrow("entries", null, values)
                            }
                            for (i in 0 until oldMetadata.length()) {
                                val row = oldMetadata.getJSONArray(i)
                                val values = android.content.ContentValues()
                                values.put("key", row.getString(0))
                                values.put("value", row.getString(1))
                                db.insertOrThrow("metadata", null, values)
                            }
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    } finally { db.close() }
                }.onFailure { recovery -> throw IllegalStateException("Restore failed and database recovery also failed", recovery) }
                base.deleteRecursively()
                if (previous.exists()) check(previous.renameTo(base)) { "Could not recover previous offline content" }
                throw error
            }
            // Keep previous content and DB recovery snapshot until migration is verified.
            // Preferences are deliberately not overwritten while the app process is alive.
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}
