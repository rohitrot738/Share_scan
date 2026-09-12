package com.rohitrot738.sharescan;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;

public class MainActivity extends Activity {
    private TextView status;
    private ProgressBar progress;
    private WebView results;
    private Button cacheButton;
    private Button scanButton;
    private final String baseDir;

    public MainActivity() { baseDir = null; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String dir = getFilesDir().getAbsolutePath();
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 20);

        TextView title = new TextView(this);
        title.setText("Share Scan");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 4);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("NSE • 360CR • Volume • Ghost Ready • Ghost Score");
        subtitle.setTextSize(14);
        root.addView(subtitle);

        TextView gates = new TextView(this);
        gates.setText("Gate order: Market Cap > ₹1000 Cr → 360CR → Volume → Ghost Ready → Ghost Score → Entry/Veto");
        gates.setTextSize(13);
        gates.setPadding(0, 12, 0, 12);
        root.addView(gates);

        cacheButton = new Button(this);
        cacheButton.setText("1  •  Build / Refresh NSE Cache");
        root.addView(cacheButton);

        scanButton = new Button(this);
        scanButton.setText("2  •  Run Top-100 Scan");
        root.addView(scanButton);

        Button openButton = new Button(this);
        openButton.setText("Open Latest Results");
        root.addView(openButton);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = new TextView(this);
        status.setText("Ready. Build the NSE cache once, then run the scan.");
        status.setTextSize(15);
        status.setPadding(0, 12, 0, 12);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        results = new WebView(this);
        results.setWebViewClient(new WebViewClient());
        results.getSettings().setJavaScriptEnabled(false);
        results.setBackgroundColor(Color.WHITE);
        scroll.addView(results);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(scroll, webParams);
        setContentView(root);

        cacheButton.setOnClickListener(v -> runPython("build_cache", dir));
        scanButton.setOnClickListener(v -> runPython("run_scan", dir));
        openButton.setOnClickListener(v -> showDashboard(dir));
    }

    private void runPython(String action, String dir) {
        cacheButton.setEnabled(false);
        scanButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(action.equals("build_cache")
                ? "NSE cache बन रहा है… पहली बार समय लग सकता है।"
                : "Top-100 NSE scan चल रहा है…");
        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject module = py.getModule("mobile_entry");
                PyObject result;
                if ("build_cache".equals(action)) {
                    result = module.callAttr("build_cache", dir);
                } else {
                    result = module.callAttr("run_scan", dir, 100, 500);
                }
                final String text = result.toString();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    cacheButton.setEnabled(true);
                    scanButton.setEnabled(true);
                    status.setText(text.startsWith("CACHE_MISSING") ? text : "पूरा हुआ: " + text);
                    if (text.endsWith("dashboard.html")) showDashboard(dir);
                    else if ("NSE cache ready".equals(text)) Toast.makeText(this, "NSE cache ready", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                final String text = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    cacheButton.setEnabled(true);
                    scanButton.setEnabled(true);
                    status.setText("ERROR — " + text);
                });
            }
        }).start();
    }

    private void showDashboard(String dir) {
        File dashboard = new File(dir, "scan_output/dashboard.html");
        if (!dashboard.exists()) {
            status.setText("अभी कोई scan result नहीं है। पहले Top-100 Scan चलाएँ।");
            return;
        }
        results.loadUrl("file://" + dashboard.getAbsolutePath());
        status.setText("Latest scan dashboard खुल गया है।");
    }
}
