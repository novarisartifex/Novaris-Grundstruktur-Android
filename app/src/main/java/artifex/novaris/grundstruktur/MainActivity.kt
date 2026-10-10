package artifex.novaris.grundstruktur

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val DATA_MANIFEST = "https://raw.githubusercontent.com/novarisartifex/Novaris-Grundstruktur-Data/main/manifest.json"
private const val APK_RELEASE = "https://api.github.com/repos/novarisartifex/Novaris-Grundstruktur-Android/releases/latest"
private val Gold = Color(0xFFD8B55B)
private val Night = Color(0xFF070A12)
private val Panel = Color(0xFF111827)

private class ContentStore(activity: Activity): SQLiteOpenHelper(activity, "novaris.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS entries (section TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, detail TEXT NOT NULL, PRIMARY KEY(section,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun version(): Long = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key='data_version'", null).use { if(it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }
    fun hasShardedData(): Boolean = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key='data_source'", null).use {
        it.moveToFirst() && it.getString(0) == "sharded"
    }
    fun install(data: JSONObject, source: String = "seed") {
        val schema = data.optInt("schema_version")
        val version = data.optLong("data_version")
        require(schema == 1 && version > 0) { "Unsupported data format" }
        val sections = listOf("people","districts","scenes","world")
        val parsed = mutableListOf<List<String>>()
        for (section in sections) {
            val array = data.optJSONArray(section) ?: throw IllegalArgumentException("Missing $section")
            require(array.length() <= 50000) { "Too many entries" }
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.getString("id").trim()
                val title = item.optString("name").ifBlank { item.optString("title") }.trim()
                require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")) && title.isNotEmpty()) { "Invalid entry" }
                val category = item.optString("classification").ifBlank { item.optString("category").ifBlank { item.optString("lead") } }
                val detail = item.optString("description").ifBlank { item.optString("story").ifBlank { item.optString("summary") } }
                parsed.add(listOf(section,id,title,category,detail))
            }
        }
        require(parsed.isNotEmpty()) { "Empty dataset" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries",null,null)
            val insert = db.compileStatement("INSERT INTO entries(section,id,title,category,detail) VALUES(?,?,?,?,?)")
            for (record in parsed) {
                insert.clearBindings()
                record.forEachIndexed { index, value -> insert.bindString(index+1,value) }
                insert.executeInsert()
            }
            db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('data_version',?)",arrayOf(version.toString()))
            db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('data_source',?)",arrayOf(source))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun installSharded(directory: java.io.File, manifest: JSONObject) {
        val sections = listOf("people", "districts", "scenes", "world")
        val arrays = sections.associateWith { JSONArray() }
        val files = manifest.getJSONArray("files")
        for (i in 0 until files.length()) {
            val path = files.getJSONObject(i).getString("path")
            val section = sections.firstOrNull { path.startsWith("data/$it/") && path.endsWith(".json") } ?: continue
            val item = JSONObject(java.io.File(directory, path).readText())
            arrays.getValue(section).put(item)
        }
        val combined = JSONObject().put("schema_version", 1)
            .put("data_version", manifest.getLong("data_version"))
        for (section in sections) combined.put(section, arrays.getValue(section))
        install(combined, source = "sharded")
    }
    fun entries(section: String): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.rawQuery("SELECT id,title,category,detail FROM entries WHERE section=? ORDER BY title",arrayOf(section)).use {
            while(it.moveToNext()) result.add(Entry(it.getString(0),it.getString(1),it.getString(2),it.getString(3)))
        }
        return result
    }
}
private data class Entry(val id:String,val title:String,val category:String,val detail:String)
private fun getBytes(url: String, max: Int): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout=10000; connection.readTimeout=20000
    connection.setRequestProperty("Accept","application/json")
    try {
        require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
        val out=java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer=ByteArray(8192)
            while(true) {
                val n=input.read(buffer); if(n<0) break
                require(out.size()+n <= max) { "Download exceeds limit" }
                out.write(buffer,0,n)
            }
        }
        return out.toByteArray()
    } finally { connection.disconnect() }
}
private fun checkData(activity: Activity, store: ContentStore): String {
    val updater = ShardedDataUpdater(activity)
    val hadPreviousGeneration = updater.activeManifest() != null
    val beforeVersion = updater.activeManifest()?.optLong("data_version") ?: 0L
    val message = updater.update()
    val manifest = updater.activeManifest() ?: error("No active manifest")
    val activeVersion = manifest.getLong("data_version")
    // The bundled seed and first published dataset may share version 1.
    // Install the complete dataset on first activation even at equal version.
    if (activeVersion > store.version() || !store.hasShardedData()) {
        try {
            store.installSharded(java.io.File(activity.filesDir, "novaris-content/active"), manifest)
        } catch (error: Exception) {
            if (hadPreviousGeneration && activeVersion > beforeVersion) {
                check(updater.rollback()) { "Data installation failed and rollback was unsuccessful: ${error.message}" }
            }
            throw error
        }
    }
    return message
}
private fun checkApk(): Pair<String,String?> {
    val release=JSONObject(String(getBytes(APK_RELEASE,150000),Charsets.UTF_8))
    val tag=release.optString("tag_name")
    val assets=release.optJSONArray("assets") ?: JSONArray()
    var link:String?=null
    for(i in 0 until assets.length()) {
        val a=assets.getJSONObject(i)
        if(a.optString("name").endsWith(".apk")) link=a.optString("browser_download_url")
    }
    return Pair("Neueste GitHub-Version: $tag",link)
}
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store=ContentStore(this)
        val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                Thread {
                    runCatching { NovarisBackup.export(this, uri) }
                        .onSuccess { runOnUiThread { android.widget.Toast.makeText(this, "Novaris-Backup gespeichert", android.widget.Toast.LENGTH_LONG).show() } }
                        .onFailure { error -> runOnUiThread { android.widget.Toast.makeText(this, "Backup-Fehler: ${error.message}", android.widget.Toast.LENGTH_LONG).show() } }
                }.start()
            }
        }
        val backupImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                Thread {
                    runCatching { store.close(); NovarisBackup.restore(this, uri) }
                        .onSuccess { runOnUiThread {
                            android.widget.Toast.makeText(this, "Wiederhergestellt. Novaris bitte neu starten.", android.widget.Toast.LENGTH_LONG).show()
                        } }
                        .onFailure { error -> runOnUiThread { android.widget.Toast.makeText(this, "Wiederherstellung fehlgeschlagen: ${error.message}", android.widget.Toast.LENGTH_LONG).show() } }
                }.start()
            }
        }
        if(store.version()==0L) {
            try { store.install(JSONObject(assets.open("seed.json").bufferedReader().use { it.readText() })) } catch (_:Exception) {}
        }
        setContent {
            var section by remember { mutableStateOf("people") }
            var filter by remember { mutableStateOf("") }
            var selectedCategory by remember { mutableStateOf("Alle") }
            var scenePerson by remember { mutableStateOf("") }
            var sceneLanguage by remember { mutableStateOf("de") }
            var revision by remember { mutableIntStateOf(0) }
            var status by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            val scope= rememberCoroutineScope()
            val tabs=listOf("people" to "Personen","districts" to "Bereiche & Gebiete","scenes" to "Szenen","world" to "Weltstruktur","media" to "Bilder")
            val entries=remember(section,revision) { if(section=="media") emptyList() else store.entries(section) }
            fun englishScene(id: String): JSONObject? {
                val file = java.io.File(filesDir, "novaris-content/active/data/translations/en/scenes/$id.json")
                return if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null
            }
            val categories=remember(entries) { listOf("Alle")+entries.map { it.category }.filter { it.isNotBlank() }.distinct() }
            val filtered=entries.filter {
                (filter.isBlank() || (it.title+" "+it.category+" "+it.detail).contains(filter,true)) &&
                (selectedCategory=="Alle" || it.category==selectedCategory) &&
                (scenePerson.isBlank() || section!="scenes" || it.detail.contains(scenePerson,true))
            }
            MaterialTheme(colorScheme=darkColorScheme(primary=Gold,background=Night,surface=Panel,onSurface=Color(0xFFEEF2F7))) {
                Column(Modifier.fillMaxSize().background(Night)) {
                    Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp)) {
                        Text("NOVARIS",color=Gold,fontWeight=FontWeight.Bold)
                        Text("World, Character & Scene Compendium",style=MaterialTheme.typography.titleMedium)
                        if (section == "scenes") {
                            Row {
                                FilterChip(selected=sceneLanguage=="de",onClick={sceneLanguage="de"},label={Text("Deutsch")})
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected=sceneLanguage=="en",onClick={sceneLanguage="en"},label={Text("English")})
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            tabs.forEach { (key,title) ->
                                TextButton(onClick={section=key;filter="";selectedCategory="Alle";scenePerson=""}) {
                                    Text(title,color=if(section==key) Gold else Color.LightGray)
                                }
                            }
                        }
                    }
                    if (section == "media") {
                        val updater = remember { ShardedDataUpdater(this@MainActivity) }
                        val media = remember(revision) {
                            val manifest = updater.activeManifest()
                            val list = manifest?.optJSONArray("files")
                            if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
                                list.optJSONObject(i)?.optString("path")?.takeIf { it.startsWith("media/") }
                            }
                        }
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("${media.size} lokal gespeicherte Bilder", color = Gold) }
                            items(media) { path ->
                                val bitmap = remember(path, revision) {
                                    updater.mediaFile(path)?.let { BitmapFactory.decodeFile(it.absolutePath) }?.asImageBitmap()
                                }
                                if (bitmap != null) {
                                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                        Column(Modifier.padding(8.dp)) {
                                            Image(bitmap = bitmap, contentDescription = path.substringAfterLast('/'),
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
                                            Text(path.substringAfterLast('/'), color = Gold)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {
                            OutlinedTextField(value=filter,onValueChange={filter=it},label={Text("Suchen")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            if(section=="people" || section=="scenes") {
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    categories.forEach { category ->
                                        FilterChip(selected=selectedCategory==category,onClick={selectedCategory=category},label={Text(category)},modifier=Modifier.padding(end=6.dp))
                                    }
                                }
                            }
                            Text("${filtered.size} Einträge",color=Gold)
                        }
                        items(filtered,key={it.id}) { entry ->
                            var expanded by remember(entry.id) { mutableStateOf(false) }
                            Card(colors=CardDefaults.cardColors(containerColor=Panel),border=BorderStroke(1.dp,Gold.copy(alpha=.25f)),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.clickable { expanded=!expanded }.padding(16.dp)) {
                                    Text(entry.category,color=Gold,style=MaterialTheme.typography.labelMedium)
                                    Text(entry.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                                    if(expanded) {
                                        if (section == "people") {
                                            val updater = remember { ShardedDataUpdater(this@MainActivity) }
                                            val imagePaths = remember(entry.id, revision) {
                                                val file = java.io.File(filesDir, "novaris-content/active/data/people/${entry.id}.json")
                                                if (file.isFile) {
                                                    val html = runCatching { JSONObject(file.readText()).optString("source_html") }.getOrDefault("")
                                                    Regex("media/[a-f0-9]{64}\\.png").findAll(html).map { it.value }.distinct().toList()
                                                } else emptyList()
                                            }
                                            imagePaths.forEach { path ->
                                                val bitmap = remember(path, revision) {
                                                    updater.mediaFile(path)?.let { BitmapFactory.decodeFile(it.absolutePath) }?.asImageBitmap()
                                                }
                                                if (bitmap != null) {
                                                    Image(bitmap = bitmap, contentDescription = entry.title,
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        if (section == "scenes" && sceneLanguage == "en") {
                                            val english = remember(entry.id, revision) { englishScene(entry.id) }
                                            if (english != null) {
                                                Text(english.optString("story"))
                                                Spacer(Modifier.height(8.dp))
                                                TextButton(onClick = {
                                                    runCatching {
                                                        PatreonExporter.share(this@MainActivity, entry.id, english)
                                                    }.onFailure { status = "Patreon export: " + (it.message ?: "failed") }
                                                }) { Text("Patreon ZIP (EN)") }
                                            } else Text("English version not downloaded yet. Update offline data.")
                                        } else Text(entry.detail)
                                        if (section == "scenes") {
                                            val updater = remember { ShardedDataUpdater(this@MainActivity) }
                                            val imagePath = remember(entry.id, revision) {
                                                val file = java.io.File(filesDir, "novaris-content/active/data/scenes/" + entry.id + ".json")
                                                if (file.isFile) {
                                                    runCatching { JSONObject(file.readText()).optString("offline_image") }.getOrDefault("")
                                                } else ""
                                            }
                                            if (imagePath.startsWith("media/")) {
                                                val bitmap = remember(imagePath, revision) {
                                                    updater.mediaFile(imagePath)?.let { BitmapFactory.decodeFile(it.absolutePath) }?.asImageBitmap()
                                                }
                                                if (bitmap != null) {
                                                    Image(bitmap = bitmap, contentDescription = entry.title,
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                                                }
                                            }
                                        }
                                        if(section=="people") TextButton(onClick={section="scenes";filter="";selectedCategory="Alle";scenePerson=entry.title}) { Text("Zur Szenenbibliothek") }
                                    } else Text("⌄",color=Gold)
                                }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("Datensicherung",color=Gold)
                            OutlinedButton(enabled=!busy,onClick={ backupExport.launch("novaris-backup.zip") }) { Text("Backup speichern") }
                            OutlinedButton(enabled=!busy,onClick={ backupImport.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Backup wiederherstellen") }
                            Text("Aktualisierungen",color=Gold)
                            Button(enabled=!busy,onClick={
                                busy=true
                                scope.launch {
                                    status=withContext(Dispatchers.IO) { try { checkData(this@MainActivity, store) } catch(e:Exception) { "Datenupdate: ${e.message ?: "Fehler"}" } }
                                    revision++;busy=false
                                }
                            }) { Text("Daten prüfen") }
                            OutlinedButton(enabled=!busy,onClick={
                                busy=true
                                scope.launch {
                                    status = withContext(Dispatchers.IO) {
                                        try {
                                            val available = AppUpdater.check(this@MainActivity)
                                            if (available == null) "App ist aktuell (Build ${AppUpdater.installedCode(this@MainActivity)})."
                                            else {
                                                val apk = AppUpdater.download(this@MainActivity, available)
                                                withContext(Dispatchers.Main) { AppUpdater.install(this@MainActivity, apk) }
                                                "Update ${available.version} heruntergeladen. Bitte Android-Installation bestätigen."
                                            }
                                        } catch (e: Exception) { "App-Update: ${e.message ?: "Fehler"}" }
                                    }
                                    busy=false
                                }
                            }) { Text("App-Update prüfen") }
                            if(status.isNotBlank()) Text(status)
                            Text("Offline-Datenversion: ${store.version()}",color=Color.LightGray)
                        }
                    }
                    }
                }
            }
        }
    }
}
