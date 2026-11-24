package com.megna.bonk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Settings screen, built in code (no layout XML needed). */
public class MainActivity extends Activity {

    private TextView status;
    private LinearLayout root;
    private int pad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pad = dp(20);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Bonk");
        title.setTextSize(32);
        root.addView(title);
        status = label("", 16);
        root.addView(status);

        // ---- character
        header("Your Bonk");
        ImageView preview = new ImageView(this);
        root.addView(preview, new LinearLayout.LayoutParams(dp(155), dp(200)));
        TextView personaLabel = label("", 14);
        root.addView(personaLabel);
        Spinner picker = new Spinner(this);
        List<Characters.Info> chars = Characters.list(this);
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, chars));
        String cur = Characters.current(this).id;
        for (int i = 0; i < chars.size(); i++) if (chars.get(i).id.equals(cur)) picker.setSelection(i);
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Characters.Info ch = chars.get(pos);
                Prefs.setCharacter(MainActivity.this, ch.id);
                preview.setImageBitmap(Characters.frame(MainActivity.this, ch.id, "idle"));
                personaLabel.setText("Personality: " + Characters.persona(MainActivity.this, ch.personality).label());
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        root.addView(picker);

        // ---- limits & apps
        header("Rules");
        root.addView(label("Minutes allowed before Bonk shows up", 14));
        EditText limit = new EditText(this);
        limit.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit.setText(String.valueOf(Prefs.limitMinutes(this)));
        root.addView(limit);
        CheckBox anywhere = new CheckBox(this);
        anywhere.setText("Catch rotting in any app (fast endless swiping)");
        anywhere.setChecked(Prefs.detectAnywhere(this));
        root.addView(anywhere);
        Set<String> chosen = Prefs.enabled(this);
        String[][] groups = {
                {"ROT", "Brain-rot apps: time limit on feeds, Reels, Shorts"},
                {"WATCH", "Streaming apps: ask \"watching something, or rotting?\""},
                {"SAFE", "Never touched: music, podcasts, learning"}};
        for (String[] g : groups) {
            root.addView(label(g[1], 14));
            for (Prefs.App app : Prefs.APPS.values()) {
                if (!app.kind.name().equals(g[0])) continue;
                CheckBox cb = new CheckBox(this);
                cb.setText(app.label);
                if (app.kind == Prefs.Kind.SAFE) { cb.setChecked(true); cb.setEnabled(false); }
                else {
                    cb.setChecked(chosen.contains(app.pkg));
                    cb.setOnCheckedChangeListener((v, on) -> { if (on) chosen.add(app.pkg); else chosen.remove(app.pkg); });
                }
                root.addView(cb);
            }
        }

        CheckBox remind = new CheckBox(this);
        remind.setText("Remind me after 30 min on my phone (doesn't close anything)");
        remind.setChecked(Prefs.remind(this));
        root.addView(remind);

        // ---- interrogation
        header("Interrogation");
        CheckBox ask = new CheckBox(this);
        ask.setText("Question me if I come back right after a kick");
        ask.setChecked(Prefs.interrogate(this));
        root.addView(ask);
        EditText key = field("Gemini API key (optional, free at aistudio.google.com)", Prefs.geminiKey(this));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = field("Gemini model", Prefs.geminiModel(this));

        // ---- sync
        header("Sync with laptop (optional)");
        EditText syncUrl = field("Firebase Realtime Database URL", Prefs.syncUrl(this));
        EditText syncCode = field("Secret sync code (same on laptop)", Prefs.syncCode(this));

        Button save = button("Save", v -> {
            try { Prefs.setLimitMinutes(this, Integer.parseInt(limit.getText().toString())); }
            catch (NumberFormatException ignored) {}
            Prefs.setEnabled(this, chosen);
            Prefs.setDetectAnywhere(this, anywhere.isChecked());
            Prefs.setRemind(this, remind.isChecked());
            Prefs.setInterrogate(this, ask.isChecked());
            Prefs.setGemini(this, key.getText().toString(), model.getText().toString());
            Prefs.setSync(this, syncUrl.getText().toString(), syncCode.getText().toString());
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        save.setBackgroundColor(Color.parseColor("#3A2A40"));
        save.setTextColor(Color.WHITE);

        header("Actions");
        button("Turn Bonk on (Accessibility)", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("Test: summon Bonk now", v -> {
            if (BonkService.instance == null) Toast.makeText(this, "Turn Bonk on first", Toast.LENGTH_SHORT).show();
            else BonkService.instance.kick("test", "");
        });
        button("Test: 30-minute reminder", v -> {
            if (BonkService.instance == null) Toast.makeText(this, "Turn Bonk on first", Toast.LENGTH_SHORT).show();
            else BonkService.instance.remind();
        });
        button("Test: interrogate me", v -> {
            BonkService.interrogating = true;
            startActivity(new Intent(this, InterrogateActivity.class).putExtra("pkg", "com.instagram.android").putExtra("mode", "excuse"));
        });
        button("Test: streaming check", v -> {
            BonkService.interrogating = true;
            startActivity(new Intent(this, InterrogateActivity.class).putExtra("pkg", "in.startv.hotstar").putExtra("mode", "watch"));
        });
        button("Open case files", v -> startActivity(new Intent(this, CaseFilesActivity.class)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = BonkService.instance != null;
        status.setText(on ? "Status: ON" : "Status: OFF. Tap 'Turn Bonk on' below.");
    }

    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        return t;
    }

    private void header(String text) {
        TextView t = label(text, 20);
        t.setPadding(0, pad, 0, dp(6));
        t.setTextColor(Color.parseColor("#3A2A40"));
        root.addView(t);
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setText(value);
        root.addView(e);
        return e;
    }

    private Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setOnClickListener(l);
        root.addView(btn);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
