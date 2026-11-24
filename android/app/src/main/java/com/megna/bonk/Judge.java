package com.megna.bonk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Decides whether your excuse earns a pass. Gemini if a key is set, offline rules otherwise. */
final class Judge {

    static final class Verdict {
        final boolean allowed;
        final String reply;
        final int passMinutes;
        Verdict(boolean allowed, String reply, int passMinutes) {
            this.allowed = allowed; this.reply = reply; this.passMinutes = passMinutes;
        }
    }

    private static final Set<String> MEAL = new HashSet<>(Arrays.asList(
            "eat", "eating", "lunch", "dinner", "breakfast", "meal", "food", "snack", "snacking"));

    static boolean isMeal(String reason) {
        for (String w : reason.toLowerCase(Locale.US).split("[^a-z']+")) if (MEAL.contains(w)) return true;
        return false;
    }

    private static final Set<String> GOOD = new HashSet<>(Arrays.asList(
            "message", "messages", "dm", "dms", "reply", "post", "posting", "upload", "class",
            "assignment", "work", "project", "call", "order", "ticket", "lecture", "tutorial",
            "notes", "group", "event", "club", "meeting", "client", "check", "verify", "otp"));
    private static final Set<String> BAD = new HashSet<>(Arrays.asList(
            "bored", "boring", "scroll", "scrolling", "reels", "reel", "shorts", "timepass",
            "nothing", "idk", "dunno", "chill", "relax", "fun", "vibe", "just"));

    private static final Set<String> VAGUE = new HashSet<>(Arrays.asList(
            "idk", "anything", "something", "nothing", "whatever", "random", "dunno", "bored",
            "scroll", "scrolling", "browse", "browsing", "rot", "rotting", "dont", "know", "just", "looking"));

    private Judge() {}

    /** Streaming check: did they name an actual thing to watch? */
    static boolean namedSomething(String answer) {
        String[] words = answer.toLowerCase(Locale.US).split("[^a-z0-9']+");
        int real = 0;
        for (String w : words) if (!w.isEmpty() && !VAGUE.contains(w)) real++;
        return answer.trim().length() >= 2 && real >= 1;
    }

    static boolean offline(String reason) {
        String[] words = reason.toLowerCase(Locale.US).split("[^a-z']+");
        int count = 0;
        boolean good = false;
        for (String w : words) {
            if (w.isEmpty()) continue;
            count++;
            if (BAD.contains(w)) return false;
            if (GOOD.contains(w)) good = true;
        }
        return count >= 3 && good;
    }

    /** Blocking (network): call off the main thread. */
    static Verdict judge(Context c, String reason, String app, Characters.Persona persona) {
        if (isMeal(reason)) {                      // eating + a show is fair: a longer meal pass
            int m = Prefs.mealPassMinutes(c);
            return new Verdict(true, persona.line("meal", Characters.vars("pass", m)), m);
        }
        int pass = Prefs.passMinutes(c);
        String key = Prefs.geminiKey(c);
        if (!key.isEmpty()) {
            try {
                String prompt = "You are Bonk, an anti-doomscrolling bot with this personality: "
                        + persona.label() + ". The user tried to open " + app
                        + " shortly after being kicked out for scrolling too long. Their reason: \""
                        + reason + "\".\nAllow only specific, purposeful reasons (replying to someone, "
                        + "posting, work, class, a quick lookup). Deny boredom, vague answers, or 'just one more'.\n"
                        + "If you allow, they get " + pass + " minutes. Reply in JSON: "
                        + "{\"allow\": true|false, \"reply\": \"one short in-character sentence, max 14 words\"}";
                JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject()
                                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                        .put("generationConfig", new JSONObject()
                                .put("responseMimeType", "application/json").put("temperature", 0.7));

                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + Prefs.geminiModel(c) + ":generateContent");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(8000);
                con.setReadTimeout(12000);
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("x-goog-api-key", key);
                try (OutputStream out = con.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                String resp;
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    resp = buf.toString("UTF-8");
                }
                con.disconnect();
                String text = new JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                JSONObject v = new JSONObject(text);
                boolean allowed = v.optBoolean("allow", false);
                String reply = v.optString("reply", "").trim();
                if (reply.isEmpty()) reply = persona.line(allowed ? "allow" : "deny", Characters.vars("pass", pass));
                return new Verdict(allowed, reply.length() > 120 ? reply.substring(0, 120) : reply, pass);
            } catch (Exception ignored) {
                // fall through to offline rules
            }
        }
        boolean allowed = offline(reason);
        return new Verdict(allowed, persona.line(allowed ? "allow" : "deny", Characters.vars("pass", pass)), pass);
    }
}
