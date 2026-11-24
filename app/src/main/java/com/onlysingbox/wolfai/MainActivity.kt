package com.onlysingbox.wolfai
import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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
        securePrefs = EncryptedSharedPreferences.create("wolf_secure", master, this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        setContent { WolfApp(
            token = securePrefs.getString("device_token", "") ?: "",
            saveToken = { securePrefs.edit().putString("device_token", it).apply() },
            send = { text -> apiChat(securePrefs.getString("device_token", "") ?: "", text) },
            speak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wolf-reply") }
        ) }
    }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR") }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }

    private suspend fun apiChat(token: String, text: String): String = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE_URL/api/companion/chat").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("text", text).put("session_id", "android-main").toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok")) throw IllegalStateException(obj.optString("error", "خطای ارتباط"))
            obj.getString("reply")
        } finally { conn.disconnect() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WolfApp(token: String, saveToken: (String)->Unit, send: suspend (String)->String, speak: (String)->Unit) {
    var paired by remember { mutableStateOf(token.isNotBlank()) }
    var pairCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("آماده گفتگو") }
    val chat = remember { mutableStateListOf<Pair<String,String>>() }
    val scope = rememberCoroutineScope()
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) message = spoken
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Gold, background = Navy, surface = Panel)) {
        Scaffold(containerColor = Navy) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🐺 WOLF AI", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text("AGENT  •  مکالمه هوشمند فارسی و انگلیسی", color = Gold)
                if (!paired) {
                    Text("اتصال امن به WOLF", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(pairCode, { pairCode = it.filter(Char::isDigit).take(6) }, label = { Text("کد ۶ رقمی تلگرام") }, singleLine = true)
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
                    Text("ابتدا در گفتگوی خصوصی @OnlyWolfbot دستور /pair را بفرست و کد را اینجا وارد کن.", color=Color.LightGray)
                } else {
                    Text(status, color=Cyan)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(chat) { item ->
                            Card(colors=CardDefaults.cardColors(containerColor=if(item.first=="تو") Panel else Color(0xFF123B55)), shape=RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(12.dp)) { Text(item.first, color=Gold); Text(item.second, color=Color.White) }
                            }
                        }
                    }
                    OutlinedTextField(message, {message=it}, modifier=Modifier.fillMaxWidth(), label={Text("با WOLF صحبت کن...")})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick={
                            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "با WOLF صحبت کن")
                            }
                            speechLauncher.launch(intent)
                        }) { Icon(Icons.Default.Mic, contentDescription="فرمان صوتی") }
                        Button(enabled=message.isNotBlank()&&!busy, onClick={
                            val q=message.trim(); message=""; chat.add("تو" to q); busy=true; status="WOLF در حال فکر کردن..."
                            scope.launch {
                                try { val a=send(q); chat.add("WOLF" to a); speak(a); status="آماده گفتگو" }
                                catch(e:Exception){ status=e.message ?: "ارتباط برقرار نشد" }
                                busy=false
                            }
                        }, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Send, contentDescription=null); Spacer(Modifier.width(8.dp)); Text("ارسال") }
                    }
                    Text("میکروفون فقط با لمس دکمه فعال می‌شود. صدای خام ذخیره نمی‌شود.", color=Color.LightGray, style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
