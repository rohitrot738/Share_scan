package com.rohitrot738.sharescan

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        progress = findViewById(R.id.progress)
        scanButton = findViewById(R.id.scanButton)
        progress.visibility = View.GONE

        scanButton.setOnClickListener { startScan() }
    }

    private fun startScan() {
        scanButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        results.text = ""
        status.text = "Share_scan engine शुरू हो रहा है…"

        Thread {
            try {
                val py = Python.getInstance()
                runOnUiThread { status.text = "NSE universe और scanner तैयार हो रहा है…" }
                val bridge = py.getModule("android_bridge")
                val raw = bridge.callAttr("run_scan", 100, 500).toString()
                runOnUiThread {
                    status.text = "NSE scan पूरा हुआ ✓"
                    progress.isIndeterminate = false
                    progress.progress = 100
                    results.text = prettyJson(raw)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Scan error"
                    results.text = e.stackTraceToString()
                }
            } finally {
                runOnUiThread {
                    scanButton.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun prettyJson(raw: String): String = try {
        val t = raw.trim()
        if (t.startsWith("[")) org.json.JSONArray(t).toString(2)
        else org.json.JSONObject(t).toString(2)
    } catch (_: Exception) {
        raw
    }
}
