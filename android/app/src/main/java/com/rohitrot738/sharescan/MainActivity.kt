package com.rohitrot738.sharescan

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: Button

    private val apiBase = "https://api.github.com"
    private val owner = "rohitrot738"
    private val repo = "Share_scan"
    private val workflow = "live_scan.yml"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        token = findViewById(R.id.token)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        progress = findViewById(R.id.progress)
        scanButton = findViewById(R.id.scanButton)
        progress.visibility = View.GONE
        scanButton.setOnClickListener { startScan() }
    }

    private fun startScan() {
        val pat = token.text.toString().trim()
        if (pat.isEmpty()) {
            status.text = "GitHub token आवश्यक है।"
            return
        }
        scanButton.isEnabled = false
        progress.visibility = View.VISIBLE
        results.text = ""
        Thread {
            try {
                statusOnUi("NSE scan शुरू हो रहा है…")
                dispatch(pat)
                var run: JSONObject? = null
                repeat(30) {
                    Thread.sleep(2000)
                    val arr = getJson("$apiBase/repos/$owner/$repo/actions/workflows/$workflow/runs?event=workflow_dispatch&per_page=10", pat)
                        .optJSONArray("workflow_runs") ?: JSONArray()
                    if (arr.length() > 0) { run = arr.getJSONObject(0); return@repeat }
                }
                if (run == null) throw Exception("Scanner run नहीं मिला")
                val runId = run!!.getLong("id")
                repeat(90) {
                    Thread.sleep(5000)
                    val r = getJson("$apiBase/repos/$owner/$repo/actions/runs/$runId", pat)
                    val state = r.optString("status")
                    val conclusion = r.optString("conclusion")
                    statusOnUi("Scan: $state${if (conclusion.isNotEmpty()) " / $conclusion" else ""}")
                    if (state == "completed") {
                        if (conclusion != "success") throw Exception("Scanner failed: $conclusion")
                        val artifacts = getJson("$apiBase/repos/$owner/$repo/actions/runs/$runId/artifacts", pat)
                        val list = artifacts.optJSONArray("artifacts") ?: JSONArray()
                        if (list.length() == 0) throw Exception("Result artifact नहीं मिला")
                        val id = list.getJSONObject(0).getLong("id")
                        val text = extractResult(getBytes("$apiBase/repos/$owner/$repo/actions/artifacts/$id/zip", pat))
                        runOnUiThread {
                            status.text = "Scan पूरा हुआ"
                            results.text = text
                        }
                        return@Thread
                    }
                }
                throw Exception("Scan timeout")
            } catch (e: Exception) {
                statusOnUi("Error: ${e.message ?: "Unknown error"}")
            } finally {
                runOnUiThread { scanButton.isEnabled = true; progress.visibility = View.GONE }
            }
        }.start()
    }

    private fun dispatch(pat: String) {
        val body = JSONObject().apply {
            put("ref", "main")
            put("inputs", JSONObject().apply { put("top", "100"); put("shortlist", "500") })
        }
        request("POST", "$apiBase/repos/$owner/$repo/actions/workflows/$workflow/dispatches", pat, body.toString())
    }

    private fun getJson(url: String, pat: String) = JSONObject(String(getBytes(url, pat), StandardCharsets.UTF_8))

    private fun request(method: String, url: String, pat: String, body: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $pat")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 20000
        c.readTimeout = 30000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = c.responseCode
        if (code !in 200..299) throw Exception("GitHub HTTP $code")
        return c.inputStream.use { it.readBytes() }
    }

    private fun getBytes(url: String, pat: String): ByteArray = request("GET", url, pat)

    private fun extractResult(zipBytes: ByteArray): String {
        ZipInputStream(BufferedInputStream(zipBytes.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name.endsWith("top100_by_volume.json") || entry.name.endsWith("top10.json"))) {
                    val out = ByteArrayOutputStream()
                    zis.copyTo(out)
                    return prettyJson(out.toString(StandardCharsets.UTF_8.name()))
                }
                entry = zis.nextEntry
            }
        }
        return "Artifact मिला, JSON result नहीं मिला।"
    }

    private fun prettyJson(raw: String): String = try {
        val t = raw.trim()
        if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
    } catch (_: Exception) { raw }

    private fun statusOnUi(text: String) = runOnUiThread { status.text = text }
}
