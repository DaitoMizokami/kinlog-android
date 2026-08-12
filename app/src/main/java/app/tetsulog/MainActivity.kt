package app.tetsulog

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import app.tetsulog.ai.ByokGeminiEvaluator
import app.tetsulog.ai.OnDeviceEvaluator
import app.tetsulog.data.AppDb
import app.tetsulog.data.WorkoutSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- palette ----
val Bg = Color(0xFF1A1B1E)
val Surface1 = Color(0xFF232529)
val Raise = Color(0xFF2B2E33)
val Line = Color(0xFF33363C)
val Ink = Color(0xFFF2F0EB)
val Muted = Color(0xFF9DA2AA)
val Accent = Color(0xFFE8622C)

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

class MainViewModel(private val app: Context) : ViewModel() {
    private val dao = AppDb.get(app).setDao()
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val secrets = app.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    val exercises = MutableStateFlow(
        prefs.getStringSet("exercises", null)?.toList()
            ?: listOf("ベンチプレス", "スクワット", "デッドリフト", "ショルダープレス", "懸垂")
    )
    val current = MutableStateFlow(prefs.getString("current", null) ?: exercises.value.first())
    val weight = MutableStateFlow(prefs.getFloat("last:${current.value}:w", 60f))
    val reps = MutableStateFlow(prefs.getInt("last:${current.value}:r", 8))

    val todaySets = dao.setsOn(today())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allSets = dao.allSets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiResult = MutableStateFlow<String?>(null)
    val aiBusy = MutableStateFlow(false)

    private val onDevice = OnDeviceEvaluator()
    private val byok = ByokGeminiEvaluator { secrets.getString("gemini_key", null) }

    fun selectExercise(name: String) {
        current.value = name
        weight.value = prefs.getFloat("last:$name:w", 60f)
        reps.value = prefs.getInt("last:$name:r", 8)
        prefs.edit().putString("current", name).apply()
    }

    fun addExercise(name: String) {
        if (name.isBlank() || exercises.value.contains(name)) return
        exercises.value = exercises.value + name
        prefs.edit().putStringSet("exercises", exercises.value.toSet()).apply()
        selectExercise(name)
    }

    fun bumpWeight(d: Float) { weight.value = (weight.value + d).coerceAtLeast(0f) }
    fun bumpReps(d: Int) { reps.value = (reps.value + d).coerceAtLeast(1) }
    fun setWeight(v: Float) { weight.value = v.coerceAtLeast(0f) }
    fun setReps(v: Int) { reps.value = v.coerceAtLeast(1) }

    fun delete(set: WorkoutSet) = viewModelScope.launch { dao.delete(set) }

    fun record() = viewModelScope.launch {
        dao.insert(WorkoutSet(date = today(), exercise = current.value,
            weightKg = weight.value, reps = reps.value, at = System.currentTimeMillis()))
        prefs.edit()
            .putFloat("last:${current.value}:w", weight.value)
            .putInt("last:${current.value}:r", reps.value)
            .apply()
        Toast.makeText(app, "記録しました", Toast.LENGTH_SHORT).show()
    }

    fun runAi() = viewModelScope.launch {
        aiBusy.value = true
        aiResult.value = null
        val since = System.currentTimeMillis() - 28L * 24 * 60 * 60 * 1000
        val sets = dao.setsSince(since)
        if (sets.isEmpty()) {
            aiResult.value = "評価対象の記録がありません。まず4週間分とは言わずとも、数回分の記録を付けてください。"
            aiBusy.value = false; return@launch
        }
        val evaluator = if (onDevice.isAvailable()) onDevice else byok
        val res = evaluator.evaluate(sets)
        aiResult.value = res.getOrElse { "評価に失敗しました: ${it.message}" }
        aiBusy.value = false
    }

    fun geminiKey(): String = secrets.getString("gemini_key", "") ?: ""
    fun saveGeminiKey(k: String) {
        secrets.edit().putString("gemini_key", k.trim()).apply()
        Toast.makeText(app, "APIキーを保存しました", Toast.LENGTH_SHORT).show()
    }

    suspend fun exportJson(): String {
        val arr = JSONArray()
        AppDb.get(app).setDao().snapshot().forEach { s ->
            arr.put(JSONObject()
                .put("date", s.date).put("ex", s.exercise)
                .put("w", s.weightKg).put("r", s.reps).put("at", s.at))
        }
        return JSONObject().put("version", 1).put("sets", arr).toString(1)
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(ctx.applicationContext) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(this))
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, background = Bg, surface = Surface1,
                onBackground = Ink, onSurface = Ink)) {
                App(vm)
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                listOf("記録", "履歴", "AI評価", "設定").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = {}, label = { Text(label, fontSize = 13.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Accent, unselectedTextColor = Muted,
                            indicatorColor = Raise))
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> LogScreen(vm)
                1 -> HistoryScreen(vm)
                2 -> AiScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun LogScreen(vm: MainViewModel) {
    val exercises by vm.exercises.collectAsState()
    val current by vm.current.collectAsState()
    val weight by vm.weight.collectAsState()
    val reps by vm.reps.collectAsState()
    val todaySets by vm.todaySets.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("筋ログ", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            exercises.forEach { ex ->
                Chip(ex, ex == current) { vm.selectExercise(ex) }
                Spacer(Modifier.width(8.dp))
            }
            Chip("＋ 種目", false) { showAdd = true }
        }
        Spacer(Modifier.height(14.dp))
        var editTarget by remember { mutableStateOf<String?>(null) }
        StepperCard("重量", "${if (weight % 1f == 0f) weight.toInt() else weight} kg",
            onMinus = { vm.bumpWeight(-2.5f) }, onPlus = { vm.bumpWeight(2.5f) },
            onValueTap = { editTarget = "w" })
        Spacer(Modifier.height(10.dp))
        StepperCard("回数", "$reps",
            onMinus = { vm.bumpReps(-1) }, onPlus = { vm.bumpReps(1) },
            onValueTap = { editTarget = "r" })
        if (editTarget != null) {
            var input by remember(editTarget) { mutableStateOf("") }
            val isWeight = editTarget == "w"
            AlertDialog(
                onDismissRequest = { editTarget = null },
                containerColor = Surface1,
                title = { Text(if (isWeight) "重量を入力 (kg)" else "回数を入力", color = Ink) },
                text = {
                    OutlinedTextField(value = input, onValueChange = { input = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        placeholder = { Text(if (isWeight) "例: 62.5" else "例: 8", color = Muted) })
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (isWeight) input.toFloatOrNull()?.let { vm.setWeight(it) }
                        else input.toIntOrNull()?.let { vm.setReps(it) }
                        editTarget = null
                    }) { Text("OK", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { editTarget = null }) { Text("キャンセル", color = Muted) }
                })
        }
        Text("推定1RM: ${"%.1f".format(weight * (1 + reps / 30f))} kg",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(14.dp))
        Button(onClick = { vm.record() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) {
            Text("セットを記録", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("今日のセット", color = Muted, fontSize = 12.sp)
        if (todaySets.isEmpty()) {
            Text("まだ記録がありません", color = Muted, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally))
        }
        todaySets.forEachIndexed { i, s ->
            SetRow(i + 1, s, onDelete = { vm.delete(s) })
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = Surface1,
            title = { Text("種目を追加", color = Ink) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    placeholder = { Text("例：インクラインベンチ", color = Muted) })
            },
            confirmButton = {
                TextButton(onClick = { vm.addExercise(name); showAdd = false }) {
                    Text("追加", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("キャンセル", color = Muted) }
            })
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier
        .background(if (selected) Accent else Surface1, CircleShape)
        .clickable { onClick() }
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, color = if (selected) Bg else Muted, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun StepperCard(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit,
                onValueTap: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", onMinus)
            Text(value, color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                modifier = if (onValueTap != null) Modifier.clickable { onValueTap() } else Modifier)
            StepButton("＋", onPlus)
        }
        if (onValueTap != null) Text("数値をタップで直接入力", color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(60.dp).background(Raise, RoundedCornerShape(12.dp))
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = Ink, fontSize = 24.sp)
    }
}

@Composable
fun SetRow(n: Int, s: WorkoutSet, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("$n", color = Muted, fontSize = 14.sp, modifier = Modifier.width(24.dp))
        Text("${if (s.weightKg % 1f == 0f) s.weightKg.toInt() else s.weightKg}kg × ${s.reps}",
            color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Text(s.exercise, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            Text("×", color = Muted, fontSize = 18.sp,
                modifier = Modifier.clickable { onDelete() }.padding(8.dp))
        }
    }
    HorizontalDivider(color = Line, thickness = 0.5.dp)
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val all by vm.allSets.collectAsState()
    val byDate = all.groupBy { it.date }
    LazyColumn(Modifier.padding(16.dp)) {
        if (byDate.isEmpty()) item {
            Text("履歴はまだありません", color = Muted,
                modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth())
        }
        items(byDate.keys.sortedDescending()) { date ->
            val sets = byDate[date] ?: emptyList()
            val vol = sets.sumOf { (it.weightKg * it.reps).toDouble() }.toInt()
            Column(Modifier.padding(bottom = 20.dp)) {
                Text(date, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${sets.size}セット / 総挙上 ${vol}kg", color = Muted, fontSize = 12.sp)
                sets.forEachIndexed { i, s -> SetRow(i + 1, s) }
            }
        }
    }
}

@Composable
fun AiScreen(vm: MainViewModel) {
    val result by vm.aiResult.collectAsState()
    val busy by vm.aiBusy.collectAsState()
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("AI評価", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("直近4週間の記録から、ボリューム推移・部位バランス・停滞打開策・来週の推奨を出します。",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        Text("データの扱い：AI評価ボタンを押したときに限り、直近の記録テキストがあなた自身のAPIキー経由でGoogleのGemini APIに送信されます。記録はそれ以外の場面では端末外に出ません。",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        Button(onClick = { vm.runAi() }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) {
            Text(if (busy) "評価中…" else "直近4週間を評価する", fontWeight = FontWeight.Bold)
        }
        result?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Ink, fontSize = 15.sp, lineHeight = 24.sp,
                modifier = Modifier.background(Surface1, RoundedCornerShape(14.dp)).padding(16.dp))
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var key by remember { mutableStateOf(vm.geminiKey()) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.exportJson()
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("設定", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text("Gemini APIキー（BYOK）", color = Muted, fontSize = 12.sp)
        OutlinedTextField(value = key, onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AIza…", color = Muted) })
        TextButton(onClick = { vm.saveGeminiKey(key) }) { Text("保存", color = Accent) }
        Text("キーは端末内にのみ保存され、バックアップにも含まれません。",
            color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text("AI評価の使い方", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            """このアプリの記録機能はAIキーなしですべて使えます。AI評価を使う場合のみ、無料のGemini APIキーが必要です。以下の手順で取得し、上の欄に入力してください。

1. ブラウザで aistudio.google.com を開く
2. Googleアカウントでログイン
3. 「Get API key」→「Create API key」をタップ
4. 表示されたキーをコピーして上の欄に貼り付け、保存""",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { exporter.launch("tetsulog-${today()}.json") },
            modifier = Modifier.fillMaxWidth()) {
            Text("JSONエクスポート（バックアップ）", color = Ink)
        }
        Text("記録はGoogleの自動バックアップで機種変更時も引き継がれますが、手動エクスポートも定期的に推奨します。",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
