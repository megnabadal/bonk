package com.megna.bonk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** The weekly investigation report, merged across phone + laptop when sync is on. */
public class CaseFilesActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setBackgroundColor(Color.parseColor("#F3EFE6"));

        TextView report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextColor(Color.parseColor("#2A2320"));
        report.setTextSize(13);
        report.setText("Pulling the files...");
        root.addView(report);

        Button share = new Button(this);
        share.setText("Share report");
        share.setOnClickListener(v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, report.getText().toString()), "Share report")));
        root.addView(share);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        String name = Characters.current(this).name;
        new Thread(() -> {
            List<JSONObject> cases = Cases.all(this);
            String text = Cases.weeklyReport(cases, name);
            runOnUiThread(() -> report.setText(text));
        }).start();
    }
}
