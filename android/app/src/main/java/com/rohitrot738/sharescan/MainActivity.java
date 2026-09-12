package com.rohitrot738.sharescan;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public class MainActivity extends Activity {
    private TextView status;
    private ProgressBar progress;
    private String baseDir;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        baseDir = getFilesDir().getAbsolutePath();
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 32);

        TextView title = new TextView(this);
        title.setText("Share Scan — NSE Scanner");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setText("Ready. First build the NSE cache.");
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        Button cache = new Button(this);
        cache.setText("Build / Refresh NSE Cache");
        root.addView(cache);

        Button scan = new Button(this);
        scan.setText("Run Top-100 Scan");
        root.addView(scan);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        cache.setOnClickListener(v -> runPython("build_cache"));
        scan.setOnClickListener(v -> runPython("run_scan"));
        setContentView(root);
    }

    private void runPython(String action) {
        progress.setVisibility(View.VISIBLE);
        status.setText("Running " + action + "…");
        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject module = py.getModule("mobile_entry");
                PyObject result;
                if ("build_cache".equals(action)) {
                    result = module.callAttr("build_cache", baseDir);
                } else {
                    result = module.callAttr("run_scan", baseDir, 100, 500);
                }
                final String text = result.toString();
                runOnUiThread(() -> { progress.setVisibility(View.GONE); status.setText(text); });
            } catch (Exception e) {
                final String text = "ERROR: " + e.getMessage();
                runOnUiThread(() -> { progress.setVisibility(View.GONE); status.setText(text); });
            }
        }).start();
    }
}
