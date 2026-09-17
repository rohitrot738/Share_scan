package com.rohit.share_scan

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var start: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var results: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { text = "NSE scanner तैयार है"; textSize = 18f }
        start = Button(this).apply { text = "पूरा NSE स्कैन शुरू करें" }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        results = TextView(this).apply { textSize = 14f }
        layout.addView(status)
        layout.addView(start)
        layout.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        layout.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        start.setOnClickListener { runScan() }
    }

    private fun runScan() {
        start.isEnabled = false
        progress.progress = 0
        results.text = ""
        worker.execute {
            runCatching {
                NseScanner.scan { done, total ->
                    runOnUiThread {
                        progress.progress = done * 100 / total
                        status.text = "स्कैन हो रहा है: $done / $total"
                    }
                }
            }.onSuccess { rows ->
                runOnUiThread {
                    status.text = "स्कैन पूरा: ${rows.size} top candidates"
                    results.text = rows.joinToString("\n") { "%-14s ₹%.2f  score %.1f  RVOL %.2f".format(it.symbol, it.price, it.score, it.rvol) }
                    start.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread { status.text = "स्कैन असफल: ${error.message}"; start.isEnabled = true }
            }
        }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
