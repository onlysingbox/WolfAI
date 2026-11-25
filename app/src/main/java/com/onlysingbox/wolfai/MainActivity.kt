package com.onlysingbox.wolfai
import android.Manifest
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
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import android.content.SharedPreferences
import java.util.Locale
import org.json.JSONArray

private const val BASE_URL = "https://wolfai.onlysingbox.workers.dev"
private val Navy = Color(0xFF071426)
private val Panel = Color(0xFF10243D)
private val Cyan = Color(0xFF18C8FF)
private val Gold = Color(0xFFFFC928)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var securePrefs: android.content.SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val master = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(this, "wolf_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        File(filesDir, "wolf_storage").apply { listOf("history", "memory", "notes", "files", "audio").forEach { File(this, it).mkdirs() } }
        setContent { WolfApp(
            token = securePrefs.getString("device_token", "") ?: "",
            saveToken = { securePrefs.edit().putString("device_token", it).apply() },
            send = { text, history, model -> apiChat(securePrefs.getString("device_token", "") ?: "", text, history, model) },
            createMission = { text -> apiCreateMission(securePrefs.getString("device_token", "") ?: "", text) },
            listMissions = { apiListMissions(securePrefs.getString("device_token", "") ?: "") },
            decideMission = { id, decision -> apiMissionDecision(securePrefs.getString("device_token", "") ?: "", id, decision) },
            prepareMissionStep = { id, step -> apiMissionStep(securePrefs.getString("device_token", "") ?: "", id, step, "prepare") },
            executeMissionStep = { id, step -> apiMissionStep(securePrefs.getString("device_token", "") ?: "", id, step, "execute") },
            setRemoteMaster = { enabled -> apiMaster(securePrefs.getString("device_token", "") ?: "", enabled) },
            loadProviderStatus = { apiProviderStatus(securePrefs.getString("device_token", "") ?: "") },
            speak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wolf-reply") },
            storagePrefs = securePrefs,
            storageRoot = File(filesDir, "wolf_storage"),
            historyFile = File(filesDir, "wolf_storage/history/chat.json"),
            loadContacts = { readPhoneContacts() }
        ) }
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
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR") }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }

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
private fun NavigationChip(label:String, selected:Boolean, onClick:()->Unit) {
    FilterChip(selected=selected,onClick=onClick,label={Text(label)},shape=RoundedCornerShape(14.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WolfApp(token: String, saveToken: (String)->Unit, send: suspend (String, List<Pair<String,String>>, String)->String, createMission: suspend (String)->JSONObject, listMissions: suspend ()->JSONArray, decideMission: suspend (String, String)->JSONObject, prepareMissionStep: suspend (String, Int)->JSONObject, executeMissionStep: suspend (String, Int)->JSONObject, setRemoteMaster: suspend (Boolean)->Unit, loadProviderStatus: suspend ()->JSONObject, speak: (String)->Unit, storagePrefs: SharedPreferences, storageRoot: File, historyFile: File, loadContacts: suspend () -> List<Pair<String,String>>) {
    var paired by remember { mutableStateOf(token.isNotBlank()) }
    var pairCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("آماده گفتگو") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showStorage by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var showPlanner by remember { mutableStateOf(false) }
    var eventTitle by remember { mutableStateOf("") }
    var eventNotes by remember { mutableStateOf("") }
    var plannerStatus by remember { mutableStateOf("برای ساخت رویداد، عنوان و توضیحات را وارد کن.") }
    var filesRefresh by remember { mutableIntStateOf(0) }
    var fileStatus by remember { mutableStateOf("فایل‌ها را از حافظه گوشی وارد فضای امن WOLF کن.") }
    var showContacts by remember { mutableStateOf(false) }
    var showDeviceTools by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var alarmHour by remember { mutableStateOf("7") }
    var alarmMinute by remember { mutableStateOf("00") }
    var deviceStatus by remember { mutableStateOf("اجرای برنامه‌ها و تنظیم هشدار گوشی") }
    val launchableApps = remember(showDeviceTools) { if (!showDeviceTools) emptyList() else context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(context.packageManager).toString().lowercase() } }
    var contacts by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var contactSearch by remember { mutableStateOf("") }
    var contactsStatus by remember { mutableStateOf("برای مشاهده مخاطبین، مجوز گوشی لازم است.") }
    var showMissions by remember { mutableStateOf(false) }
    var missionText by remember { mutableStateOf("") }
    var missionBusy by remember { mutableStateOf(false) }
    var missionStatus by remember { mutableStateOf("برای ساخت طرح مأموریت، درخواستت را بنویس.") }
    var missions by remember { mutableStateOf(JSONArray()) }
    var selectedModel by remember { mutableStateOf(storagePrefs.getString("ai_model", "auto") ?: "auto") }
    var providerStatus by remember { mutableStateOf<JSONObject?>(null) }
    var providerStatusError by remember { mutableStateOf("وضعیت موتورها هنوز دریافت نشده است.") }
    var providerStatusBusy by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var darkTheme by remember { mutableStateOf(storagePrefs.getBoolean("dark_theme", true)) }
    var masterEnabled by remember { mutableStateOf(storagePrefs.getBoolean("master_enabled", true)) }
    var storageRefresh by remember { mutableIntStateOf(0) }
    var pendingDeleteBucket by remember { mutableStateOf<Pair<String, File>?>(null) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    var quotaGb by remember { mutableFloatStateOf(storagePrefs.getInt("storage_quota_gb", 60).toFloat()) }
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
    LaunchedEffect(paired) {
        if (paired) {
            providerStatusBusy = true
            runCatching { loadProviderStatus() }
                .onSuccess { providerStatus = it; providerStatusError = "" }
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
        if (command.contains("تماس با") || command.contains("شماره گیری")) {
            val number = Regex("[+]?\\d[\\d ()-]{5,}").find(raw)?.value
            if (number != null) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))); return "صفحه تماس باز شد؛ تماس نهایی با تأیید خودت انجام می‌شود." }
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
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
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
                fileStatus = "فایل «${target.name}» وارد شد."
                filesRefresh++
            }.onFailure { fileStatus = "واردکردن فایل ناموفق بود: ${it.message}" }
        }
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
        Scaffold(containerColor=MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🐺 WOLF AI",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.onBackground)
                Text("AGENT  •  دستیار هوشمند شخصی",color=MaterialTheme.colorScheme.secondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(masterEnabled) "WOLF فعال است" else "فعالیت‌های خودکار متوقف است", color=if(masterEnabled) Cyan else Gold, style=MaterialTheme.typography.labelLarge)
                        Text(status, color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall, maxLines=1)
                    }
                    Button(onClick={
                        masterEnabled=!masterEnabled
                        storagePrefs.edit().putBoolean("master_enabled",masterEnabled).apply()
                        if(!masterEnabled){activeRequest?.cancel();activeRequest=null;busy=false;status="WOLF خاموش شد"} else status="WOLF فعال شد"
                        scope.launch { runCatching { setRemoteMaster(masterEnabled) }.onFailure { status="همگام‌سازی ناموفق: ${it.message}" } }
                    }, colors=ButtonDefaults.buttonColors(containerColor=if(masterEnabled) Color(0xFF9B3030) else Color(0xFF16734B))) { Text(if(masterEnabled) "خاموش" else "فعال‌سازی") }
                }
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(vertical=4.dp)) {
                    item { NavigationChip("گفتگو",!showStorage&&!showContacts&&!showDeviceTools&&!showModels&&!showMissions&&!showSettings) { showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("مخاطبین",showContacts) { showContacts=true;showStorage=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false; if(context.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED) scope.launch { runCatching { loadContacts() }.onSuccess { contacts=it;contactsStatus="${it.size} شماره مخاطب خوانده شد." }.onFailure { contactsStatus=it.message ?: "خطا در خواندن مخاطبین" } } else contactsPermission.launch(Manifest.permission.READ_CONTACTS) } }
                    item { NavigationChip("ابزارهای گوشی",showDeviceTools) { showDeviceTools=true;showStorage=false;showContacts=false;showModels=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("مأموریت‌ها",showMissions) { showMissions=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showSettings=false;scope.launch { runCatching { listMissions() }.onSuccess { missions=it }.onFailure { missionStatus=it.message ?: "خطا در دریافت مأموریت‌ها" } } } }
                    item { NavigationChip("موتورها",showModels) { showModels=true;showStorage=false;showContacts=false;showDeviceTools=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("تقویم و یادآور",showPlanner) { showPlanner=true;showFiles=false;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("فایل‌ها",showFiles) { showFiles=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("حافظه",showStorage) { showStorage=true;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false;showSettings=false } }
                    item { NavigationChip("تنظیمات",showSettings) { showSettings=true;showStorage=false;showContacts=false;showDeviceTools=false;showModels=false;showMissions=false } }
                }
                if(showDeviceTools) {
                    Text("کنترل برنامه‌ها و گوشی",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleLarge)
                    Text(deviceStatus,color=Cyan)
                    Text("تنظیم هشدار",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(alarmHour,{alarmHour=it.filter(Char::isDigit).take(2)},modifier=Modifier.weight(1f),label={Text("ساعت")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        OutlinedTextField(alarmMinute,{alarmMinute=it.filter(Char::isDigit).take(2)},modifier=Modifier.weight(1f),label={Text("دقیقه")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        Button(enabled=(alarmHour.toIntOrNull()?:99) in 0..23&&(alarmMinute.toIntOrNull()?:99) in 0..59,onClick={
                            val alarm=Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,alarmHour.toInt()).putExtra(AlarmClock.EXTRA_MINUTES,alarmMinute.toInt()).putExtra(AlarmClock.EXTRA_MESSAGE,"هشدار از WOLF").putExtra(AlarmClock.EXTRA_SKIP_UI,false)
                            if(alarm.resolveActivity(context.packageManager)!=null) context.startActivity(alarm) else deviceStatus="برنامه ساعت پیدا نشد."
                        }) { Text("تنظیم") }
                    }
                    HorizontalDivider()
                    Text("برنامه‌های نصب‌شده",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
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
                if(showPlanner) {
                    Text("تقویم و برنامه‌ریزی",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.headlineSmall)
                    Text("رویداد و قرار را آماده کن؛ ثبت نهایی در تقویم گوشی با تأیید خودت انجام می‌شود.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(eventTitle,{eventTitle=it},modifier=Modifier.fillMaxWidth(),label={Text("عنوان رویداد")},singleLine=true)
                    OutlinedTextField(eventNotes,{eventNotes=it},modifier=Modifier.fillMaxWidth(),label={Text("توضیحات")},minLines=3)
                    Button(enabled=eventTitle.isNotBlank(),onClick={
                        val begin=System.currentTimeMillis()+60L*60L*1000L
                        val finish=begin+60L*60L*1000L
                        val intent=Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE,eventTitle.trim())
                            .putExtra(CalendarContract.Events.DESCRIPTION,eventNotes.trim())
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,finish)
                        if(intent.resolveActivity(context.packageManager)!=null){context.startActivity(intent);plannerStatus="فرم رویداد در تقویم باز شد؛ تاریخ و ساعت را بررسی و ذخیره کن."}
                        else plannerStatus="برنامه تقویم سازگار پیدا نشد."
                    },modifier=Modifier.fillMaxWidth()){Text("افزودن به تقویم")}
                    OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))},modifier=Modifier.fillMaxWidth()){Text("تنظیم دسترسی اعلان‌ها")}
                    Text("دسترسی اعلان‌ها فقط پس از فعال‌سازی دستی در تنظیمات گوشی کار می‌کند. این دسترسی به معنی خواندن تاریخچه کامل پیام‌رسان‌ها نیست.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    Text(plannerStatus,color=MaterialTheme.colorScheme.primary)
                } else if(showFiles) {
                    Text("مدیریت فایل‌ها و اسناد",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.headlineSmall)
                    Text("PDF، Word، Excel، تصویر، صوت و فایل‌های متنی را وارد کن. فایل اصلی در فضای خصوصی WOLF کپی می‌شود.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    Button(onClick={filePicker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) { Text("انتخاب فایل از گوشی") }
                    Text(fileStatus,color=MaterialTheme.colorScheme.primary)
                    val importedFiles = remember(filesRefresh) { File(storageRoot,"files").apply{mkdirs()}.listFiles()?.filter{it.isFile}?.sortedByDescending{it.lastModified()} ?: emptyList() }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(importedFiles) { file ->
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(file.name,color=MaterialTheme.colorScheme.onSurface)
                                        Text("%.1f KB".format(file.length()/1024.0),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick={fileStatus="برای بازکردن فایل، از برنامه مدیریت فایل گوشی استفاده کن؛ نسخه داخلی خواندن این قالب هنوز اضافه نشده است."}) { Text("جزئیات") }
                                }
                            }
                        }
                    }
                } else if(showSettings) {
                    Text("تنظیمات برنامه",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("ظاهر برنامه",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) { Text("تم تاریک",color=MaterialTheme.colorScheme.onSurface);Text("انتخاب ظاهر روشن یا تاریک",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
                                Switch(checked=darkTheme,onCheckedChange={darkTheme=it;storagePrefs.edit().putBoolean("dark_theme",it).apply()})
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected=darkTheme,onClick={darkTheme=true;storagePrefs.edit().putBoolean("dark_theme",true).apply()},label={Text("تاریک")})
                                FilterChip(selected=!darkTheme,onClick={darkTheme=false;storagePrefs.edit().putBoolean("dark_theme",false).apply()},label={Text("روشن")})
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("حریم خصوصی و کنترل",color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
                            Text("مجوزها هنگام استفاده از قابلیت مربوطه درخواست می‌شوند. تماس، پیامک و عملیات حساس بدون تأیید مستقیم انجام نمی‌شوند.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("WOLF AI AGENT • نسخه 0.3",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                        }
                    }
                } else if (showContacts) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("مخاطبین گوشی", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { showContacts = false }) { Text("بازگشت") }
                    }
                    Text(contactsStatus, color = Cyan)
                    OutlinedTextField(contactSearch, { contactSearch = it }, modifier = Modifier.fillMaxWidth(), label = { Text("جست‌وجوی نام یا شماره") }, singleLine = true)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(contacts.filter { it.first.contains(contactSearch, true) || it.second.contains(contactSearch, true) }) { item ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.first, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                        Text(item.second, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL).setData(android.net.Uri.parse("tel:" + android.net.Uri.encode(item.second)))) }) { Text("تماس") }
                                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO).setData(android.net.Uri.parse("smsto:" + android.net.Uri.encode(item.second)))) }) { Text("پیامک") }
                                }
                            }
                        }
                    }
                    Text("WOLF فقط شماره‌ها را می‌خواند؛ تماس و پیامک در برنامه پیش‌فرض گوشی باز می‌شوند و ارسال خودکار انجام نمی‌شود.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                } else if (showMissions) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("مأموریت‌های WOLF", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { showMissions = false }) { Text("بازگشت") }
                    }
                    Text("طرح‌ها و مراحل در این صفحه قابل خواندن و پیمایش هستند. هیچ اقدام بیرونی بدون تأیید تو اجرا نمی‌شود.", color = Gold, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(missionText, { missionText = it }, modifier = Modifier.fillMaxWidth(), enabled = masterEnabled && !missionBusy, label = { Text("چه کاری انجام بدهم؟") }, minLines = 2, maxLines = 4)
                    Button(enabled = masterEnabled && missionText.isNotBlank() && !missionBusy, onClick = { val request = missionText.trim(); missionBusy = true; missionStatus = "در حال ساخت طرح امن..."; scope.launch { runCatching { createMission(request) }.onSuccess { mission -> missionText = ""; missionStatus = "طرح ذخیره شد و همه مراحل در انتظار تأیید هستند."; missions = JSONArray().put(mission).also { current -> for (i in 0 until missions.length()) current.put(missions.getJSONObject(i)) } }.onFailure { missionStatus = it.message ?: "ساخت مأموریت ناموفق بود" }; missionBusy = false } }) { Text(if (missionBusy) "در حال برنامه‌ریزی..." else "ساخت طرح مأموریت") }
                    Text(missionStatus, color = Cyan)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0 until missions.length()).map { missions.getJSONObject(it) }) { mission ->
                            val plan = mission.optJSONObject("plan") ?: JSONObject()
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(plan.optString("summary", mission.optString("request", "مأموریت")), color = Color.White, style = MaterialTheme.typography.titleMedium)
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
                                        Text(step.optString("details"), color = Color.LightGray, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                                        Text("قابلیت: ${step.optString("capability")}\nوضعیت: $stepStatus", color = Gold, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                                        state.optJSONObject("prepared_action")?.let { prepared ->
                                            Text("عملیات دقیق آماده‌شده: " + prepared.toString(), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
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
                                    } else if (mission.optString("status") == "approved_pending_execution") { Text("تأیید شده، اما هنوز اجرا نشده است.", color = Gold) }
                                }
                            }
                        }
                    }
                } else if (showModels) {
                    Text("انتخاب موتور", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("وضعیت زیر از خود Worker خوانده می‌شود؛ موتورِ بدون اتصال قابل انتخاب نیست.", color = Color.LightGray)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (providerStatusBusy) "در حال بررسی..." else if (providerStatus != null) "وضعیت سرور دریافت شد" else providerStatusError, color = if (providerStatus != null) Cyan else Gold, modifier = Modifier.weight(1f))
                        OutlinedButton(enabled = !providerStatusBusy && paired, onClick = {
                            scope.launch {
                                providerStatusBusy = true
                                runCatching { loadProviderStatus() }
                                    .onSuccess { providerStatus = it; providerStatusError = "" }
                                    .onFailure { providerStatusError = it.message ?: "خطا در دریافت وضعیت" }
                                providerStatusBusy = false
                            }
                        }) { Text("تازه‌سازی") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto" to "خودکار", "glm" to "GLM", "zai" to "Z.ai GLM", "gemma" to "Gemma", "gemini" to "Gemini", "groq" to "Groq", "openrouter" to "OpenRouter رایگان").forEach { (key, label) ->
                            FilterChip(selected = selectedModel == key, enabled = providerIsConfigured(key), onClick = { selectedModel = key; storagePrefs.edit().putString("ai_model", key).apply() }, label = { Text(label) })
                        }
                    }
                    val providerItems = providerStatus?.optJSONArray("providers") ?: JSONArray()
                    for (i in 0 until providerItems.length()) {
                        val item = providerItems.optJSONObject(i) ?: continue
                        if (item.optString("id") == "auto" || item.optString("id") == "deepseek") continue
                        Text((item.optString("label") + " | " + if (item.optBoolean("configured")) "پیکربندی شده" else "نیازمند اتصال یا کلید"), color = if (item.optBoolean("configured")) Cyan else Gold, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("کلیدها فقط در Cloudflare ذخیره می‌شوند. Gemini در طرح رایگان ممکن است از داده‌ها برای بهبود محصولات Google استفاده کند؛ اطلاعات محرمانه نفرست. Workers AI در طرح رایگان روزانه ۱۰٬۰۰۰ Neuron دارد؛ در طرح پولی مصرف اضافه ممکن است هزینه داشته باشد. برای جلوگیری از هزینه، طرح پولی را فعال نکن.", color = Gold, style = MaterialTheme.typography.bodySmall)
                    Text("مدل انتخاب‌شده: " + when(selectedModel) { "glm" -> "Cloudflare GLM-4.7-Flash"; "zai" -> "Z.ai GLM-4.7-Flash"; "gemma" -> "Cloudflare Gemma 4 26B"; "gemini" -> "Google Gemini 2.5 Flash"; "groq" -> "Groq GPT-OSS 120B"; "openrouter" -> "OpenRouter Free"; else -> "خودکار" }, color = Cyan)
                }
                if (showStorage) {
                    Text("مدیریت حافظه WOLF", color = Color.White, style = MaterialTheme.typography.titleLarge)
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
                    Text("سقف مصرف WOLF: %.0f GB".format(quotaGb), color = Color.White)
                    Slider(value = quotaGb, onValueChange = { quotaGb = it }, valueRange = 20f..200f, steps = 17)
                    Button(onClick = { storagePrefs.edit().putInt("storage_quota_gb", quotaGb.toInt()).apply(); status = "سقف حافظه ذخیره شد" }) { Text("ذخیره سقف جدید") }
                    Text("فضای آزاد قابل استفاده گوشی: %.1f GB".format(phoneFreeGb), color = Color.LightGray)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(storageBuckets) { bucket ->
                            val (key, pair) = bucket
                            val (label, dir) = pair
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, color = Color.White)
                                        Text("%.2f GB".format(folderBytes(dir) / (1024.0 * 1024 * 1024)), color = Color.LightGray)
                                    }
                                    if (key == "files" || key == "audio") {
                                        TextButton(onClick = { pendingDeleteBucket = label to dir }) { Text("پاک‌سازی") }
                                    }
                                }
                            }
                        }
                    }
                    Text("پاک‌سازی فقط فایل‌ها یا صداها را حذف می‌کند. تاریخچه و حافظه بلندمدت دست‌نخورده می‌مانند. افزایش سقف، ظرفیت فیزیکی گوشی را زیاد نمی‌کند.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                } else if (!paired) {
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
                    Text("ابتدا در گفتگوی خصوصی @OnlyWolfbot دستور /pair را بفرست و کد را اینجا وارد کن.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(status, color=Cyan, modifier = Modifier.weight(1f))
                        TextButton(enabled = chat.isNotEmpty(), onClick = { showClearChatConfirm = true }) {
                            Text("پاک کردن گفتگو", color = Color(0xFFFF7777))
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(chat) { item ->
                            Card(colors=CardDefaults.cardColors(containerColor=if(item.first=="تو") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface), shape=RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(12.dp)) { Text(item.first, color=Gold); Text(item.second, color=MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier.weight(1f),
                            enabled = masterEnabled && !busy,
                            label = { Text("با WOLF صحبت کن...") },
                            maxLines = 4
                        )
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
                        FilledIconButton(
                            enabled = masterEnabled && message.isNotBlank() && !busy,
                            onClick = {
                                val q = message.trim()
                                message = ""
                                chat.add("تو" to q)
                                persistChat()
                                val localResult = runCatching { runLocalCommand(q) }.getOrElse { "اجرای فرمان ناموفق بود: " + (it.message ?: "خطا") }
                                if (localResult != null) { chat.add("WOLF" to localResult); persistChat(); speak(localResult); status = "فرمان گوشی اجرا شد" } else {
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
                    Text("میکروفون فقط با لمس دکمه فعال می‌شود. صدای خام ذخیره نمی‌شود.", color=Color.LightGray, style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
