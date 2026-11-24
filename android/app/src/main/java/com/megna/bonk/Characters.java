package com.megna.bonk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Reads characters and personalities from assets/characters (made by tools/render.js). */
final class Characters {

    static final class Info {
        final String id, name, personality;
        Info(String id, String name, String personality) { this.id = id; this.name = name; this.personality = personality; }
        @Override public String toString() { return name; }
    }

    /** A personality: its lines, with {n} {app} {mins} {pass} placeholders. */
    static final class Persona {
        final JSONObject json;
        private final Random random = new Random();
        Persona(JSONObject json) { this.json = json; }

        String label() { return json.optString("label", "Bonk"); }

        String line(String key, Map<String, String> vars) {
            String text;
            JSONArray arr = json.optJSONArray(key);
            if (arr != null && arr.length() > 0) text = arr.optString(random.nextInt(arr.length()));
            else text = json.optString(key, "");
            if (vars != null) for (Map.Entry<String, String> e : vars.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", e.getValue());
            }
            return text.replaceAll("\\{\\w+\\}", "");
        }
    }

    private Characters() {}

    static String read(Context c, String asset) throws IOException {
        try (InputStream in = c.getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static List<Info> list(Context c) {
        List<Info> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(read(c, "characters/index.json"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Info(o.getString("id"), o.optString("name", o.getString("id")),
                        o.optString("personality", "cheerleader")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** The chosen character, falling back to the first one if it no longer exists. */
    static Info current(Context c) {
        List<Info> all = list(c);
        String id = Prefs.character(c);
        for (Info i : all) if (i.id.equals(id)) return i;
        return all.isEmpty() ? new Info("detective-panda", "Bonk", "detective") : all.get(0);
    }

    static Persona persona(Context c, String personality) {
        try {
            JSONObject all = new JSONObject(read(c, "characters/personalities.json"));
            JSONObject p = all.optJSONObject(personality);
            return new Persona(p != null ? p : all.getJSONObject("cheerleader"));
        } catch (Exception e) {
            return new Persona(new JSONObject());
        }
    }

    static Bitmap frame(Context c, String id, String name) {
        try (InputStream in = c.getAssets().open("characters/" + id + "/" + name + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    static Map<String, Bitmap> frames(Context c, String id) {
        Map<String, Bitmap> m = new HashMap<>();
        try {
            for (String f : c.getAssets().list("characters/" + id)) {
                if (!f.endsWith(".png")) continue;
                Bitmap b = frame(c, id, f.substring(0, f.length() - 4));
                if (b != null) m.put(f.substring(0, f.length() - 4), b);
            }
        } catch (IOException ignored) {}
        return m;
    }

    static Map<String, String> vars(Object... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return m;
    }
}
