package app.tetsulog.ai

import app.tetsulog.data.WorkoutSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * トレーニング評価の抽象化。
 * 実装を差し替えることで「オンデバイス(Gemini Nano) → BYOK(クラウド) → 将来の有料クラウド」を
 * UI に一切手を入れずに切り替えられる。これがこのアプリの生命線なので消さないこと。
 */
interface TrainingEvaluator {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun evaluate(sets: List<WorkoutSet>): Result<String>
}

/** 直近の記録を評価用プロンプトに整形する（実装間で共通） */
fun buildPrompt(sets: List<WorkoutSet>): String {
    val byDate = sets.groupBy { it.date }.toSortedMap()
    val log = buildString {
        byDate.forEach { (date, daySets) ->
            append(date).append(":\n")
            daySets.groupBy { it.exercise }.forEach { (ex, s) ->
                append("  ").append(ex).append(" ")
                append(s.joinToString(", ") { "${it.weightKg}kg×${it.reps}" })
                append("\n")
            }
        }
    }
    return """あなたは経験豊富なストレングスコーチです。以下は直近4週間のトレーニング記録です。

$log

次の観点で日本語で簡潔に評価してください。挨拶や前置きは不要です。
出力はプレーンテキストのみ。Markdown記法（#、*、-の箇条書き記号、**強調**）は一切使わない。
見出しは【】で囲み、箇条書きは「・」で始める。
1. 週あたりのボリューム推移と漸進性過負荷が成立しているか
2. 部位バランスの偏り（押す/引く/脚）
3. 停滞している種目とその打開策の具体案
4. 来週の重量・回数の具体的な推奨（種目ごとに1行）"""
}

/**
 * オンデバイス評価（Gemini Nano / ML Kit GenAI）。
 * 対応端末でのみ有効。依存と呼び出しは端末側SDKのバージョンに強く依存するため、
 * 実装時に必ず最新の公式ドキュメントを確認すること:
 * https://developers.google.com/ml-kit/genai
 * 依存追加後、isAvailable() で AICore の対応判定を行い、evaluate() で
 * buildPrompt() の結果を渡す。非対応端末は自動的に BYOK にフォールバックする設計。
 */
class OnDeviceEvaluator : TrainingEvaluator {
    override val name = "オンデバイスAI (Gemini Nano)"
    override suspend fun isAvailable(): Boolean = false // TODO: AICore対応判定を実装
    override suspend fun evaluate(sets: List<WorkoutSet>): Result<String> =
        Result.failure(UnsupportedOperationException("この端末はオンデバイスAIに未対応です"))
}

/**
 * BYOK: ユーザー自身の Gemini API キーで評価する。開発者側の維持費用ゼロ。
 * キーは端末内の SharedPreferences("secrets") のみに保存し、バックアップ対象から除外済み。
 */
class ByokGeminiEvaluator(private val apiKeyProvider: () -> String?) : TrainingEvaluator {
    override val name = "Gemini API (自分のキー)"

    override suspend fun isAvailable(): Boolean = !apiKeyProvider().isNullOrBlank()

    private var cachedModel: String? = null
    private var cachedRanked: List<String> = emptyList()

    private fun http(method: String, urlStr: String, key: String, body: String? = null): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
            connectTimeout = 15000
            readTimeout = 60000
            if (body != null) doOutput = true
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    /** キーで利用可能なモデルを実行時に取得し、flash系を優先選択する。モデル名の世代交代による404を恒久的に回避。 */
    private fun pickModel(key: String): String {
        cachedModel?.let { return it }
        val (code, text) = http("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100", key)
        if (code !in 200..299) error("モデル一覧の取得に失敗 ($code): ${text.take(300)}")
        val models = JSONObject(text).optJSONArray("models") ?: JSONArray()
        val usable = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val name = m.optString("name")            // "models/gemini-x.y-flash"
            val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            var canGenerate = false
            for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") canGenerate = true
            if (canGenerate) usable.add(name.removePrefix("models/"))
        }
        if (usable.isEmpty()) error("generateContent 対応モデルが見つかりません")
        // 安定版flashをバージョン数値で選択。preview/exp等は無料枠が狭いので除外。
        val bad = listOf("preview", "exp", "omni", "image", "live", "tts", "audio", "thinking", "latest")
        val stableFlash = Regex("^gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?$")
        val ranked = usable.mapNotNull { name ->
            if (bad.any { name.contains(it) }) return@mapNotNull null
            val m = stableFlash.find(name) ?: return@mapNotNull null
            val ver = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lite = m.groupValues[2].isNotEmpty()
            Triple(name, ver, lite)
        }
        val sorted = ranked.sortedWith(compareByDescending<Triple<String, Double, Boolean>> { it.second }
            .thenBy { it.third })                      // 同バージョンなら非lite優先
            .map { it.first }
        cachedRanked = sorted.ifEmpty {
            listOfNotNull(usable.firstOrNull { it.contains("flash") && bad.none { b -> it.contains(b) } } ?: usable.first())
        }
        val chosen = cachedRanked.first()
        cachedModel = chosen
        return chosen
    }

    override suspend fun evaluate(sets: List<WorkoutSet>): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
            ?: return@withContext Result.failure(IllegalStateException("APIキーが未設定です"))
        runCatching {
            pickModel(key)
            val body = JSONObject().put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", buildPrompt(sets)))
                    )
                )
            ).toString()
            var lastError = ""
            val candidates = cachedRanked.take(3)
            for (model in candidates) {
                repeat(2) { attempt ->
                    val (code, text) = http(
                        "POST",
                        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                        key, body
                    )
                    if (code in 200..299) {
                        val raw = JSONObject(text)
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                            .getJSONObject(0).getString("text")
                        return@runCatching raw
                            .replace("**", "")
                            .replace(Regex("(?m)^#{1,6}\\s*"), "")
                            .replace(Regex("(?m)^\\s*[-*]\\s+"), "・")
                    }
                    lastError = "API エラー ($code / $model): ${text.take(200)}"
                    if (code == 503 && attempt == 0) Thread.sleep(2000)   // 過負荷は待って再試行
                    else if (code != 503) return@repeat                    // 503以外は同モデル再試行しない
                }
            }
            cachedModel = null; cachedRanked = emptyList()
            error(lastError.ifEmpty { "評価に失敗しました" })
        }
    }
}
