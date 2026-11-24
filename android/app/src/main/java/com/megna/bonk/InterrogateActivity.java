package com.megna.bonk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Why are you back on Instagram?" Shown over the blocked app. A good reason earns a
 * short pass (you return to the app); a bad one gets you kicked out.
 */
public class InterrogateActivity extends Activity {

    private Characters.Persona persona;
    private String app, mode;
    private boolean finishedVerdict;
    private int passMinutes;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setFinishOnTouchOutside(false);
        app = Prefs.appLabel(getIntent().getStringExtra("pkg"));
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "excuse";
        boolean watch = mode.equals("watch");
        Characters.Info ch = Characters.current(this);
        persona = Characters.persona(this, ch.personality);
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FFFDF8"));
        bg.setCornerRadius(dp(24));
        root.setBackground(bg);

        ImageView face = new ImageView(this);
        face.setImageBitmap(Characters.frame(this, ch.id, "idle"));
        root.addView(face, new LinearLayout.LayoutParams(dp(150), dp(194)));

        TextView q = new TextView(this);
        q.setText(persona.line(watch ? "watch_ask" : "ask", Characters.vars("app", app)));
        q.setTextSize(18);
        q.setTextColor(Color.parseColor("#3A2A40"));
        q.setGravity(Gravity.CENTER);
        q.setPadding(0, dp(8), 0, dp(8));
        root.addView(q);

        EditText answer = new EditText(this);
        answer.setHint(watch ? "What are you watching? (title)" : "Your reason...");
        answer.setSingleLine(true);
        answer.setImeOptions(EditorInfo.IME_ACTION_DONE);
        root.addView(answer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button plead = new Button(this);
        plead.setText(watch ? "I'm watching this" : "Plead my case");
        root.addView(plead);
        Button leave = new Button(this);
        leave.setText(watch ? "Honestly? Just rotting" : "Fine, I'll leave");
        root.addView(leave);

        Runnable submit = () -> {
            String reason = answer.getText().toString().trim();
            if (reason.isEmpty() || finishedVerdict) return;
            plead.setEnabled(false);
            if (watch) {
                boolean ok = Judge.namedSomething(reason);
                q.setText(ok ? persona.line("watch_ok", Characters.vars("title", reason))
                             : persona.line("watch_no", Characters.vars("app", app)));
                q.setTextColor(Color.parseColor(ok ? "#2E7D32" : "#B3261E"));
                handler.postDelayed(() -> done(ok, reason), 1600);
                return;
            }
            plead.setText("Judging...");
            new Thread(() -> {
                Judge.Verdict v = Judge.judge(this, reason, app, persona);
                passMinutes = v.passMinutes;
                handler.post(() -> {
                    q.setText(v.allowed || v.byAi ? v.reply
                            : v.reply + "\n\n(Judged by offline rules: AI not connected. See Settings → Test Gemini key.)");
                    q.setTextColor(Color.parseColor(v.allowed ? "#2E7D32" : "#B3261E"));
                    handler.postDelayed(() -> done(v.allowed, reason), 1800);
                });
            }).start();
        };
        plead.setOnClickListener(v -> submit.run());
        answer.setOnEditorActionListener((v, id, e) -> { submit.run(); return true; });
        leave.setOnClickListener(v -> done(false, "(gave up)"));

        setContentView(root);
        getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(32), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void done(boolean allowed, String reason) {
        if (finishedVerdict) return;
        finishedVerdict = true;
        if (BonkService.instance != null) BonkService.instance.onVerdict(mode, allowed, reason, passMinutes);
        else BonkService.interrogating = false;
        finish();
    }

    @Override
    public void onBackPressed() {
        done(false, "(pressed back)");
    }

    @Override
    protected void onDestroy() {
        if (!finishedVerdict) {
            finishedVerdict = true;
            if (BonkService.instance != null) BonkService.instance.onVerdict(mode, false, "(left the question screen)", 0);
            else BonkService.interrogating = false;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
