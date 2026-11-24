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
        boolean byAi;                       // false = offline keyword rules decided
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
            "message", "messages", "messaging", "dm", "dms", "reply", "replying", "text", "texting", "chat", "chatting",
            "talk", "talking", "friend", "friends", "family", "mom", "mum", "amma", "dad", "brother", "sister",
            "streak", "streaks", "snap", "snaps", "send", "sending", "call", "calling", "video", "post", "posting",
            "upload", "story", "class", "assignment", "work", "project", "order", "ticket", "lecture", "tutorial",
            "notes", "group", "event", "club", "meeting", "client", "check", "verify", "otp", "birthday", "wish"));
    private static final Set<String> BAD = new HashSet<>(Arrays.asList(
            "bored", "boring", "scroll", "scrolling", "reels", "reel", "shorts", "timepass", "nothing", "idk",
            "dunno", "chill", "relax", "vibe", "vibing", "random", "whatever"));

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
        return count >= 2 && good;
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
            Verdict v = askGemini(c, key, Prefs.geminiModel(c), reason, app, persona, pass);
            if (v == null && !Prefs.geminiModel(c).equals(FALLBACK_MODEL)) {
                v = askGemini(c, key, FALLBACK_MODEL, reason, app, persona, pass);   // model name changed?
            }
            if (v != null) return v;
        }
        boolean allowed = offline(reason);
        return new Verdict(allowed, persona.line(allowed ? "allow" : "deny", Characters.vars("pass", pass)), pass);
    }

    static final String FALLBACK_MODEL = "gemini-2.5-flash";

    /** One Gemini call. Returns null on any failure and remembers why in Prefs.aiStatus. */
    private static Verdict askGemini(Context c, String key, String model, String reason, String app,
                                     Characters.Persona persona, int pass) {
        {
            try {
                String prompt = "You are Bonk, an anti-doomscrolling bot with this personality: "
                        + persona.label() + ". The user tried to open " + app
                        + " shortly after being kicked out for scrolling too long. Their reason: \""
                        + reason + "\".\nALLOW any specific purpose: talking or replying to a friend or family, "
                        + "sending a message or a Snapchat streak, a call, posting something, work, class, "
                        + "a quick lookup. DENY only boredom, 'just scrolling', reels/shorts, or no real reason. "
                        + "When in doubt about a social or communication reason, allow it.\n"
                        + "If you allow, they get " + pass + " minutes. Reply in JSON: "
                        + "{\"allow\": true|false, \"reply\": \"one short in-character sentence, max 14 words\"}";
                JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject()
                                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                        .put("generationConfig", new JSONObject()
                                .put("responseMimeType", "application/json").put("temperature", 0.7));

                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(8000);
                con.setReadTimeout(12000);
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("x-goog-api-key", key);
                try (OutputStream out = con.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = con.getResponseCode();
                if (code != 200) {
                    String err = "";
                    try (InputStream es = con.getErrorStream()) {
                        if (es != null) {
                            ByteArrayOutputStream eb = new ByteArrayOutputStream();
                            byte[] bb = new byte[4096];
                            int k;
                            while ((k = es.read(bb)) > 0) eb.write(bb, 0, k);
                            err = new JSONObject(eb.toString("UTF-8")).getJSONObject("error").optString("message", "");
                        }
                    } catch (Exception ignored) {}
                    Prefs.setAiStatus(c, "Gemini error " + code + " (" + model + "): " + err);
                    return null;
                }
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
                Verdict out = new Verdict(allowed, reply.length() > 120 ? reply.substring(0, 120) : reply, pass);
                out.byAi = true;
                Prefs.setAiStatus(c, "Gemini works (" + model + ")");
                return out;
            } catch (Exception e) {
                Prefs.setAiStatus(c, "Gemini unreachable (" + model + "): " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()));
                return null;
            }
        }
    }
}
