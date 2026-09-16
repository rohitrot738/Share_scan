package com.rohitrot738.sharescan

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var scanButton: Button

    private val api = "https://api.github.com"
    private val owner = "rohitrot738"
    private val repo = "Share_scan"
    private val workflow = "live_scan.yml"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        token = findViewById(R.id.token)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        scanButton = findViewById(R.id.scanButton)
        scanButton.setOnClickListener { startScan() }
    }

    private fun startScan() {
        val pat = token.text.toString().trim()
        if (pat.isEmpty()) { status.text = "GitHub token डालें (Actions read/write)."; return }
        scanButton.isEnabled = false
        results.text = ""
        Thread {
            try {
                ui("GitHub scanner शुरू हो रहा है…")
                dispatch(pat)
                var run: JSONObject? = null
                repeat(30) {
                    Thread.sleep(2000)
                    val a = getJson("$api/repos/$owner/$repo/actions/workflows/$workflow/runs?event=workflow_dispatch&per_page=5", pat).optJSONArray("workflow_runs") ?: JSONArray()
                    if (a.length() > 0) { run = a.getJSONObject(0); return@repeat }
                }
                if (run == null) throw Exception("Workflow run नहीं मिला")
                val id = run!!.getLong("id")
                var done = false
                var conclusion = ""
                repeat(90) {
                    Thread.sleep(5000)
                    val r = getJson("$api/repos/$owner/$repo/actions/runs/$id", pat)
                    val state = r.optString("status")
                    conclusion = r.optString("conclusion")
                    ui("Scan #$id: $state${if (conclusion.isNotEmpty()) " / $conclusion" else ""}")
                    if (state == "completed") { done = true; return@repeat }
                }
                if (!done) throw Exception("Scan timeout")
                if (conclusion != "success") throw Exception("Scanner failed: $conclusion")
                val arts = getJson("$api/repos/$owner/$repo/actions/runs/$id/artifacts", pat).optJSONArray("artifacts") ?: JSONArray()
                if (arts.length() == 0) throw Exception("Result artifact नहीं मिला")
                val aid = arts.getJSONObject(0).getLong("id")
                ui("Results डाउनलोड हो रहे हैं…")
                val text = extract(getBytes("$api/repos/$owner/$repo/actions/artifacts/$aid/zip", pat))
                ui("Scan पूरा हुआ")
                runOnUiThread { results.text = text }
            } catch (e: Exception) { ui("Error: ${e.message ?: e.javaClass.simpleName}") }
            finally { runOnUiThread { scanButton.isEnabled = true } }
        }.start()
    }

    private fun dispatch(pat: String) {
        val body = JSONObject().apply {
            put("ref", "main")
            put("inputs", JSONObject().apply { put("top", "100"); put("shortlist", "500") })
        }
        request("POST", "$api/repos/$owner/$repo/actions/workflows/$workflow/dispatches", pat, body.toString())
    }

    private fun getJson(url: String, pat: String) = JSONObject(String(getBytes(url, pat), StandardCharsets.UTF_8))

    private fun getBytes(url: String, pat: String): ByteArray = request("GET", url, pat)

    private fun request(method: String, url: String, pat: String, body: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $pat")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) } }
        val code = c.responseCode
        if (code !in 200..299) throw Exception("GitHub HTTP $code")
        return c.inputStream.use { it.readBytes() }
    }

    private fun extract(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && (e.name.endsWith("top10.json") || e.name.endsWith("top100_by_volume.json"))) {
                    val raw = z.readBytes().toString(StandardCharsets.UTF_8)
                    return try { if (raw.trim().startsWith("[")) JSONArray(raw).toString(2) else JSONObject(raw).toString(2) } catch (_: Exception) { raw }
                }
                e = z.nextEntry
            }
        }
        return "Artifact मिला, JSON result नहीं मिला।"
    }

    private fun ui(s: String) = runOnUiThread { status.text = s }
}
