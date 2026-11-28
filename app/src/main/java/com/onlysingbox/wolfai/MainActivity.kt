package com.onlysingbox.wolfai
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.OpenableColumns
import android.provider.CalendarContract
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import android.content.SharedPreferences
import java.util.Locale
import org.json.JSONArray
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

private fun SharedPreferences.safeString(key: String, fallback: String = ""): String =
    runCatching { getString(key, fallback) ?: fallback }.getOrDefault(fallback)

private fun SharedPreferences.safeBoolean(key: String, fallback: Boolean): Boolean =
    runCatching { getBoolean(key, fallback) }.getOrDefault(fallback)

private fun SharedPreferences.safeInt(key: String, fallback: Int): Int =
    runCatching { getInt(key, fallback) }.getOrDefault(fallback)

private inline fun SharedPreferences.safeEdit(update: SharedPreferences.Editor.() -> Unit) {
    runCatching { edit().apply { update() }.apply() }
}

private const val BASE_URL = "https://wolfai.onlysingbox.workers.dev"
private val Navy = Color(0xFF071426)
private val Panel = Color(0xFF10243D)
private val Cyan = Color(0xFF18C8FF)
private val Gold = Color(0xFFFFC928)


private fun decodeXmlText(value: String): String = value
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'")


private fun readPdfText(file: File, context: Context): String {
    PDFBoxResourceLoader.init(context.applicationContext)
    PDDocument.load(file).use { document ->
        if (document.isEncrypted) return "این PDF رمزگذاری شده است و بدون رمز قابل خواندن نیست."
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        return stripper.getText(document).take(12000).ifBlank { "متن قابل استخراج پیدا نشد؛ ممکن است PDF فقط تصویر اسکن‌شده باشد." }
    }
}

private fun ensureOcrModel(context: Context, language: String): File {
    val root = File(context.filesDir, "wolf_ocr")
    val dataDir = File(root, "tessdata")
    if (!dataDir.exists() && !dataDir.mkdirs()) error("ساخت پوشه مدل OCR ممکن نشد")
    val target = File(dataDir, "$language.traineddata")
    if (target.exists() && target.length() > 100_000L) return target

    val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$language.traineddata")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 60000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "WOLF-AI-Android")
    }
    val temporary = File(dataDir, "$language.traineddata.part")
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            error("دریافت مدل OCR $language ناموفق بود: HTTP ${connection.responseCode}")
        }
        connection.inputStream.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (temporary.length() < 100_000L) error("فایل مدل OCR ناقص دریافت شد")
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) error("ذخیره مدل OCR ممکن نشد")
    } finally {
        connection.disconnect()
        if (temporary.exists()) temporary.delete()
    }
    return target
}

private suspend fun readScannedPdfText(file: File, context: Context): String = withContext(Dispatchers.IO) {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    var renderer: PdfRenderer? = null
    val api = TessBaseAPI()
    try {
        val root = File(context.filesDir, "wolf_ocr")
        ensureOcrModel(context, "fas")
        ensureOcrModel(context, "eng")
        if (!api.init(root.absolutePath, "fas+eng")) {
            error("موتور OCR نتوانست مدل‌های فارسی و انگلیسی را بارگذاری کند")
        }
        val pdfRenderer = PdfRenderer(descriptor)
        renderer = pdfRenderer
        val output = StringBuilder()
        val pageLimit = minOf(pdfRenderer.pageCount, 8)
        for (index in 0 until pageLimit) {
            val page = pdfRenderer.openPage(index)
            val scale = minOf(1.7f, 2200f / page.width.toFloat())
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                api.setImage(bitmap)
                val text = api.getUTF8Text()
                if (!text.isNullOrBlank()) {
                    if (output.isNotEmpty()) output.append("\n\n")
                    output.append("--- صفحه ").append(index + 1).append(" ---\n").append(text.trim())
                }
            } finally {
                bitmap.recycle()
                page.close()
            }
            if (output.length >= 12000) break
        }
        if (output.isEmpty()) "در صفحات اسکن‌شده متن قابل تشخیصی پیدا نشد."
        else output.toString().take(12000) + if (pdfRenderer.pageCount > pageLimit) "\n\nفقط ۸ صفحه اول پردازش شد." else ""
    } finally {
        api.recycle()
        renderer?.close()
        descriptor.close()
    }
}

private fun readXlsxText(file: File): String {
    java.util.zip.ZipFile(file).use { zip ->
        val shared = mutableListOf<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            Regex("<si\\b[^>]*>(.*?)</si>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).forEach { item ->
                shared.add(Regex("<t\\b[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(item.groupValues[1]).joinToString("") { decodeXmlText(it.groupValues[1]) })
            }
        }
        val sheets = zip.entries().asSequence().filter { !it.isDirectory && it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.name.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }.toList()
        if (sheets.isEmpty()) return "برگه‌ای برای خواندن در فایل پیدا نشد."
        val output = StringBuilder()
        sheets.forEachIndexed { sheetIndex, entry ->
            val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            if (sheetIndex > 0) output.append("\n")
            output.append("برگه ").append(sheetIndex + 1).append("\n")
            Regex("<row\\b[^>]*>(.*?)</row>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).forEach { row ->
                val cells = Regex("<c\\b([^>]*)>(.*?)</c>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(row.groupValues[1])
                val values = cells.map { cell ->
                    val attrs = cell.groupValues[1]
                    val body = cell.groupValues[2]
                    val type = Regex("""\bt="([^"]+)""").find(attrs)?.groupValues?.get(1)
                    val raw = Regex("<v\\b[^>]*>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL)).find(body)?.groupValues?.get(1)
                    when (type) {
                        "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> Regex("<t\\b[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(body).joinToString("") { decodeXmlText(it.groupValues[1]) }
                        else -> raw?.let(::decodeXmlText).orEmpty()
                    }
                }
                if (values.any { it.isNotBlank() }) output.append(values.joinToString(" | ")).append("\n")
                if (output.length >= 12000) return output.toString().take(12000)
            }
        }
        return output.toString().ifBlank { "محتوای متنی قابل استخراج در این فایل پیدا نشد." }.take(12000)
    }
}

private data class CalendarEvent(val title: String, val begin: Long, val end: Long, val location: String, val description: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var securePrefs: android.content.SharedPreferences

    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val directory = File(filesDir, "wolf_storage")
                directory.mkdirs()
                File(directory, "last_crash.txt").writeText(
                    "WOLF AI startup/runtime crash\nThread: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(error)
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCapture()

        // Keep optional speech-engine startup out of the critical first-frame path.
        // Never let a device-specific Android Keystore failure prevent the app UI from opening.
        securePrefs = try {
            val master = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                "wolf_secure",
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (error: Exception) {
            android.util.Log.e("WOLF_STARTUP", "Encrypted preferences unavailable; using local fallback", error)
            getSharedPreferences("wolf_secure_fallback", MODE_PRIVATE)
        }

        val storageRoot = File(filesDir, "wolf_storage")
        runCatching {
            storageRoot.mkdirs()
            listOf("history", "memory", "notes", "files", "audio").forEach { File(storageRoot, it).mkdirs() }
        }.onFailure { android.util.Log.e("WOLF_STARTUP", "Could not prepare local storage", it) }
        val crashFile = File(storageRoot, "last_crash.txt")
        val previousCrash = runCatching {
            if (crashFile.isFile) {
                val report = crashFile.readText().take(6000)
                crashFile.delete()
                report
            } else ""
        }.getOrDefault("")

        setContent { WolfApp(
            token = securePrefs.safeString("device_token"),
            saveToken = { value -> securePrefs.safeEdit { putString("device_token", value) } },
            send = { text, history, model -> apiChat(securePrefs.safeString("device_token"), text, history, model) },
            createMission = { text -> apiCreateMission(securePrefs.safeString("device_token"), text) },
            listMissions = { apiListMissions(securePrefs.safeString("device_token")) },
            decideMission = { id, decision -> apiMissionDecision(securePrefs.safeString("device_token"), id, decision) },
            prepareMissionStep = { id, step -> apiMissionStep(securePrefs.safeString("device_token"), id, step, "prepare") },
            executeMissionStep = { id, step -> apiMissionStep(securePrefs.safeString("device_token"), id, step, "execute") },
            controlMission = { id, action -> apiMissionControl(securePrefs.safeString("device_token"), id, action) },
            retryMissionStep = { id, step -> apiRetryMissionStep(securePrefs.safeString("device_token"), id, step) },
            setRemoteMaster = { enabled -> apiMaster(securePrefs.safeString("device_token"), enabled) },
            loadProviderStatus = { apiProviderStatus(securePrefs.safeString("device_token")) },
            listMemory = { apiListMemory(securePrefs.safeString("device_token")) },
            deleteMemory = { key -> apiDeleteMemory(securePrefs.safeString("device_token"), key) },
            listArtifacts = { apiListArtifacts(securePrefs.safeString("device_token")) },
            getArtifact = { id -> apiGetArtifact(securePrefs.safeString("device_token"), id) },
            deleteArtifact = { id -> apiDeleteArtifact(securePrefs.safeString("device_token"), id) },
            createArtifact = { title, content -> apiCreateArtifact(securePrefs.safeString("device_token"), title, content) },
            speak = { text -> runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wolf-reply") } },
            stopSpeaking = { runCatching { tts?.stop() } },
            storagePrefs = securePrefs,
            storageRoot = storageRoot,
            historyFile = File(storageRoot, "history/chat.json"),
            loadContacts = { readPhoneContacts() },
            loadCalendarEvents = { readCalendarEvents() },
            startupCrashReport = previousCrash
        ) }

        // Initialize TTS only after Compose has had a chance to draw the first screen.
        window.decorView.post {
            if (!isFinishing && tts == null) {
                runCatching { tts = TextToSpeech(applicationContext, this) }
                    .onFailure { android.util.Log.e("WOLF_STARTUP", "Text-to-speech initialization failed", it) }
            }
        }
    }
    private suspend fun apiProviderStatus(token: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/status").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "دریافت وضعیت موتورها ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiMaster(token: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/master").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("enabled", enabled).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "خطای همگام‌سازی MASTER"))
        } finally { conn.disconnect() }
    }

    private fun findPhoneNumberByName(query: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val contacts = mutableListOf<ContactPhone>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) contacts.add(ContactPhone(cursor.getString(nameCol).orEmpty(), cursor.getString(numberCol).orEmpty()))
        }
        return (ContactResolver.resolve(query, contacts) as? ContactLookup.Match)?.contact?.number
    }

    private suspend fun readPhoneContacts(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) throw SecurityException("مجوز مخاطبین فعال نیست")
        val result = mutableListOf<Pair<String, String>>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use { cursor ->
            val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) { val name = cursor.getString(nameCol).orEmpty(); val number = cursor.getString(numberCol).orEmpty(); if (name.isNotBlank() && number.isNotBlank()) result.add(name to number) }
        }
        result.distinctBy { it.second }.take(2000)
    }
    private suspend fun readCalendarEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("مجوز خواندن تقویم فعال نیست")
        }
        val begin = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val end = System.currentTimeMillis() + 90L * 24L * 60L * 60L * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString()).appendPath(end.toString()).build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION
        )
        val result = mutableListOf<CalendarEvent>()
        contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            val titleCol = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginCol = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endCol = cursor.getColumnIndex(CalendarContract.Instances.END)
            val locationCol = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val descriptionCol = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
            while (cursor.moveToNext() && result.size < 100) {
                val title = cursor.getString(titleCol).orEmpty()
                if (title.isNotBlank()) result.add(CalendarEvent(
                    title,
                    cursor.getLong(beginCol),
                    cursor.getLong(endCol),
                    cursor.getString(locationCol).orEmpty(),
                    cursor.getString(descriptionCol).orEmpty()
                ))
            }
        }
        result
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            runCatching { tts?.language = Locale("fa", "IR") }
                .onFailure { android.util.Log.w("WOLF_TTS", "Persian TTS language setup failed", it) }
        }
    }

    override fun onDestroy() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        super.onDestroy()
    }

    private suspend fun apiListArtifacts(token: String): JSONArray = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/artifacts").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "دریافت اسناد ابری ناموفق بود"))
            obj.optJSONArray("artifacts") ?: JSONArray()
        } finally { conn.disconnect() }
    }

    private suspend fun apiGetArtifact(token: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/artifacts?id=" + java.net.URLEncoder.encode(id, "UTF-8")).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "دریافت سند ناموفق بود"))
            obj.optJSONObject("artifact") ?: throw IllegalStateException("محتوای سند خالی است.")
        } finally { conn.disconnect() }
    }

    private suspend fun apiDeleteArtifact(token: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/artifacts").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "DELETE"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("id", id).put("confirm", true).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "حذف سند ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiCreateArtifact(token: String, title: String, content: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/artifacts").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("title", title).put("content", content).put("kind", "document").put("confirm", true).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "ذخیره سند ابری ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiListMemory(token: String): JSONArray = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/memory").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "دریافت حافظه ناموفق بود"))
            obj.optJSONArray("entries") ?: JSONArray()
        } finally { conn.disconnect() }
    }

    private suspend fun apiDeleteMemory(token: String, key: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/memory").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "DELETE"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("key", key).put("confirm", true).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "حذف حافظه ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiCreateMission(token: String, text: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/missions").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 90000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("text", text).put("input_mode", "text").toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "ساخت مأموریت ناموفق بود"))
            obj.getJSONObject("mission")
        } finally { conn.disconnect() }
    }

    private suspend fun apiMissionDecision(token: String, missionId: String, decision: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/missions/$missionId/decision").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("decision", decision).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "ثبت تصمیم ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiMissionStep(token: String, missionId: String, stepNo: Int, action: String): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = if (action == "prepare") "prepare" else "execute"
        val conn = (URL("$BASE_URL/api/companion/missions/$missionId/steps/$endpoint").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 90000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("step_no", stepNo).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "عملیات مرحله ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiMissionControl(token: String, missionId: String, action: String): JSONObject = withContext(Dispatchers.IO) {
        require(action == "pause" || action == "resume") { "عملیات کنترل مأموریت نامعتبر است." }
        val conn = (URL("$BASE_URL/api/companion/missions/$missionId/control").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("action", action).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "کنترل مأموریت ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiRetryMissionStep(token: String, missionId: String, stepNo: Int): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/missions/$missionId/steps/retry").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("step_no", stepNo).toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val obj = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "تلاش مجدد مرحله ناموفق بود"))
            obj
        } finally { conn.disconnect() }
    }

    private suspend fun apiListMissions(token: String): JSONArray = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/missions").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "دریافت مأموریت‌ها ناموفق بود"))
            obj.optJSONArray("missions") ?: JSONArray()
        } finally { conn.disconnect() }
    }

    private suspend fun apiChat(token: String, text: String, history: List<Pair<String,String>>, model: String): String = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/chat").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { out ->
                val turns = JSONArray()
                history.takeLast(10).forEach { (role, content) -> turns.put(JSONObject().put("role", if (role == "تو") "user" else "assistant").put("content", content)) }
                out.write(JSONObject().put("text", text).put("session_id", "android-main").put("history", turns).put("model", model).toString().toByteArray())
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "خطای ارتباط"))
            obj.getString("reply")
        } finally { conn.disconnect() }
    }
}

@Composable
private fun WolfPageHeader(title: String, subtitle: String, eyebrow: String = "WOLF AI WORKSPACE") {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(46.dp).background(Color(0xFF123C59), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", color = Color(0xFF55D9FF), style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(eyebrow, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
                Text(title, color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun WolfSectionLabel(title: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.width(4.dp).height(20.dp).background(Color(0xFF28C9F4), RoundedCornerShape(4.dp)))
        Text(title, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable
private fun NavigationChip(label:String, selected:Boolean, onClick:()->Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFF123C59) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) Color(0xFF25C8F4) else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        shadowElevation = if (selected) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (selected) Box(Modifier.size(7.dp).background(Color(0xFF35D5FF), RoundedCornerShape(50)))
            Text(label, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WolfApp(token: String, saveToken: (String)->Unit, send: suspend (String, List<Pair<String,String>>, String)->String, createMission: suspend (String)->JSONObject, listMissions: suspend ()->JSONArray, decideMission: suspend (String, String)->JSONObject, prepareMissionStep: suspend (String, Int)->JSONObject, executeMissionStep: suspend (String, Int)->JSONObject, controlMission: suspend (String, String)->JSONObject, retryMissionStep: suspend (String, Int)->JSONObject, setRemoteMaster: suspend (Boolean)->Unit, loadProviderStatus: suspend ()->JSONObject, listMemory: suspend ()->JSONArray, deleteMemory: suspend (String)->JSONObject, listArtifacts: suspend ()->JSONArray, getArtifact: suspend (String)->JSONObject, deleteArtifact: suspend (String)->JSONObject, createArtifact: suspend (String, String)->JSONObject, speak: (String)->Unit, stopSpeaking: ()->Unit, storagePrefs: SharedPreferences, storageRoot: File, historyFile: File, loadContacts: suspend () -> List<Pair<String,String>>, loadCalendarEvents: suspend () -> List<CalendarEvent>, startupCrashReport: String) {
    var paired by remember { mutableStateOf(token.isNotBlank()) }
    var localMode by remember { mutableStateOf(storagePrefs.safeBoolean("local_mode", false)) }
    var showStartupCrash by remember { mutableStateOf(startupCrashReport.isNotBlank()) }
    var pairCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (startupCrashReport.isBlank()) "آماده گفتگو" else "گزارش خطای اجرای قبلی ذخیره شده است.") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var exportStatus by remember { mutableStateOf("برای خروجی گرفتن، متن سند را وارد کن.") }
    fun copyExportToUri(uri: Uri?) {
        val source = pendingExportFile
        if (uri == null || source == null) { exportStatus = "ذخیره خروجی لغو شد."; return }
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
                ?: error("امکان نوشتن فایل مقصد وجود ندارد.")
        }.onSuccess { exportStatus = "فایل با موفقیت در محل انتخاب‌شده ذخیره شد." }
            .onFailure { exportStatus = "ذخیره فایل ناموفق بود: ${it.message}" }
        pendingExportFile = null
    }
    val pdfSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { copyExportToUri(it) }
    val docxSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { copyExportToUri(it) }
    val xlsxSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { copyExportToUri(it) }
    if (showStartupCrash) {
        AlertDialog(
            onDismissRequest = { showStartupCrash = false },
            title = { Text("گزارش خطای اجرای قبلی WOLF AI") },
            text = { Text(startupCrashReport.take(6000), maxLines = 18) },
            confirmButton = {
                TextButton(onClick = { showStartupCrash = false }) { Text("متوجه شدم") }
            }
        )
    }
    fun findPhoneNumberByName(query: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val found = mutableListOf<ContactPhone>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) found.add(ContactPhone(cursor.getString(nameCol).orEmpty(), cursor.getString(numberCol).orEmpty()))
        }
        return (ContactResolver.resolve(query, found) as? ContactLookup.Match)?.contact?.number
    }
    var showStorage by remember { mutableStateOf(false) }
    var showMarket by remember { mutableStateOf(false) }
    var marketQuery by remember { mutableStateOf("") }
    var marketSource by remember { mutableStateOf("global") }
    var exchangeQuote by remember { mutableStateOf<ExchangeQuote?>(null) }
    var marketStatus by remember { mutableStateOf("نام یا نماد رمزارز را جست‌وجو کن.") }
    var marketBusy by remember { mutableStateOf(false) }
    var marketQuotes by remember { mutableStateOf<List<CoinQuote>>(emptyList()) }
    var remoteArtifacts by remember { mutableStateOf(JSONArray()) }
    var cloudDocTitle by remember { mutableStateOf("") }
    var cloudDocContent by remember { mutableStateOf("") }
    var cloudDocStatus by remember { mutableStateOf("اسناد خصوصی ابری هنوز بارگذاری نشده‌اند.") }
    var selectedCloudDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingCloudDocDelete by remember { mutableStateOf<JSONObject?>(null) }
    var remoteMemory by remember { mutableStateOf(JSONArray()) }
    var remoteMemoryStatus by remember { mutableStateOf("برای مشاهده حافظه خصوصی سرور، به‌روزرسانی را بزن.") }
    var remoteMemorySearch by remember { mutableStateOf("") }
    var pendingMemoryDelete by remember { mutableStateOf<String?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var showPlanner by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var notificationRefresh by remember { mutableIntStateOf(0) }
    var eventTitle by remember { mutableStateOf("") }
    var eventDate by remember { mutableStateOf(java.time.LocalDate.now().plusDays(1).toString()) }
    var eventTime by remember { mutableStateOf("09:00") }
    var eventLocation by remember { mutableStateOf("") }
    var eventNotes by remember { mutableStateOf("") }
    var plannerStatus by remember { mutableStateOf("برای ساخت رویداد، عنوان و توضیحات را وارد کن.") }
    var calendarEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var calendarEventsStatus by remember { mutableStateOf("برای مشاهده قرارهای ثبت‌شده، دسترسی تقویم را فعال کن.") }
    var filesRefresh by remember { mutableIntStateOf(0) }
    var fileStatus by remember { mutableStateOf("فایل‌ها را از حافظه گوشی وارد فضای امن WOLF کن.") }
    var showContacts by remember { mutableStateOf(false) }
    var showDeviceTools by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var alarmHour by remember { mutableStateOf("7") }
    var alarmMinute by remember { mutableStateOf("00") }
    var deviceStatus by remember { mutableStateOf("اجرای برنامه‌ها و تنظیم هشدار گوشی") }
    val launchableApps = remember(showDeviceTools) { if (!showDeviceTools) emptyList() else context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(context.packageManager).toString().lowercase() } }
    var pendingCallNumber by remember { mutableStateOf<String?>(null) }
    var pendingCallType by remember { mutableStateOf("phone") }
    var pendingCallLabel by remember { mutableStateOf("") }
    var pendingCallMessage by remember { mutableStateOf("") }
    var pendingCallSubject by remember { mutableStateOf("") }
    var pendingCalendarTitle by remember { mutableStateOf("") }
    var pendingCalendarStart by remember { mutableStateOf(0L) }
    var pendingCalendarEnd by remember { mutableStateOf(0L) }
    var pendingCalendarLocation by remember { mutableStateOf("") }
    var pendingCalendarDescription by remember { mutableStateOf("") }
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val number = pendingCallNumber
        pendingCallNumber = null
        if (granted && !number.isNullOrBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))) }
                .onSuccess { status = "درخواست شماره‌گیری به اندروید ارسال شد؛ پاسخ‌گویی مخاطب تأیید نشده است." }
                .onFailure { status = "شروع تماس ناموفق بود: ${it.message}" }
        } else if (!granted) status = "مجوز تماس داده نشد؛ تماس برقرار نشد."
    }
    fun confirmPendingCall() {
        val number = pendingCallNumber ?: return
        val type = pendingCallType
        pendingCallNumber = null
        pendingCallType = "phone"
        pendingCallLabel = ""
        when (type) {
            "calendar" -> {
                val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
                    putExtra(CalendarContract.Events.TITLE, pendingCalendarTitle)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, pendingCalendarStart)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, pendingCalendarEnd)
                    putExtra(CalendarContract.Events.EVENT_LOCATION, pendingCalendarLocation)
                    putExtra(CalendarContract.Events.DESCRIPTION, pendingCalendarDescription)
                }
                pendingCalendarTitle = ""; pendingCalendarLocation = ""; pendingCalendarDescription = ""
                pendingCalendarStart = 0L; pendingCalendarEnd = 0L
                runCatching { context.startActivity(intent) }
                    .onSuccess { status = "فرم رویداد در تقویم باز شد؛ ذخیره نهایی با تأیید خودت در برنامه تقویم انجام می‌شود." }
                    .onFailure { status = "برنامه تقویم در دسترس نیست: ${it.message}" }
            }
            "email" -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + number)).apply {
                    putExtra(Intent.EXTRA_SUBJECT, pendingCallSubject)
                    putExtra(Intent.EXTRA_TEXT, pendingCallMessage)
                }
                pendingCallSubject = ""; pendingCallMessage = ""
                runCatching { context.startActivity(intent) }
                    .onSuccess { status = "پیش‌نویس ایمیل در برنامهٔ ایمیل باز شد؛ ارسال نهایی با خودت است." }
                    .onFailure { status = "برنامهٔ ایمیل در دسترس نیست: ${it.message}" }
            }
            "whatsapp_message" -> {
                val digits = number.filter { it.isDigit() }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(pendingCallMessage))).setPackage("com.whatsapp")
                pendingCallMessage = ""
                runCatching { context.startActivity(intent) }
                    .onSuccess { status = "پیش‌نویس پیام در واتساپ باز شد؛ ارسال نهایی با خودت است." }
                    .onFailure { status = "بازکردن واتساپ ناموفق بود: ${it.message}" }
            }
            "whatsapp" -> {
                val digits = number.filter { it.isDigit() }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage("com.whatsapp")
                runCatching { context.startActivity(intent) }
                    .onSuccess { status = "گفتگوی واتساپ باز شد؛ برای شروع تماس، دکمه تماس را خودت در واتساپ بزن." }
                    .onFailure { status = "بازکردن واتساپ ناموفق بود: ${it.message}" }
            }
            "telegram" -> {
                val digits = number.filter { it.isDigit() }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$digits")).setPackage("org.telegram.messenger")
                runCatching { context.startActivity(intent) }
                    .onSuccess { status = "مخاطب تلگرام باز شد؛ برای شروع تماس، دکمه تماس را خودت در تلگرام بزن." }
                    .onFailure { status = "بازکردن تلگرام ناموفق بود: ${it.message}" }
            }
            else -> {
                if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))) }
                        .onSuccess { status = "درخواست شماره‌گیری به اندروید ارسال شد؛ پاسخ‌گویی مخاطب تأیید نشده است." }
                        .onFailure { status = "شروع تماس ناموفق بود: ${it.message}" }
                } else {
                    pendingCallNumber = number
                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                }
            }
        }
    }
    var contacts by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var contactSearch by remember { mutableStateOf("") }
    var contactsStatus by remember { mutableStateOf("برای مشاهده مخاطبین، مجوز گوشی لازم است.") }
    var showMissions by remember { mutableStateOf(false) }
    var missionText by remember { mutableStateOf("") }
    var missionBusy by remember { mutableStateOf(false) }
    var missionStatus by remember { mutableStateOf("برای ساخت طرح مأموریت، درخواستت را بنویس.") }
    var missions by remember { mutableStateOf(JSONArray()) }
    var selectedModel by remember { mutableStateOf(storagePrefs.safeString("ai_model", "auto")) }
    var providerStatus by remember { mutableStateOf<JSONObject?>(null) }
    var providerStatusError by remember { mutableStateOf("وضعیت موتورها هنوز دریافت نشده است.") }
    var providerStatusBusy by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var darkTheme by remember { mutableStateOf(storagePrefs.safeBoolean("dark_theme", true)) }
    var masterEnabled by remember { mutableStateOf(storagePrefs.safeBoolean("master_enabled", true)) }
    var storageRefresh by remember { mutableIntStateOf(0) }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var noteSearch by remember { mutableStateOf("") }
    var noteStatus by remember { mutableStateOf("یادداشت‌ها فقط روی همین دستگاه ذخیره می‌شوند.") }
    var notesRefresh by remember { mutableIntStateOf(0) }
    var editingNote by remember { mutableStateOf<File?>(null) }
    var pendingDeleteBucket by remember { mutableStateOf<Pair<String, File>?>(null) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    var quotaGb by remember { mutableFloatStateOf(storagePrefs.safeInt("storage_quota_gb", 60).toFloat()) }
    val storageBuckets = remember(storageRefresh) { listOf("history" to "تاریخچه گفتگوها", "memory" to "حافظه بلندمدت", "notes" to "یادداشت‌ها", "files" to "فایل‌ها", "audio" to "صدا و ضبط‌ها").map { (key, label) -> key to (label to File(storageRoot, key).apply { mkdirs() }) } }
    fun folderBytes(file: File): Long = if (!file.exists()) 0L else if (file.isFile) file.length() else (file.listFiles()?.sumOf { folderBytes(it) } ?: 0L)
    val usedBytes = storageBuckets.sumOf { folderBytes(it.second.second) }
    val usedGb = usedBytes.toDouble() / (1024.0 * 1024 * 1024)
    val phoneFreeGb = storageRoot.usableSpace.toDouble() / (1024.0 * 1024 * 1024)
    val usagePercent = if (quotaGb > 0) (usedGb / quotaGb).toFloat().coerceIn(0f, 1f) else 0f
    val chat = remember {
        mutableStateListOf<Pair<String,String>>().apply {
            runCatching {
                if (historyFile.exists()) {
                    val saved = JSONArray(historyFile.readText())
                    for (i in 0 until saved.length()) {
                        val item = saved.getJSONObject(i)
                        add(item.optString("role") to item.optString("text"))
                    }
                }
            }
        }
    }
    fun persistChat() {
        runCatching {
            historyFile.parentFile?.mkdirs()
            val saved = JSONArray()
            chat.forEach { (role, text) ->
                saved.put(JSONObject().put("role", role).put("text", text))
            }
            val temp = File(historyFile.parentFile, "chat.json.tmp")
            temp.writeText(saved.toString())
            if (!temp.renameTo(historyFile)) {
                historyFile.writeText(saved.toString())
                temp.delete()
            }
        }
    }
    val scope = rememberCoroutineScope()
    fun acceptProviderStatus(status: JSONObject) {
        providerStatus = status
        providerStatusError = ""
        val providers = status.optJSONArray("providers") ?: JSONArray()
        val selectedAvailable = (0 until providers.length()).any { index ->
            val item = providers.optJSONObject(index)
            item != null && item.optString("id") == selectedModel && item.optBoolean("configured")
        }
        if (selectedModel != "auto" && !selectedAvailable) {
            selectedModel = "auto"
            storagePrefs.safeEdit { putString("ai_model", "auto") }
        }
    }
    LaunchedEffect(paired) {
        if (paired) {
            providerStatusBusy = true
            runCatching { loadProviderStatus() }
                .onSuccess { acceptProviderStatus(it) }
                .onFailure { providerStatusError = it.message ?: "ارتباط با سرور برقرار نشد." }
            providerStatusBusy = false
        }
    }
    fun providerIsConfigured(id: String): Boolean {
        if (id == "auto") return true
        val providers = providerStatus?.optJSONArray("providers") ?: return false
        for (i in 0 until providers.length()) {
            val item = providers.optJSONObject(i) ?: continue
            if (item.optString("id") == id) return item.optBoolean("configured")
        }
        return false
    }
    fun runLocalCommand(raw: String): String? {
        val command = raw.trim().lowercase(Locale.ROOT)
        val addressMatch = Regex("(https?://[^\\s]+|www\\.[^\\s]+)").find(command)
        if (addressMatch != null && (command.contains("باز") || command.contains("open") || command.contains("chrome") || command.contains("firefox") || command.contains("سایت"))) {
            val address = if (addressMatch.value.startsWith("http")) addressMatch.value else "https://" + addressMatch.value
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse(address))
            if (command.contains("firefox")) browser.setPackage("org.mozilla.firefox") else if (command.contains("chrome")) browser.setPackage("com.android.chrome")
            if (browser.resolveActivity(context.packageManager) != null) { context.startActivity(browser); return "صفحه در مرورگر باز شد: " + address }
            return "مرورگر انتخاب‌شده نصب نیست یا پیوند را باز نمی‌کند."
        }
        val calendarCommand = command.contains("رویداد تقویم") || command.contains("calendar event")
        if (calendarCommand) {
            fun field(name: String): String = Regex("(?:^|\\s)${Regex.escape(name)}\\s*[:：]\\s*(.+?)(?=\\s+(?:عنوان|شروع|پایان|مکان|توضیح|title|start|end|location|description)\\s*[:：]|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            val title = field("عنوان").ifBlank { field("title") }
            val startRaw = field("شروع").ifBlank { field("start") }
            val endRaw = field("پایان").ifBlank { field("end") }
            val location = field("مکان").ifBlank { field("location") }
            val description = field("توضیح").ifBlank { field("description") }
            if (title.isBlank() || startRaw.isBlank() || endRaw.isBlank()) return "قالب رویداد: رویداد تقویم عنوان: جلسه شروع: 2026-11-29T14:00 پایان: 2026-11-29T15:00 مکان: دفتر توضیح: متن"
            val start = runCatching { java.time.LocalDateTime.parse(startRaw).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                ?: return "زمان شروع معتبر نیست؛ قالب تاریخ و ساعت باید YYYY-MM-DDTHH:mm باشد."
            val end = runCatching { java.time.LocalDateTime.parse(endRaw).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                ?: return "زمان پایان معتبر نیست؛ قالب تاریخ و ساعت باید YYYY-MM-DDTHH:mm باشد."
            if (end <= start) return "زمان پایان باید بعد از زمان شروع باشد."
            if (start < System.currentTimeMillis() - 60_000L) return "زمان شروع رویداد در گذشته است؛ زمان آینده وارد کن."
            pendingCallNumber = "calendar-event"; pendingCallType = "calendar"
            pendingCalendarTitle = title; pendingCalendarStart = start; pendingCalendarEnd = end
            pendingCalendarLocation = location; pendingCalendarDescription = description
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            pendingCallLabel = "رویداد تقویم: $title\\nشروع: ${formatter.format(java.util.Date(start))}\\nپایان: ${formatter.format(java.util.Date(end))}\\nمکان: ${location.ifBlank { "تعیین نشده" }}"
            return "رویداد آماده بررسی است. پس از تأیید، فرم تقویم با جزئیات باز می‌شود و ذخیره نهایی را خودت انجام می‌دهی."
        }
        val emailMatch = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).find(raw)
        if ((command.contains("ایمیل") || command.contains("email")) && emailMatch != null) {
            val subject = Regex("موضوع\\s*[:：]\\s*(.+?)(?=\\s+متن\\s*[:：]|$)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            val body = Regex("متن\\s*[:：]\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            if (subject.isBlank() && body.isBlank()) return "برای ساخت پیش‌نویس ایمیل، از قالب «ایمیل به name@example.com موضوع: ... متن: ...» استفاده کن."
            pendingCallNumber = emailMatch.value; pendingCallType = "email"
            pendingCallSubject = subject; pendingCallMessage = body
            pendingCallLabel = "پیش‌نویس ایمیل به ${emailMatch.value}\nموضوع: ${subject.ifBlank { "بدون موضوع" }}\nمتن: ${body.take(500)}"
            return "پیش‌نویس آماده است. برای بازکردن برنامه ایمیل و بررسی آن، تأیید کن؛ ایمیل خودکار ارسال نمی‌شود."
        }
        val isWhatsappCall = command.contains("واتساپ") || command.contains("whatsapp")
        val isWhatsappMessage = isWhatsappCall && (command.contains("پیام") || command.contains("message"))
        if (isWhatsappMessage) {
            val target = raw.substringAfter("به ", "").substringBefore(" متن").trim()
            val number = Regex("[+]?\\d[\\d ()-]{5,}").find(raw)?.value ?: if (target.isNotBlank()) findPhoneNumberByName(target) else null
            val body = Regex("(?:متن|message)\\s*[:：]\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            if (number.isNullOrBlank()) return "شماره مخاطب پیدا نشد؛ نام یا شمارهٔ دقیق مخاطب را وارد کن."
            if (body.isBlank()) return "متن پیام مشخص نیست. از قالب «پیام واتساپ به شماره ... متن: ...» استفاده کن."
            pendingCallNumber = number; pendingCallType = "whatsapp_message"; pendingCallMessage = body
            pendingCallLabel = "پیش‌نویس پیام واتساپ به $number\n${body.take(700)}"
            return "پیش‌نویس پیام آماده است؛ برای بازکردن واتساپ و بررسی متن تأیید کن. پیام خودکار ارسال نمی‌شود."
        }
        val callIntent = command.contains("زنگ بزن") || command.contains("تماس بگیر") || command.contains("تماس با") || command.contains("شماره گیری") || command.contains("call ")
        val isTelegramCall = command.contains("تلگرام") || command.contains("telegram")
        if (isTelegramCall && callIntent) {
            val target = raw.substringAfter("به ", raw.substringAfter("با تلگرام", raw.substringAfter("با telegram", "")))
                .replace("زنگ بزن", "").replace("تماس بگیر", "").trim()
            val number = Regex("[+]?\\d[\\d ()-]{5,}").find(raw)?.value
                ?: if (target.isNotBlank()) findPhoneNumberByName(target) else null
            if (number.isNullOrBlank()) return "شماره مخاطب پیدا نشد. برای تماس تلگرام، شماره مخاطب یا نام ذخیره‌شده‌اش لازم است."
            val digits = number.filter { it.isDigit() }
            val telegram = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$digits")).setPackage("org.telegram.messenger")
            if (telegram.resolveActivity(context.packageManager) == null) return "تلگرام اصلی نصب نیست یا پیوند مخاطب را پشتیبانی نمی‌کند."
            pendingCallNumber = number
            pendingCallType = "telegram"
            pendingCallLabel = "تماس تلگرام با شماره $number"
            return "درخواست آماده است؛ برای بازکردن مخاطب تلگرام و ادامه تماس باید آن را تأیید کنی."
        }
        if (isWhatsappCall && callIntent) {
            val target = raw.substringAfter("به ", raw.substringAfter("با واتساپ", raw.substringAfter("با whatsapp", ""))).replace("زنگ بزن", "").replace("تماس بگیر", "").trim()
            val number = Regex("[+]?\\d[\\d ()-]{5,}").find(raw)?.value ?: if (target.isNotBlank()) findPhoneNumberByName(target) else null
            if (number.isNullOrBlank()) return "شماره مخاطب پیدا نشد. نام مخاطب را دقیق بنویس و مجوز مخاطبین را فعال کن."
            val digits = number.filter { it.isDigit() }
            val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage("com.whatsapp")
            if (wa.resolveActivity(context.packageManager) == null) return "واتساپ نصب نیست یا لینک مخاطب را باز نمی‌کند."
            pendingCallNumber = number
            pendingCallType = "whatsapp"
            pendingCallLabel = "تماس واتساپ با شماره $number"
            return "درخواست آماده است؛ برای بازکردن گفتگوی واتساپ و ادامه تماس باید آن را تأیید کنی."
        }
        if (callIntent) {
            val number = Regex("[+]?\\d[\\d ()-]{5,}").find(raw)?.value
                ?: raw.substringAfter("با ", raw.substringAfter("به ", "")).let { findPhoneNumberByName(it.replace("زنگ بزن", "").replace("تماس بگیر", "").trim()) }
            if (number.isNullOrBlank()) return "شماره مخاطب پیدا نشد. نام یا شماره را دقیق بنویس و مجوز مخاطبین را فعال کن."
            pendingCallNumber = number
            pendingCallType = "phone"
            pendingCallLabel = "تماس تلفنی مستقیم با شماره $number"
            return "درخواست تماس آماده است؛ پیش از شماره‌گیری، جزئیات را تأیید کن."
        }
        if (command.contains("خواندن صفحه") || command.contains("متن صفحه") || command.contains("read screen")) {
            val service = WolfAccessibilityService.active ?: return "برای خواندن صفحه، ابتدا دسترسی کنترل پیشرفته WOLF را در تنظیمات فعال کن."
            return service.readVisibleText()
        }
        if (command.contains("برگرد عقب") || command == "عقب" || command.contains("دکمه بازگشت")) {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.global("back")) "به صفحه قبل برگشتم." else "بازگشت در این صفحه انجام نشد."
        }
        if (command.contains("برو خانه") || command.contains("صفحه اصلی گوشی") || command == "home") {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.global("home")) "به صفحه اصلی گوشی برگشتم." else "رفتن به خانه انجام نشد."
        }
        if (command.contains("برنامه های اخیر") || command.contains("برنامه‌های اخیر") || command.contains("recent apps")) {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.global("recents")) "برنامه‌های اخیر باز شدند." else "بازکردن برنامه‌های اخیر انجام نشد."
        }
        if (command.contains("پنل اعلان") || command.contains("اعلان ها را باز") || command.contains("اعلان‌ها را باز") || command.contains("notifications")) {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.global("notifications")) "پنل اعلان‌ها باز شد." else "بازکردن پنل اعلان‌ها انجام نشد."
        }
        if (command.contains("تنظیمات سریع") || command.contains("quick settings")) {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.global("quick_settings")) "تنظیمات سریع باز شد." else "بازکردن تنظیمات سریع انجام نشد."
        }
        if (command.contains("اسکرول پایین") || command.contains("پایین برو") || command.contains("صفحه را پایین") || command.contains("scroll down")) {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.scroll("down")) "صفحه به پایین پیمایش شد." else "بخش قابل پیمایش در صفحه پیدا نشد."
        }
        if (command.contains("اسکرول بالا") || command.contains("بالا برو") || command.contains("صفحه را بالا") || command.contains("scroll up")) {
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.scroll("up")) "صفحه به بالا پیمایش شد." else "بخش قابل پیمایش در صفحه پیدا نشد."
        }
        if (command.contains("نگه دار") || command.contains("لمس طولانی")) {
            val target = command.substringAfter("روی ").substringBefore("نگه").substringBefore("لمس").trim()
            if (target.isBlank()) return "نام گزینه‌ای که باید نگه داشته شود مشخص نیست."
            val service = WolfAccessibilityService.active ?: return "برای کنترل صفحه، دسترسی Accessibility را در تنظیمات فعال کن."
            return if (service.longClickVisibleText(target)) "روی «$target» لمس طولانی انجام شد." else "گزینه «$target» پیدا نشد یا لمس طولانی را پشتیبانی نمی‌کند."
        }
        if (command.contains("در فیلد") && (command.contains("بنویس") || command.contains("وارد کن"))) {
            val field = command.substringAfter("در فیلد").substringBefore("بنویس").substringBefore("وارد کن").trim()
            val value = raw.substringAfter("بنویس", raw.substringAfter("وارد کن", "")).trim()
            if (field.isBlank() || value.isBlank()) return "نام فیلد و متن موردنظر را مشخص کن."
            val service = WolfAccessibilityService.active ?: return "ابتدا دسترسی کنترل پیشرفته WOLF را در تنظیمات فعال کن."
            return if (service.enterText(field, value)) "متن در فیلد موردنظر وارد شد؛ ارسال یا ثبت نهایی با تأیید تو انجام می‌شود." else "فیلد موردنظر در صفحه پیدا نشد یا اجازه ورود متن نداد."
        }
        if (command.contains("روی ") && (command.contains("بزن") || command.contains("کلیک"))) {
            val target = command.substringAfter("روی ").substringBefore("بزن").substringBefore("کلیک").trim()
            if (target.isBlank()) return "نام دکمه یا گزینه مشخص نیست."
            val service = WolfAccessibilityService.active ?: return "ابتدا دسترسی کنترل پیشرفته WOLF را در تنظیمات فعال کن."
            return if (service.clickVisibleText(target)) "روی «" + target + "» کلیک شد." else "گزینه «" + target + "» در صفحه فعلی پیدا نشد."
        }
        if (command.contains("vpn")) {
            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            return "تنظیمات VPN باز شد؛ روشن‌کردن تونل به اتصال رسمی سرویس نیاز دارد."
        }
        if (command.contains("باز کن") || command.contains("اجرا کن") || command.startsWith("open app") || command.startsWith("launch ")) {
            val target = command.replace("لطفاً", "").replace("لطفا", "").replace("باز کن", "").replace("اجرا کن", "").replace("برنامه", "").replace("open app", "").replace("launch", "").trim()
            val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }
            val match = apps.firstOrNull { target.isNotBlank() && it.loadLabel(context.packageManager).toString().lowercase().contains(target) }
            if (match != null) {
                val launch = context.packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
                if (launch != null) { context.startActivity(launch); return "برنامه " + match.loadLabel(context.packageManager) + " باز شد." }
            }
            return "برنامه موردنظر پیدا نشد. نام نمایشی برنامه را دقیق‌تر بنویس."
        }
        if (command.contains("هشدار") || command.contains("آلارم")) {
            val match = Regex("(\\d{1,2})[:٫.](\\d{2})").find(command)
            if (match != null) {
                val hour = match.groupValues[1].toIntOrNull() ?: return "ساعت نامعتبر است."
                val minute = match.groupValues[2].toIntOrNull() ?: return "دقیقه نامعتبر است."
                if (hour !in 0..23 || minute !in 0..59) return "زمان هشدار معتبر نیست."
                val alarm = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_MESSAGE, "هشدار از WOLF").putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (alarm.resolveActivity(context.packageManager) != null) { context.startActivity(alarm); return "فرم تنظیم هشدار باز شد؛ ذخیره را در برنامه ساعت تأیید کن." }
                return "برنامه ساعت پیدا نشد."
            }
        }
        return null
    }
    var activeRequest by remember { mutableStateOf<Job?>(null) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { runCatching { loadContacts() }.onSuccess { contacts = it; contactsStatus = it.size.toString() + " شماره مخاطب خوانده شد." }.onFailure { contactsStatus = it.message ?: "خواندن مخاطبین ناموفق بود." } }
        else contactsStatus = "مجوز مخاطبین داده نشد."
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch {
            calendarEventsStatus = "در حال خواندن تقویم..."
            runCatching { loadCalendarEvents() }
                .onSuccess { calendarEvents = it; calendarEventsStatus = it.size.toString() + " رویداد پیدا شد." }
                .onFailure { calendarEventsStatus = it.message ?: "خواندن تقویم ناموفق بود." }
        } else calendarEventsStatus = "مجوز تقویم داده نشد؛ هیچ رویدادی خوانده نشد."
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileStatus = "در حال وارد کردن فایل..."
            scope.launch {
                val imported = runCatching {
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        var displayName = "document"
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
                        }
                        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(100).ifBlank { "document" }
                        val destination = File(storageRoot, "files").apply { mkdirs() }
                        var target = File(destination, safeName)
                        var suffix = 1
                        while (target.exists()) { target = File(destination, "${target.nameWithoutExtension}_${suffix++}.${target.extension}") }
                        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                            ?: error("خواندن فایل ممکن نشد")
                        target.name
                    }
                }
                imported.onSuccess {
                    fileStatus = "فایل «$it» وارد شد."
                    filesRefresh++
                    storageRefresh++
                }.onFailure { fileStatus = "واردکردن فایل ناموفق بود: ${it.message}" }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExportFile
        pendingExportFile = null
        if (uri != null && source != null && source.exists()) {
            fileStatus = "در حال ساخت فایل خروجی..."
            scope.launch {
                val exported = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("ساخت فایل خروجی ممکن نشد")
                    }
                }
                exported.onSuccess { fileStatus = "فایل «${source.name}» در حافظه انتخاب‌شده ذخیره شد." }
                    .onFailure { fileStatus = "خروجی گرفتن ناموفق بود: ${it.message}" }
            }
        } else if (uri != null) fileStatus = "فایل منبع برای خروجی پیدا نشد."
    }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) message = spoken
    }
    val wolfColors = if (darkTheme) darkColorScheme(primary=Color(0xFF36C8F5),secondary=Gold,background=Color(0xFF071426),surface=Color(0xFF10243D),surfaceVariant=Color(0xFF18334F)) else lightColorScheme(primary=Color(0xFF075E8C),secondary=Color(0xFF9A6900),background=Color(0xFFF3F6FA),surface=Color.White,surfaceVariant=Color(0xFFE7EEF5))
    MaterialTheme(colorScheme=wolfColors) {
        pendingDeleteBucket?.let { (label, dir) ->
            AlertDialog(
                onDismissRequest = { pendingDeleteBucket = null },
                title = { Text("تأیید پاک‌سازی") },
                text = { Text("تمام محتویات بخش «" + label + "» برای همیشه از حافظه محلی WOLF حذف می‌شود. این کار قابل بازگشت نیست. ادامه می‌دهی؟") },
                confirmButton = {
                    TextButton(onClick = {
                        val deleted = dir.listFiles()?.count { it.deleteRecursively() } ?: 0
                        pendingDeleteBucket = null
                        storageRefresh++
                        status = "پاک‌سازی «" + label + "» انجام شد؛ " + deleted + " مورد حذف شد"
                    }) { Text("حذف دائمی", color = Color(0xFFFF7777)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteBucket = null }) { Text("انصراف") }
                }
            )
        }
        selectedCloudDoc?.let { (title, content) ->
            AlertDialog(
                onDismissRequest = { selectedCloudDoc = null },
                title = { Text(title) },
                text = { Text(content, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { selectedCloudDoc = null }) { Text("بستن") } }
            )
        }
        pendingCloudDocDelete?.let { doc ->
            AlertDialog(
                onDismissRequest = { pendingCloudDocDelete = null },
                title = { Text("حذف سند خصوصی") },
                text = { Text("سند «${doc.optString("title")}» برای همیشه از فضای ابری حساب مالک حذف می‌شود. ادامه می‌دهی؟") },
                confirmButton = {
                    TextButton(onClick = {
                        val id = doc.optString("id")
                        pendingCloudDocDelete = null
                        scope.launch {
                            runCatching { deleteArtifact(id) }
                                .onSuccess {
                                    runCatching { listArtifacts() }.onSuccess { remoteArtifacts = it }
                                    cloudDocStatus = "سند حذف شد."
                                }
                                .onFailure { cloudDocStatus = "حذف سند ناموفق بود: ${it.message}" }
                        }
                    }) { Text("حذف دائمی", color = Color(0xFFFF7777)) }
                },
                dismissButton = { TextButton(onClick = { pendingCloudDocDelete = null }) { Text("انصراف") } }
            )
        }
        pendingMemoryDelete?.let { memoryKey ->
            AlertDialog(
                onDismissRequest = { pendingMemoryDelete = null },
                title = { Text("حذف از حافظه خصوصی") },
                text = { Text("مورد «$memoryKey» برای همیشه از حافظه بلندمدت حساب مالک حذف می‌شود. این عمل قابل بازگشت نیست.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingMemoryDelete = null
                        scope.launch {
                            runCatching { deleteMemory(memoryKey) }
                                .onSuccess {
                                    runCatching { listMemory() }.onSuccess { remoteMemory = it }
                                    remoteMemoryStatus = "مورد «$memoryKey» حذف شد."
                                }
                                .onFailure { remoteMemoryStatus = "حذف انجام نشد: ${it.message}" }
                        }
                    }) { Text("حذف دائمی", color = Color(0xFFFF7777)) }
                },
                dismissButton = { TextButton(onClick = { pendingMemoryDelete = null }) { Text("انصراف") } }
            )
        }
        if (showClearChatConfirm) {
            AlertDialog(
                onDismissRequest = { showClearChatConfirm = false },
                title = { Text("پاک کردن همه گفتگوها") },
                text = { Text("تمام پیام‌های گفتگوی این دستگاه از تاریخچه محلی حذف می‌شوند. این کار قابل بازگشت نیست.") },
                confirmButton = {
                    TextButton(onClick = {
                        activeRequest?.cancel()
                        activeRequest = null
                        busy = false
                        chat.clear()
                        persistChat()
                        showClearChatConfirm = false
                        status = if (historyFile.exists() && historyFile.length() > 2L) "پاک‌کردن تاریخچه ناموفق بود" else "همه گفتگوها پاک شدند"
                    }) { Text("پاک کردن", color = Color(0xFFFF7777)) }
                },
                dismissButton = { TextButton(onClick = { showClearChatConfirm = false }) { Text("انصراف") } }
            )
        }
        if (!pendingCallNumber.isNullOrBlank()) {
            AlertDialog(
                onDismissRequest = {
                    pendingCallNumber = null
                    pendingCallType = "phone"
                    pendingCallLabel = ""; pendingCallMessage = ""; pendingCallSubject = ""
                    pendingCalendarTitle = ""; pendingCalendarLocation = ""; pendingCalendarDescription = ""
                    pendingCalendarStart = 0L; pendingCalendarEnd = 0L
                    status = "درخواست لغو شد؛ هیچ اقدامی انجام نشد."
                },
                title = { Text(if (pendingCallType == "calendar") "تأیید رویداد تقویم" else if (pendingCallType == "email" || pendingCallType == "whatsapp_message") "تأیید پیش‌نویس پیام" else "تأیید تماس") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pendingCallLabel)
                        Text(
                            when (pendingCallType) {
                                "phone" -> "با تأیید، اندروید درخواست شماره‌گیری مستقیم را اجرا می‌کند. تماس فقط پس از اجازه سیستم آغاز می‌شود."
                                "calendar" -> "با تأیید، فرم رویداد در تقویم گوشی باز می‌شود؛ رویداد تا وقتی خودت در تقویم ذخیره نکنی ثبت نخواهد شد."
                                "email" -> "با تأیید، فقط برنامه ایمیل و پیش‌نویس باز می‌شود؛ ارسال نهایی انجام نمی‌شود."
                                "whatsapp_message" -> "با تأیید، فقط پیش‌نویس در واتساپ باز می‌شود؛ ارسال نهایی انجام نمی‌شود."
                                else -> "با تأیید، فقط مخاطب در برنامه مربوط باز می‌شود. شروع تماس را باید خودت داخل همان برنامه انجام دهی."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { confirmPendingCall() }) { Text("تأیید و ادامه") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingCallNumber = null
                        pendingCallType = "phone"
                        pendingCallLabel = ""; pendingCallMessage = ""; pendingCallSubject = ""
                        pendingCalendarTitle = ""; pendingCalendarLocation = ""; pendingCalendarDescription = ""
                        pendingCalendarStart = 0L; pendingCalendarEnd = 0L
                        status = "درخواست لغو شد؛ هیچ اقدامی انجام نشد."
                    }) { Text("لغو") }
                }
            )
        }
        Scaffold(containerColor=MaterialTheme.colorScheme.background) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Brush.linearGradient(listOf(Color(0xFF071426), Color(0xFF123B59), Color(0xFF0A2238))))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val wolfLogoImage = remember(context) {
                            runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.wolf_logo_photo)?.asImageBitmap() }
                                .onFailure { android.util.Log.e("WOLF_IMAGE", "Logo image could not be decoded; using safe fallback", it) }
                                .getOrNull()
                        }
                        Box(
                            Modifier.size(68.dp).background(Color(0xFF183D59), RoundedCornerShape(21.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (wolfLogoImage != null) {
                                Image(bitmap = wolfLogoImage, contentDescription = "WOLF AI logo",
                                    modifier = Modifier.size(60.dp), contentScale = ContentScale.Fit)
                            } else Text("🐺", fontSize = 37.sp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("WOLF AI", color = Color.White, style = MaterialTheme.typography.headlineMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                            Text("PERSONAL AI AGENT", color = Color(0xFF4BD7FF),
                                style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                            Text("دستیار هوشمند شخصی", color = Color(0xFFD5E5F2),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color(0x3328D7A0),
                            border = BorderStroke(1.dp, Color(0x6655E2B0))) {
                            Text("PRIVATE", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = Color(0xFF80F0C2), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(10.dp).background(
                                if (masterEnabled) Color(0xFF35D6A0) else Gold,
                                RoundedCornerShape(50)
                            )
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (masterEnabled) "دستیار آماده است" else "فعالیت‌های خودکار متوقف است",
                                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                masterEnabled = !masterEnabled
                                storagePrefs.safeEdit { putBoolean("master_enabled", masterEnabled) }
                                if (!masterEnabled) { activeRequest?.cancel(); activeRequest = null; busy = false; status = "WOLF خاموش شد" }
                                else status = "WOLF فعال شد"
                                if (paired) scope.launch {
                                    runCatching { setRemoteMaster(masterEnabled) }
                                        .onFailure { status = "همگام‌سازی ناموفق: ${it.message}" }
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (masterEnabled) Color(0xFF542D37) else Color(0xFF15533F),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)
                        ) { Text(if (masterEnabled) "توقف" else "فعال‌سازی") }
                    }
                }
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    item { NavigationChip("گفتگو",!showStorage&&!showContacts&&!showDeviceTools&&!showModels&&!showMissions&&!showSettings&&!showNotifications&&!showPlanner&&!showFiles&&!showMarket) { showMarket=false;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false } }
                    item { NavigationChip("مخاطبین",showContacts) { showContacts=true;showStorage=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false; if(context.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED) scope.launch { runCatching { loadContacts() }.onSuccess { contacts=it;contactsStatus="${it.size} شماره مخاطب خوانده شد." }.onFailure { contactsStatus=it.message ?: "خطا در خواندن مخاطبین" } } else contactsPermission.launch(Manifest.permission.READ_CONTACTS) } }
                    item { NavigationChip("ابزار گوشی",showDeviceTools) { showDeviceTools=true;showStorage=false;showContacts=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false } }
                    item { NavigationChip("بازار رمزارز",showMarket) { showMarket=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false } }
                    if (paired) {
                        item { NavigationChip("مأموریت‌ها",showMissions) { showMissions=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false;scope.launch { runCatching { listMissions() }.onSuccess { missions=it }.onFailure { missionStatus=it.message ?: "خطا در دریافت مأموریت‌ها" } } } }
                        item { NavigationChip("موتورها",showModels) { showModels=true;showStorage=false;showContacts=false;showDeviceTools=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false } }
                    }
                    item { NavigationChip("اعلان‌ها",showNotifications) { showNotifications=true;showPlanner=false;showFiles=false;showMarket=false;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("تقویم",showPlanner) { showPlanner=true;showFiles=false;showMarket=false;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false } }
                    item { NavigationChip("فایل‌ها",showFiles) { showFiles=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false } }
                    item { NavigationChip("حافظه",showStorage) { showStorage=true;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false } }
                    item { NavigationChip("تنظیمات",showSettings) { showSettings=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showNotifications=false;showPlanner=false;showFiles=false;showMarket=false } }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(showMarket) {
                    WolfPageHeader("بازار رمزارز","قیمت جهانی و بازار ریالی صرافی‌ها","CRYPTO MARKET")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = marketSource == "global", onClick = { marketSource = "global"; marketQuotes = emptyList(); exchangeQuote = null }, label = { Text("جهانی") })
                        FilterChip(selected = marketSource == "nobitex", onClick = { marketSource = "nobitex"; marketQuotes = emptyList(); exchangeQuote = null }, label = { Text("نوبیتکس") })
                        FilterChip(selected = marketSource == "bitpin", onClick = { marketSource = "bitpin"; marketQuotes = emptyList(); exchangeQuote = null }, label = { Text("بیت‌پین") })
                    }
                    OutlinedTextField(
                        marketQuery, { marketQuery = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (marketSource == "global") "نام یا نماد، مثل Bitcoin یا BTC" else "نماد پایه، مثل BTC یا ETH") },
                        singleLine = true
                    )
                    Button(enabled = marketQuery.isNotBlank() && !marketBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                        marketBusy = true
                        marketStatus = "در حال دریافت داده‌های بازار..."
                        marketQuotes = emptyList()
                        exchangeQuote = null
                        scope.launch {
                            if (marketSource == "global") {
                                runCatching { MarketData.search(marketQuery) }
                                    .onSuccess { marketQuotes = it; marketStatus = if (it.isEmpty()) "داده‌ای برای این رمزارز دریافت نشد." else "${it.size} نتیجه از بازار جهانی دریافت شد." }
                                    .onFailure { marketStatus = "دریافت بازار ناموفق بود: ${it.message}" }
                            } else {
                                val result = runCatching {
                                    if (marketSource == "nobitex") ExchangeMarketData.nobitex(marketQuery)
                                    else ExchangeMarketData.bitpin(marketQuery)
                                }
                                result.onSuccess { exchangeQuote = it; marketStatus = "قیمت بازار ${it.exchange} دریافت شد." }
                                    .onFailure { marketStatus = "دریافت قیمت صرافی ناموفق بود: ${it.message}" }
                            }
                            marketBusy = false
                        }
                    }) { Text(if (marketBusy) "در حال دریافت..." else "جست‌وجوی بازار") }
                    Text(marketStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    if (marketSource == "global") {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(marketQuotes) { quote ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("${quote.name} (${quote.symbol})", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                        Text(if (quote.priceUsd.isFinite()) "$" + String.format(Locale.US, "%,.8f", quote.priceUsd).trimEnd('0').trimEnd('.') else "قیمت ناموجود", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
                                        Text("تغییر ۲۴ ساعت: " + (quote.change24h?.let { String.format(Locale.US, "%.2f%%", it) } ?: "ناموجود"), color = quote.change24h?.let { if (it >= 0) Color(0xFF55D6A0) else Color(0xFFFF7777) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("ارزش بازار: " + (quote.marketCapUsd?.let { String.format(Locale.US, "$%,.0f", it) } ?: "ناموجود"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        Text("حجم ۲۴ ساعته: " + (quote.volume24hUsd?.let { String.format(Locale.US, "$%,.0f", it) } ?: "ناموجود"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        Text("منبع: ${quote.source}" + (quote.updatedAt?.let { " | به‌روزرسانی: $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    } else {
                        exchangeQuote?.let { quote ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text("${quote.exchange} • ${quote.symbol}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                    Text(String.format(Locale.US, "%,.0f ریال", quote.lastPriceRial), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
                                    Text(String.format(Locale.US, "%,.0f تومان", quote.lastPriceToman), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                                    Text("تغییر ۲۴ ساعت: " + (quote.change24hPercent?.let { String.format(Locale.US, "%.2f%%", it) } ?: "ناموجود"), color = quote.change24hPercent?.let { if (it >= 0) Color(0xFF55D6A0) else Color(0xFFFF7777) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                                    quote.highRial?.let { Text("بیشترین قیمت: ${String.format(Locale.US, "%,.0f ریال", it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    quote.lowRial?.let { Text("کمترین قیمت: ${String.format(Locale.US, "%,.0f ریال", it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    quote.quoteVolumeRial?.let { Text("حجم ۲۴ ساعته: ${String.format(Locale.US, "%,.0f ریال", it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text("زمان: ${quote.updatedAt ?: "نامشخص"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    if (quote.marketClosed == true) Text("این بازار طبق پاسخ صرافی بسته است.", color = Gold)
                                }
                            }
                        }
                        Text("داده‌ها فقط خواندنی‌اند. هیچ سفارش خرید یا فروشی ثبت نمی‌شود. قیمت ریال و تومان از یک داده بازار و با تبدیل ۱۰ به ۱ نمایش داده می‌شود.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (marketSource == "global") {
                        Text("منابع: CoinGecko و CoinPaprika. داده جهانی است و قیمت صرافی‌ها را نشان نمی‌دهد.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Unit
                } else if(showDeviceTools) {
                    WolfPageHeader("ابزارهای گوشی","اجرای برنامه‌ها، تنظیم هشدار و دسترسی به امکانات اندروید","DEVICE CONTROL")
                    Text(deviceStatus,color=Cyan)
                    WolfSectionLabel("تنظیم هشدار")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(alarmHour,{alarmHour=it.filter(Char::isDigit).take(2)},modifier=Modifier.weight(1f),label={Text("ساعت")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        OutlinedTextField(alarmMinute,{alarmMinute=it.filter(Char::isDigit).take(2)},modifier=Modifier.weight(1f),label={Text("دقیقه")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        Button(enabled=(alarmHour.toIntOrNull()?:99) in 0..23&&(alarmMinute.toIntOrNull()?:99) in 0..59,onClick={
                            val alarm=Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,alarmHour.toInt()).putExtra(AlarmClock.EXTRA_MINUTES,alarmMinute.toInt()).putExtra(AlarmClock.EXTRA_MESSAGE,"هشدار از WOLF").putExtra(AlarmClock.EXTRA_SKIP_UI,false)
                            if(alarm.resolveActivity(context.packageManager)!=null) context.startActivity(alarm) else deviceStatus="برنامه ساعت پیدا نشد."
                        }) { Text("تنظیم") }
                    }
                    HorizontalDivider()
                    WolfSectionLabel("برنامه‌های نصب‌شده")
                    OutlinedTextField(appSearch,{appSearch=it},modifier=Modifier.fillMaxWidth(),label={Text("جست‌وجوی برنامه")},singleLine=true)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        items(launchableApps.filter{it.loadLabel(context.packageManager).toString().contains(appSearch,true)}) { app ->
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text(app.loadLabel(context.packageManager).toString(),color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.weight(1f))
                                    TextButton(onClick={context.packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)?.let{context.startActivity(it)}?:run{deviceStatus="اجرای این برنامه ممکن نیست."}}){Text("باز کن")}
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}){Text("تنظیمات VPN")}
                        OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_SETTINGS))}){Text("تنظیمات گوشی")}
                    }
                    Text("بازکردن تنظیمات VPN به معنی روشن‌شدن تونل نیست؛ اتصال واقعی به API رسمی سرویس نیاز دارد.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                }
                else if(showNotifications) {
                    WolfPageHeader("اعلان‌های گوشی","اعلان‌های دریافت‌شده را در یک نمای مرتب بررسی کن","NOTIFICATIONS")
                    Text("اعلان‌های جدید پس از فعال‌سازی دسترسی، فقط روی همین دستگاه نمایش داده می‌شوند.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}){Text("فعال‌سازی دسترسی")}
                        OutlinedButton(onClick={notificationRefresh++}){Text("به‌روزرسانی")}
                    }
                    val notificationItems=remember(notificationRefresh){WolfNotificationListener.recent.toList()}
                    if(notificationItems.isEmpty()) Text("هنوز اعلانی دریافت نشده است.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(notificationItems) { item ->
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                        Text(item.app,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                                        Text(item.time,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
                                    }
                                    if(item.title.isNotBlank()) Text(item.title,color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleSmall)
                                    if(item.text.isNotBlank()) Text(item.text,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Text("این بخش تاریخچه پیام‌های خصوصی را از برنامه‌ها استخراج نمی‌کند؛ فقط اعلان‌هایی را می‌بیند که اندروید پس از فعال‌سازی دسترسی به WOLF تحویل می‌دهد.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                } else if(showPlanner) {
                    WolfPageHeader("تقویم و برنامه‌ریزی","ساخت رویداد و مرور برنامه‌های ثبت‌شده در گوشی","CALENDAR")
                    Text("عنوان، تاریخ، ساعت و مکان را مشخص کن. ذخیره نهایی در تقویم گوشی فقط پس از تأیید خودت انجام می‌شود.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(eventTitle,{eventTitle=it},modifier=Modifier.fillMaxWidth(),label={Text("عنوان رویداد")},singleLine=true)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(eventDate,{eventDate=it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)},modifier=Modifier.weight(1.2f),label={Text("تاریخ YYYY-MM-DD")},singleLine=true)
                        OutlinedTextField(eventTime,{eventTime=it.filter { ch -> ch.isDigit() || ch == ':' }.take(5)},modifier=Modifier.weight(0.8f),label={Text("ساعت HH:MM")},singleLine=true)
                    }
                    OutlinedTextField(eventLocation,{eventLocation=it},modifier=Modifier.fillMaxWidth(),label={Text("مکان (اختیاری)")},singleLine=true)
                    OutlinedTextField(eventNotes,{eventNotes=it},modifier=Modifier.fillMaxWidth(),label={Text("توضیحات")},minLines=3)
                    Button(enabled=eventTitle.isNotBlank(),onClick={
                        runCatching {
                            val date = java.time.LocalDate.parse(eventDate)
                            val time = java.time.LocalTime.parse(eventTime)
                            val begin = java.time.ZonedDateTime.of(date,time,java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            require(begin > System.currentTimeMillis()) { "زمان رویداد باید در آینده باشد." }
                            val finish = begin + 60L*60L*1000L
                            val intent=Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                                .putExtra(CalendarContract.Events.TITLE,eventTitle.trim())
                                .putExtra(CalendarContract.Events.DESCRIPTION,eventNotes.trim())
                                .putExtra(CalendarContract.Events.EVENT_LOCATION,eventLocation.trim())
                                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin)
                                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,finish)
                            if(intent.resolveActivity(context.packageManager)!=null){context.startActivity(intent);plannerStatus="فرم رویداد با تاریخ و ساعت انتخابی باز شد؛ برای ثبت نهایی در تقویم ذخیره را بزن."}
                            else plannerStatus="برنامه تقویم سازگار پیدا نشد."
                        }.onFailure { plannerStatus = it.message ?: "تاریخ یا ساعت معتبر نیست؛ قالب YYYY-MM-DD و HH:MM را بررسی کن." }
                    },modifier=Modifier.fillMaxWidth()){Text("افزودن رویداد به تقویم")}
                    OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))},modifier=Modifier.fillMaxWidth()){Text("تنظیم دسترسی اعلان‌ها")}
                    HorizontalDivider()
                    WolfSectionLabel("رویدادهای تقویم")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Button(onClick={
                            if(context.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED) {
                                scope.launch {
                                    calendarEventsStatus="در حال خواندن تقویم..."
                                    runCatching { loadCalendarEvents() }
                                        .onSuccess { calendarEvents=it;calendarEventsStatus=it.size.toString()+" رویداد پیدا شد." }
                                        .onFailure { calendarEventsStatus=it.message ?: "خواندن تقویم ناموفق بود." }
                                }
                            } else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        }) { Text("خواندن تقویم گوشی") }
                        OutlinedButton(onClick={calendarEvents=emptyList();calendarEventsStatus="فهرست محلی پاک شد؛ رویدادهای اصلی تقویم حذف نمی‌شوند."}) { Text("پاک‌کردن فهرست") }
                    }
                    Text(calendarEventsStatus,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
                    if(calendarEvents.isNotEmpty()) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            items(calendarEvents) { event ->
                                Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        Text(event.title,color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleSmall)
                                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(event.begin)),color=MaterialTheme.colorScheme.primary)
                                        if(event.location.isNotBlank()) Text(event.location,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                        if(event.description.isNotBlank()) Text(event.description.take(240),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    Text("WOLF فقط رویدادهای ۷ روز گذشته تا ۹۰ روز آینده را می‌خواند؛ هیچ رویدادی را تغییر یا حذف نمی‌کند.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    Text(plannerStatus,color=MaterialTheme.colorScheme.primary)
                } else if(showFiles) {
                    WolfPageHeader("فایل‌ها و اسناد","ورود، خواندن، ویرایش و خروجی گرفتن از فایل‌های شخصی","DOCUMENTS")
                    Text("PDF، Word، Excel، تصویر، صوت و فایل‌های متنی را وارد کن. فایل اصلی در فضای خصوصی WOLF کپی می‌شود.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    Button(onClick={filePicker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) { Text("انتخاب فایل از گوشی") }
                    Text(fileStatus,color=MaterialTheme.colorScheme.primary)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    WolfSectionLabel("اسناد خصوصی ابری")
                    Text("سندهای متنی در فضای خصوصی حساب مالک ذخیره می‌شوند؛ ذخیره فقط با فشردن دکمه انجام می‌شود.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = cloudDocTitle, onValueChange = { cloudDocTitle = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("عنوان سند") })
                    OutlinedTextField(value = cloudDocContent, onValueChange = { cloudDocContent = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, label = { Text("متن سند") })
                    Text("خروجی فایل (با پشتیبانی از متن فارسی و راست‌به‌چپ)", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(exportStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = cloudDocTitle.isNotBlank() && cloudDocContent.isNotBlank() && !busy, modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { DocumentExport.createPdf(context, cloudDocTitle, cloudDocContent, File(storageRoot, "files/wolf_export.pdf")) } }
                                    .onSuccess { pendingExportFile = it; pdfSaveLauncher.launch(cloudDocTitle.trim().take(50).ifBlank { "WOLF" } + ".pdf") }
                                    .onFailure { exportStatus = "ساخت PDF ناموفق بود: ${it.message}" }
                            }
                        }) { Text("PDF") }
                        OutlinedButton(enabled = cloudDocTitle.isNotBlank() && cloudDocContent.isNotBlank() && !busy, modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { DocumentExport.createDocx(cloudDocTitle, cloudDocContent, File(storageRoot, "files/wolf_export.docx")) } }
                                    .onSuccess { pendingExportFile = it; docxSaveLauncher.launch(cloudDocTitle.trim().take(50).ifBlank { "WOLF" } + ".docx") }
                                    .onFailure { exportStatus = "ساخت Word ناموفق بود: ${it.message}" }
                            }
                        }) { Text("Word") }
                        OutlinedButton(enabled = cloudDocTitle.isNotBlank() && cloudDocContent.isNotBlank() && !busy, modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                runCatching {
                                    val rows = cloudDocContent.lines().filter { it.isNotBlank() }.map { line -> line.split('\t').map(String::trim) }
                                    withContext(Dispatchers.IO) { DocumentExport.createXlsx(rows, File(storageRoot, "files/wolf_export.xlsx")) }
                                }.onSuccess { pendingExportFile = it; xlsxSaveLauncher.launch(cloudDocTitle.trim().take(50).ifBlank { "WOLF" } + ".xlsx") }
                                    .onFailure { exportStatus = "ساخت Excel ناموفق بود: ${it.message}" }
                            }
                        }) { Text("Excel") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = paired && cloudDocTitle.isNotBlank() && cloudDocContent.isNotBlank() && !busy, onClick = {
                            val title = cloudDocTitle.trim()
                            val content = cloudDocContent.trim()
                            cloudDocStatus = "در حال ذخیره سند خصوصی..."
                            scope.launch {
                                runCatching { createArtifact(title, content) }
                                    .onSuccess {
                                        cloudDocTitle = ""; cloudDocContent = ""
                                        cloudDocStatus = "سند «$title» در فضای خصوصی ذخیره شد."
                                        runCatching { listArtifacts() }.onSuccess { remoteArtifacts = it }
                                    }
                                    .onFailure { cloudDocStatus = "ذخیره سند ناموفق بود: ${it.message}" }
                            }
                        }, modifier = Modifier.weight(1f)) { Text("ذخیره در فضای ابری") }
                        OutlinedButton(enabled = paired && !busy, onClick = {
                            cloudDocStatus = "در حال دریافت اسناد..."
                            scope.launch {
                                runCatching { listArtifacts() }
                                    .onSuccess { remoteArtifacts = it; cloudDocStatus = "${it.length()} سند خصوصی دریافت شد." }
                                    .onFailure { cloudDocStatus = "دریافت اسناد ناموفق بود: ${it.message}" }
                            }
                        }, modifier = Modifier.weight(1f)) { Text("به‌روزرسانی") }
                    }
                    Text(cloudDocStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((0 until remoteArtifacts.length()).mapNotNull { remoteArtifacts.optJSONObject(it) }) { artifact ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(artifact.optString("title"), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                                    Text(artifact.optString("excerpt"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    Text(artifact.optString("created_at"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            cloudDocStatus = "در حال دریافت متن سند..."
                                            scope.launch {
                                                runCatching { getArtifact(artifact.optString("id")) }
                                                    .onSuccess { selectedCloudDoc = it.optString("title") to it.optString("content_text"); cloudDocStatus = "متن سند دریافت شد." }
                                                    .onFailure { cloudDocStatus = "دریافت متن سند ناموفق بود: ${it.message}" }
                                            }
                                        }) { Text("بازکردن") }
                                        TextButton(onClick = { pendingCloudDocDelete = JSONObject(artifact.toString()) }) { Text("حذف") }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    WolfSectionLabel("فایل‌های محلی گوشی")
                    var previewText by remember { mutableStateOf("") }
                    var previewFile by remember { mutableStateOf<File?>(null) }
                    val importedFiles = remember(filesRefresh) { File(storageRoot,"files").apply{mkdirs()}.listFiles()?.filter{it.isFile}?.sortedByDescending{it.lastModified()} ?: emptyList() }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(importedFiles) { file ->
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(file.name,color=MaterialTheme.colorScheme.onSurface)
                                        Text("%.1f KB".format(file.length()/1024.0),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick={
                                        val ext=file.extension.lowercase()
                                        previewFile=file
                                        if (ext == "pdf") {
                                            previewText = ""
                                            fileStatus = "در حال استخراج PDF؛ در صورت اسکن بودن، مدل‌های فارسی و انگلیسی فقط در بار اول دریافت می‌شوند و سپس آفلاین کار می‌کنند..."
                                            scope.launch {
                                                val embedded = runCatching { withContext(Dispatchers.IO) { readPdfText(file, context) } }
                                                    .getOrElse { "استخراج PDF ناموفق بود: ${it.message}" }
                                                previewText = if (embedded.contains("متن قابل استخراج پیدا نشد")) {
                                                    runCatching { readScannedPdfText(file, context) }.getOrElse { "OCR ناموفق بود: ${it.message}" }
                                                } else embedded
                                                fileStatus = "نتیجه خواندن: ${file.name}"
                                            }
                                        } else {
                                            previewText = ""
                                            fileStatus = "در حال خواندن فایل..."
                                            scope.launch {
                                                val extracted = withContext(Dispatchers.IO) {
                                                    when (ext) {
                                                        "txt", "md", "csv", "json", "log" ->
                                                            runCatching { file.readText(Charsets.UTF_8).take(12000) }
                                                                .getOrElse { "خواندن فایل ناموفق بود: ${it.message}" }
                                                        "docx" -> runCatching {
                                                            java.util.zip.ZipFile(file).use { zip ->
                                                                val entry = zip.getEntry("word/document.xml")
                                                                if (entry == null) "متن قابل استخراج در این فایل پیدا نشد." else
                                                                    zip.getInputStream(entry).bufferedReader().use { xml ->
                                                                        xml.readText().replace(Regex("</w:p>"), "\n")
                                                                            .replace(Regex("<[^>]+>"), " ")
                                                                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                                                            .replace(Regex("[ \\t]+"), " ").take(12000)
                                                                    }
                                                            }
                                                        }.getOrElse { "استخراج Word ناموفق بود: ${it.message}" }
                                                        "xlsx" -> runCatching { readXlsxText(file) }
                                                            .getOrElse { "استخراج Excel ناموفق بود: ${it.message}" }
                                                        else -> "خواندن داخلی قالب .$ext هنوز اضافه نشده است. فایل اصلی محفوظ است."
                                                    }
                                                }
                                                previewText = extracted
                                                fileStatus = "نتیجه خواندن: ${file.name}"
                                            }
                                        }
                                    }) { Text("خواندن") }
                                    TextButton(onClick={
                                        pendingExportFile=file
                                        exportPicker.launch(file.name)
                                    }) { Text("خروجی") }
                                }
                            }
                        }
                    }
                    if(previewText.isNotBlank()) {
                        WolfSectionLabel("پیش‌نمایش متن")
                        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value=previewText,onValueChange={previewText=it},modifier=Modifier.fillMaxWidth(),minLines=5,maxLines=14,label={Text("متن استخراج‌شده")})
                                val editable = previewFile?.extension?.lowercase() in listOf("txt","md","csv","json","log")
                                if(editable) Button(onClick={
                                    val target = previewFile
                                    val content = previewText
                                    scope.launch {
                                        val saved = runCatching {
                                            withContext(Dispatchers.IO) {
                                                target?.writeText(content, Charsets.UTF_8) ?: error("فایلی انتخاب نشده")
                                            }
                                        }
                                        saved.onSuccess { fileStatus="تغییرات در فایل ذخیره شد." }
                                            .onFailure { fileStatus="ذخیره تغییرات ناموفق بود: ${it.message}" }
                                    }
                                },modifier=Modifier.fillMaxWidth()) { Text("ذخیره تغییرات در فایل") }
                                else Text("ویرایش پیش‌نمایش فقط برای فایل‌های متنی قابل ذخیره است؛ فایل اصلی Word و Excel دست‌نخورده می‌ماند.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if(showSettings) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WolfPageHeader("تنظیمات WOLF","ظاهر، مجوزها و کنترل‌های حریم خصوصی برنامه","PREFERENCES")
                    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("ظاهر برنامه",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) { Text("تم تاریک",color=MaterialTheme.colorScheme.onSurface);Text("انتخاب ظاهر روشن یا تاریک",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
                                Switch(checked=darkTheme,onCheckedChange={darkTheme=it;storagePrefs.safeEdit { putBoolean("dark_theme", it) }})
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected=darkTheme,onClick={darkTheme=true;storagePrefs.safeEdit { putBoolean("dark_theme", true) }},label={Text("تاریک")})
                                FilterChip(selected=!darkTheme,onClick={darkTheme=false;storagePrefs.safeEdit { putBoolean("dark_theme", false) }},label={Text("روشن")})
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},modifier=Modifier.fillMaxWidth()){Text("فعال‌سازی کنترل پیشرفته برنامه‌ها")}
                            Text(if(WolfAccessibilityService.active!=null) "کنترل پیشرفته فعال است." else "برای کنترل عناصر داخل برنامه‌ها، این دسترسی را فقط در صورت تمایل از تنظیمات اندروید فعال کن.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                            if (!paired) OutlinedButton(onClick={localMode=false;storagePrefs.safeEdit { putBoolean("local_mode", false) };showSettings=false;status="برای اتصال ابری، کد اتصال را وارد کن."},modifier=Modifier.fillMaxWidth()) { Text("رفتن به صفحه اتصال موتور ابری") }
                            Text("حریم خصوصی و کنترل",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
                            Text("مجوزها هنگام استفاده از قابلیت مربوطه درخواست می‌شوند. تماس، پیامک و عملیات حساس بدون تأیید مستقیم انجام نمی‌شوند.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("WOLF AI AGENT • نسخه ${BuildConfig.VERSION_NAME}",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                        }
                    }
                    }
                } else if (showContacts) {
                    WolfPageHeader("مخاطبین گوشی","جست‌وجو و دسترسی سریع به تماس و پیامک","CONTACTS")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showContacts = false }) { Text("بازگشت به گفتگو") }
                    }
                    Text(contactsStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(contactSearch, { contactSearch = it }, modifier = Modifier.fillMaxWidth(), label = { Text("جست‌وجوی نام یا شماره") }, singleLine = true)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(contacts.filter { it.first.contains(contactSearch, true) || it.second.contains(contactSearch, true) }) { item ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.first, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                        Text(item.second, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL).setData(android.net.Uri.parse("tel:" + android.net.Uri.encode(item.second)))) }) { Text("تماس") }
                                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO).setData(android.net.Uri.parse("smsto:" + android.net.Uri.encode(item.second)))) }) { Text("پیامک") }
                                }
                            }
                        }
                    }
                    Text("WOLF فقط شماره‌ها را می‌خواند؛ تماس و پیامک در برنامه پیش‌فرض گوشی باز می‌شوند و ارسال خودکار انجام نمی‌شود.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else if (showMissions) {
                    WolfPageHeader("مأموریت‌های هوشمند","درخواست‌های چندمرحله‌ای را بررسی و با تأیید خودت اجرا کن","MISSIONS")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showMissions = false }) { Text("بازگشت به گفتگو") }
                    }
                    Text("طرح‌ها و مراحل در این صفحه قابل خواندن و پیمایش هستند. هیچ اقدام بیرونی بدون تأیید تو اجرا نمی‌شود.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(missionText, { missionText = it }, modifier = Modifier.fillMaxWidth(), enabled = masterEnabled && !missionBusy, label = { Text("چه کاری انجام بدهم؟") }, minLines = 2, maxLines = 4)
                    Button(enabled = masterEnabled && missionText.isNotBlank() && !missionBusy, onClick = { val request = missionText.trim(); missionBusy = true; missionStatus = "در حال ساخت طرح امن..."; scope.launch { runCatching { createMission(request) }.onSuccess { mission -> missionText = ""; missionStatus = "طرح ذخیره شد و همه مراحل در انتظار تأیید هستند."; missions = JSONArray().put(mission).also { current -> for (i in 0 until missions.length()) current.put(missions.getJSONObject(i)) } }.onFailure { missionStatus = it.message ?: "ساخت مأموریت ناموفق بود" }; missionBusy = false } }) { Text(if (missionBusy) "در حال برنامه‌ریزی..." else "ساخت طرح مأموریت") }
                    Text(missionStatus, color = Cyan)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0 until missions.length()).map { missions.getJSONObject(it) }) { mission ->
                            val plan = mission.optJSONObject("plan") ?: JSONObject()
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(plan.optString("summary", mission.optString("request", "مأموریت")), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                    Text("وضعیت: " + mission.optString("status", "awaiting_approval"), color = Gold)
                                    val steps = plan.optJSONArray("steps") ?: JSONArray()
                                    val stepStates = mission.optJSONArray("steps") ?: JSONArray()
                                    for (i in 0 until steps.length()) {
                                        val step = steps.optJSONObject(i) ?: JSONObject()
                                        val state = stepStates.optJSONObject(i) ?: JSONObject()
                                        val stepStatus = state.optString("status", "awaiting_approval")
                                        val stepNo = i + 1
                                        HorizontalDivider(color = Color(0xFF33445E), thickness = 1.dp)
                                        Text("مرحله $stepNo: ${step.optString("title")}", color = Cyan, style = MaterialTheme.typography.titleMedium)
                                        Text(step.optString("details"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                                        Text("قابلیت: ${step.optString("capability")}\nوضعیت: $stepStatus", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                                        state.optJSONObject("prepared_action")?.let { prepared ->
                                            Text("عملیات دقیق آماده‌شده: " + prepared.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (mission.optString("status") == "execution_failed" && stepStatus == "failed") {
                                            OutlinedButton(enabled = masterEnabled && !missionBusy, onClick = {
                                                missionBusy = true; missionStatus = "در حال بازنشانی امن مرحله $stepNo..."
                                                scope.launch {
                                                    runCatching { retryMissionStep(mission.optString("id"), stepNo) }
                                                        .onSuccess { missionStatus = "مرحله برای آماده‌سازی دوباره برگشت؛ اجرای مجدد بدون تأیید جداگانه ممکن نیست." }
                                                        .onFailure { missionStatus = it.message ?: "بازنشانی مرحله ناموفق بود" }
                                                    runCatching { listMissions() }.onSuccess { missions = it }
                                                    missionBusy = false
                                                }
                                            }) { Text("آماده‌سازی مجدد مرحله ناموفق") }
                                        }
                                        if (mission.optString("status") == "approved_pending_execution" && stepStatus == "approved_pending_execution") {
                                            OutlinedButton(enabled = masterEnabled && !missionBusy, onClick = {
                                                missionBusy = true; missionStatus = "در حال آماده‌سازی عملیات خواندنی مرحله $stepNo..."
                                                scope.launch {
                                                    runCatching { prepareMissionStep(mission.optString("id"), stepNo) }
                                                        .onSuccess { missionStatus = "عملیات آماده شد. جزئیات آن را بررسی کن؛ هنوز اجرا نشده است." }
                                                        .onFailure { missionStatus = it.message ?: "آماده‌سازی ناموفق بود" }
                                                    runCatching { listMissions() }.onSuccess { missions = it }
                                                    missionBusy = false
                                                }
                                            }) { Text("آماده‌سازی عملیات") }
                                        } else if (stepStatus == "awaiting_execution_approval") {
                                            Button(enabled = masterEnabled && !missionBusy, onClick = {
                                                missionBusy = true; missionStatus = "در حال اجرای عملیات خواندنی تأییدشده..."
                                                scope.launch {
                                                    runCatching { executeMissionStep(mission.optString("id"), stepNo) }
                                                        .onSuccess { missionStatus = "مرحله اجرا شد و نتیجه ثبت شد." }
                                                        .onFailure { missionStatus = it.message ?: "اجرای مرحله ناموفق بود" }
                                                    runCatching { listMissions() }.onSuccess { missions = it }
                                                    missionBusy = false
                                                }
                                            }) { Text("تأیید و اجرای همین عملیات") }
                                        }
                                    }
                                    if (mission.optString("status") == "awaiting_approval") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(enabled = masterEnabled && !missionBusy, onClick = { missionBusy = true; scope.launch { runCatching { decideMission(mission.optString("id"), "approve") }.onSuccess { missionStatus = "طرح تأیید شد؛ هنوز اجرا نشده است." }.onFailure { missionStatus = it.message ?: "تأیید ناموفق بود" }; runCatching { listMissions() }.onSuccess { missions = it }; missionBusy = false } }) { Text("تأیید طرح") }
                                            OutlinedButton(enabled = masterEnabled && !missionBusy, onClick = { missionBusy = true; scope.launch { runCatching { decideMission(mission.optString("id"), "reject") }.onSuccess { missionStatus = "طرح رد شد." }.onFailure { missionStatus = it.message ?: "رد طرح ناموفق بود" }; runCatching { listMissions() }.onSuccess { missions = it }; missionBusy = false } }) { Text("رد طرح") }
                                        }
                                    } else if (mission.optString("status") == "approved_pending_execution") {
                                        Text("تأیید شده، اما هنوز اجرا نشده است.", color = Gold)
                                        OutlinedButton(enabled = masterEnabled && !missionBusy, onClick = {
                                            missionBusy = true
                                            scope.launch {
                                                runCatching { controlMission(mission.optString("id"), "pause") }
                                                    .onSuccess { missionStatus = "مأموریت متوقف شد؛ هیچ مرحله فعالی در حال اجرا نبود." }
                                                    .onFailure { missionStatus = it.message ?: "توقف مأموریت ناموفق بود" }
                                                runCatching { listMissions() }.onSuccess { missions = it }
                                                missionBusy = false
                                            }
                                        }) { Text("توقف موقت مأموریت") }
                                    } else if (mission.optString("status") == "paused") {
                                        Text("مأموریت متوقف است و هیچ مرحله جدیدی اجرا نمی‌شود.", color = Gold)
                                        Button(enabled = masterEnabled && !missionBusy, onClick = {
                                            missionBusy = true
                                            scope.launch {
                                                runCatching { controlMission(mission.optString("id"), "resume") }
                                                    .onSuccess { missionStatus = "مأموریت ادامه یافت؛ مراحل همچنان به تأیید جداگانه نیاز دارند." }
                                                    .onFailure { missionStatus = it.message ?: "ادامه مأموریت ناموفق بود" }
                                                runCatching { listMissions() }.onSuccess { missions = it }
                                                missionBusy = false
                                            }
                                        }) { Text("ادامه مأموریت") }
                                    }
                                }
                            }
                        }
                    }
                } else if (showModels) {
                    WolfPageHeader("موتورهای هوش مصنوعی","وضعیت اتصال و موتور فعال را مدیریت کن","AI ENGINES")
                    Text("وضعیت زیر از خود Worker خوانده می‌شود؛ موتورِ بدون اتصال قابل انتخاب نیست.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (providerStatusBusy) "در حال بررسی..." else if (providerStatus != null) "وضعیت سرور دریافت شد" else providerStatusError, color = if (providerStatus != null) Cyan else Gold, modifier = Modifier.weight(1f))
                        OutlinedButton(enabled = !providerStatusBusy && paired, onClick = {
                            scope.launch {
                                providerStatusBusy = true
                                runCatching { loadProviderStatus() }
                                    .onSuccess { acceptProviderStatus(it) }
                                    .onFailure { providerStatusError = it.message ?: "خطا در دریافت وضعیت" }
                                providerStatusBusy = false
                            }
                        }) { Text("تازه‌سازی") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto" to "خودکار", "zai" to "Z.AI رایگان", "groq" to "Groq رایگان").forEach { (key, label) ->
                            FilterChip(selected = selectedModel == key, enabled = providerIsConfigured(key), onClick = { selectedModel = key; storagePrefs.safeEdit { putString("ai_model", key) } }, label = { Text(label) })
                        }
                    }
                    val providerItems = providerStatus?.optJSONArray("providers") ?: JSONArray()
                    for (i in 0 until providerItems.length()) {
                        val item = providerItems.optJSONObject(i) ?: continue
                        if (item.optString("id") == "auto" || item.optString("id") == "deepseek") continue
                        Text((item.optString("label") + " | " + if (item.optBoolean("configured")) "پیکربندی شده" else "نیازمند اتصال یا کلید"), color = if (item.optBoolean("configured")) Cyan else Gold, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("حالت رایگان فقط از Z.AI و Groq استفاده می‌کند. سهمیه هر دو محدود است؛ با تمام شدن سهمیه، برنامه خطا می‌دهد و به سرویس پولی تغییر مسیر نمی‌دهد. کلیدها فقط در Cloudflare نگهداری می‌شوند.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("مدل انتخاب‌شده: " + when(selectedModel) { "zai" -> "Z.AI GLM-4.7-Flash رایگان"; "groq" -> "Groq GPT-OSS 120B رایگان"; else -> "خودکار، فقط رایگان" }, color = Cyan)
                }
                else if (showStorage) {
                    WolfPageHeader("حافظه و یادداشت‌ها","فضای محلی، یادداشت‌ها و فایل‌های ذخیره‌شده را مدیریت کن","PRIVATE STORAGE")
                    Text("مصرف‌شده: %.2f از %.0f گیگابایت".format(usedGb, quotaGb), color = Cyan)
                    LinearProgressIndicator(progress = { usagePercent }, modifier = Modifier.fillMaxWidth())
                    if (usagePercent >= 0.9f || phoneFreeGb < 1.0) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF633333))) {
                            Column(Modifier.padding(12.dp)) {
                                Text("هشدار کمبود حافظه", color = Color.White)
                                Text("فضا رو به اتمام است. فایل‌های غیرضروری را پاک کن یا سقف مصرف را افزایش بده. فضای آزاد واقعی گوشی: %.1f GB".format(phoneFreeGb), color = Color.White)
                            }
                        }
                    } else if (usagePercent >= 0.8f) {
                        Text("هشدار: بیش از ۸۰٪ سهم حافظه مصرف شده است.", color = Gold)
                    }
                    Text("سقف مصرف WOLF: %.0f GB".format(quotaGb), color = MaterialTheme.colorScheme.onSurface)
                    Slider(value = quotaGb, onValueChange = { quotaGb = it }, valueRange = 20f..200f, steps = 17)
                    Button(onClick = { storagePrefs.safeEdit { putInt("storage_quota_gb", quotaGb.toInt()) }; status = "سقف حافظه ذخیره شد" }) { Text("ذخیره سقف جدید") }
                    Text("فضای آزاد قابل استفاده گوشی: %.1f GB".format(phoneFreeGb), color = MaterialTheme.colorScheme.onSurfaceVariant)

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    WolfSectionLabel("حافظه بلندمدت خصوصی")
                    Text("این موارد در حساب مالک روی سرور ذخیره شده‌اند و با یادداشت‌ها یا فایل‌های محلی گوشی فرق دارند.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = paired && !busy, onClick = {
                            remoteMemoryStatus = "در حال دریافت حافظه خصوصی..."
                            scope.launch {
                                runCatching { listMemory() }
                                    .onSuccess { remoteMemory = it; remoteMemoryStatus = "${it.length()} مورد حافظه دریافت شد." }
                                    .onFailure { remoteMemoryStatus = "دریافت حافظه ناموفق بود: ${it.message}" }
                            }
                        }) { Text("به‌روزرسانی حافظه") }
                        Text(remoteMemoryStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = remoteMemorySearch, onValueChange = { remoteMemorySearch = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("جست‌وجو در حافظه بلندمدت") })
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((0 until remoteMemory.length()).mapNotNull { remoteMemory.optJSONObject(it) }.filter { item ->
                            val q = remoteMemorySearch.trim()
                            q.isBlank() || item.optString("key").contains(q, true) || item.optString("value").contains(q, true) || item.optString("category").contains(q, true)
                        }) { item ->
                            val key = item.optString("key")
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(key, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { pendingMemoryDelete = key }) { Text("حذف") }
                                    }
                                    Text(item.optString("value"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    Text(item.optString("category"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    WolfSectionLabel("یادداشت‌های شخصی")
                    Text(noteStatus, color = Cyan, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("عنوان یادداشت") }, singleLine = true)
                    OutlinedTextField(value = noteBody, onValueChange = { noteBody = it }, modifier = Modifier.fillMaxWidth(), label = { Text("متن یادداشت") }, minLines = 3, maxLines = 6)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = noteTitle.isNotBlank() && noteBody.isNotBlank(), onClick = {
                            runCatching {
                                val dir = File(storageRoot, "notes").apply { mkdirs() }
                                val target = editingNote ?: File(dir, "note_${System.currentTimeMillis()}.md")
                                val safeTitle = noteTitle.trim().replace("\n", " ").take(120)
                                val content = "# " + safeTitle + "\n\n" + noteBody.trim() + "\n"
                                val temporary = File(dir, target.name + ".tmp")
                                temporary.writeText(content, Charsets.UTF_8)
                                if (!temporary.renameTo(target)) { target.writeText(content, Charsets.UTF_8); temporary.delete() }
                            }.onSuccess {
                                editingNote = null
                                noteTitle = ""; noteBody = ""
                                notesRefresh++; storageRefresh++
                                noteStatus = "یادداشت ذخیره شد."
                            }.onFailure { noteStatus = "ذخیره یادداشت ناموفق بود: ${it.message}" }
                        }, modifier = Modifier.weight(1f)) { Text(if (editingNote == null) "ذخیره یادداشت" else "ذخیره تغییرات") }
                        OutlinedButton(onClick = { editingNote = null; noteTitle = ""; noteBody = "" }, modifier = Modifier.weight(1f)) { Text("پاک‌کردن فرم") }
                    }
                    OutlinedTextField(value = noteSearch, onValueChange = { noteSearch = it }, modifier = Modifier.fillMaxWidth(), label = { Text("جست‌وجو در یادداشت‌ها") }, singleLine = true)
                    val savedNotes = remember(notesRefresh) { File(storageRoot, "notes").apply { mkdirs() }.listFiles()?.filter { it.isFile && it.extension == "md" }?.sortedByDescending { it.lastModified() } ?: emptyList() }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(savedNotes.filter { file -> file.readText(Charsets.UTF_8).contains(noteSearch, ignoreCase = true) }) { file ->
                            val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                            val title = raw.lineSequence().firstOrNull()?.removePrefix("# ")?.ifBlank { file.nameWithoutExtension } ?: file.nameWithoutExtension
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                                    Text(raw.lineSequence().drop(2).joinToString("\n").take(240), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            editingNote = file
                                            noteTitle = title
                                            noteBody = raw.lineSequence().drop(2).joinToString("\n").trim()
                                            noteStatus = "یادداشت برای ویرایش بارگذاری شد."
                                        }) { Text("ویرایش") }
                                        TextButton(onClick = {
                                            runCatching { file.delete() }.onSuccess { notesRefresh++; storageRefresh++; noteStatus = "یادداشت حذف شد." }.onFailure { noteStatus = "حذف یادداشت ناموفق بود." }
                                        }) { Text("حذف", color = Color(0xFFFF7777)) }
                                    }
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(storageBuckets) { bucket ->
                            val (key, pair) = bucket
                            val (label, dir) = pair
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, color = MaterialTheme.colorScheme.onSurface)
                                        Text("%.2f GB".format(folderBytes(dir) / (1024.0 * 1024 * 1024)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (key == "files" || key == "audio") {
                                        TextButton(onClick = { pendingDeleteBucket = label to dir }) { Text("پاک‌سازی") }
                                    }
                                }
                            }
                        }
                    }
                    Text("پاک‌سازی فقط فایل‌ها یا صداها را حذف می‌کند. تاریخچه و حافظه بلندمدت دست‌نخورده می‌مانند. افزایش سقف، ظرفیت فیزیکی گوشی را زیاد نمی‌کند.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else if (!paired && !localMode) {
                    Text("اتصال امن به WOLF", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(pairCode, { pairCode = it.filter(Char::isDigit).take(6) }, label = { Text("کد ۶ رقمی تلگرام") }, singleLine = true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                    Button(enabled = pairCode.length == 6 && !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val newToken = withContext(Dispatchers.IO) {
                                    val c = URL("$BASE_URL/api/companion/pair").openConnection() as HttpURLConnection
                                    try {
                                        c.requestMethod="POST"; c.connectTimeout=15000; c.readTimeout=20000
                                        c.setRequestProperty("Content-Type","application/json"); c.doOutput=true
                                        c.outputStream.use { it.write(JSONObject().put("code",pairCode).put("device_name","WOLF AI Android").toString().toByteArray()) }
                                        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()}
                                        val o=JSONObject(raw); if(!o.optBoolean("ok")) error(o.optString("error","اتصال ناموفق"))
                                        o.getString("token")
                                    } finally { c.disconnect() }
                                }
                                saveToken(newToken); paired=true; status="دستگاه با موفقیت متصل شد"
                            } catch(e:Exception) { status=e.message ?: "خطا در اتصال" }
                            busy=false
                        }
                    }) { Text(if (busy) "در حال اتصال..." else "اتصال WOLF") }
                    Text("برای اتصال موتور ابری، کد یک‌بارمصرف را از روش اتصال مالک دریافت کن. این اتصال فقط برای قابلیت‌های آنلاین است.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick={localMode=true;storagePrefs.safeEdit { putBoolean("local_mode", true) };status="حالت محلی فعال است؛ ابزارهای گوشی بدون تلگرام کار می‌کنند."},modifier=Modifier.fillMaxWidth()) { Text("ادامه بدون تلگرام، حالت محلی") }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(status, color=Cyan, modifier = Modifier.weight(1f))
                        TextButton(enabled = chat.isNotEmpty(), onClick = { showClearChatConfirm = true }) {
                            Text("پاک کردن گفتگو", color = Color(0xFFFF7777))
                        }
                    }
                    if (chat.isEmpty()) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text("سلام، من WOLF هستم.", color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("درخواستت را بنویس. می‌توانم برنامه‌ریزی کنم، تحقیق کنم، فایل‌ها را بررسی کنم و کارها را مرحله‌به‌مرحله با تأیید تو پیش ببرم.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 23.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("تحقیق و وب", "مدیریت کارها", "فایل و اسناد").forEach { item ->
                                        Surface(shape = RoundedCornerShape(50), color = Color(0xFF123C59)) {
                                            Text(item, Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                color = Color(0xFF9DE8FF), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(chat) { item ->
                            val isUser = item.first == "تو"
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Card(
                                    Modifier.fillMaxWidth(if (isUser) 0.88f else 0.96f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) Color(0xFF123C59) else MaterialTheme.colorScheme.surface
                                    ),
                                    shape = RoundedCornerShape(
                                        topStart = 19.dp, topEnd = 19.dp,
                                        bottomEnd = if (isUser) 5.dp else 19.dp,
                                        bottomStart = if (isUser) 19.dp else 5.dp
                                    ),
                                    border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(if (isUser) "شما" else "WOLF AI",
                                            color = if (isUser) Color(0xFF79DFFF) else MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium)
                                        Text(item.second, color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                                    }
                                }
                            }
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = masterEnabled && !busy,
                            label = { Text("پیامت را برای WOLF بنویس...") },
                            maxLines = 4,
                            shape = RoundedCornerShape(16.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            enabled = masterEnabled && !busy,
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "با WOLF صحبت کن")
                                }
                                speechLauncher.launch(intent)
                            }
                        ) { Icon(Icons.Default.Mic, contentDescription = "ضبط صدا") }
                        OutlinedButton(
                            onClick = { stopSpeaking(); status = "پخش صدای WOLF متوقف شد." },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("توقف صدا") }
                        FilledIconButton(
                            enabled = masterEnabled && message.isNotBlank() && !busy,
                            onClick = {
                                val q = message.trim()
                                message = ""
                                chat.add("تو" to q)
                                persistChat()
                                val localResult = runCatching { runLocalCommand(q) }.getOrElse { "اجرای فرمان ناموفق بود: " + (it.message ?: "خطا") }
                                if (localResult != null) { chat.add("WOLF" to localResult); persistChat(); speak(localResult); status = "فرمان گوشی اجرا شد" } else if (!paired) { chat.add("WOLF" to "برای پاسخ هوشمند و مأموریت‌های آنلاین باید اتصال ابری را فعال کنی. فرمان‌های محلی گوشی، فایل‌ها، مخاطبین، هشدار و تقویم بدون تلگرام در دسترس‌اند."); persistChat(); status="حالت محلی: اتصال اینترنتی هوش مصنوعی فعال نیست." } else {
                                busy = true
                                status = "WOLF در حال فکر کردن..."
                                activeRequest = scope.launch {
                                    try {
                                        val prior = chat.dropLast(1).takeLast(10).toList()
                                        val a = send(q, prior, selectedModel)
                                        if (masterEnabled) {
                                            chat.add("WOLF" to a)
                                            persistChat()
                                            speak(a)
                                            status = "آماده گفتگو"
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        status = "درخواست متوقف شد"
                                        throw e
                                    } catch (e: Exception) {
                                        status = e.message ?: "ارتباط برقرار نشد"
                                    } finally {
                                        busy = false
                                        activeRequest = null
                                    }
                                }
                                }
                            }
                        ) { Icon(Icons.Default.Send, contentDescription = "ارسال پیام") }

                        }
                        }
                    }
                    Text("میکروفون فقط با لمس دکمه فعال می‌شود. صدای خام ذخیره نمی‌شود.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall)
                }
                    }
                }
            }
        }
    }
}
