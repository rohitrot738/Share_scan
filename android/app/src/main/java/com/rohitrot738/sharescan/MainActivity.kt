package com.rohitrot738.sharescan

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.status)
        findViewById<Button>(R.id.scanButton).setOnClickListener {
            status.text = "Scan requested. Backend integration is required to execute the Python scanner."
        }
    }
}
