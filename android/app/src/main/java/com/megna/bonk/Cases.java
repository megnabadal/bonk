package com.megna.bonk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Case files: every kick (or interrogation) is a numbered case. Stored locally and,
 * if sync is set up, mirrored to a Firebase Realtime Database so phone + laptop share them.
 */
final class Cases {

    private static final int KEEP = 500;
    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    private Cases() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bonk_cases", Context.MODE_PRIVATE);
    }

    static synchronized List<JSONObject> local(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString("cases", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception ignored) {}
        return out;
    }

    /** Local + other devices' cases. Does network I/O when sync is on: call off the main thread. */
    static List<JSONObject> all(Context c) {
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (JSONObject o : local(c)) byId.put(o.optString("id"), o);
        for (JSONObject o : fetchRemote(c)) if (!byId.containsKey(o.optString("id"))) byId.put(o.optString("id"), o);
        List<JSONObject> list = new ArrayList<>(byId.values());
        Collections.sort(list, (a, b) -> a.optString("ts").compareTo(b.optString("ts")));
        return list;
    }

    static synchronized JSONObject open(Context c, String app, double minutes, String verdict, String reason) {
        List<JSONObject> list = local(c);
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            o.put("id", "phone-" + now + "-" + (100 + new Random().nextInt(900)));
            o.put("n", sp(c).getInt("counter", 0) + 1);
            o.put("ts", ISO.format(new Date(now)));
            o.put("device", "phone");
            o.put("app", app);
            o.put("minutes", Math.round(minutes * 10) / 10.0);
            o.put("verdict", verdict);
            o.put("reason", reason == null ? "" : reason);
        } catch (Exception ignored) {}
        list.add(o);
        while (list.size() > KEEP) list.remove(0);
        sp(c).edit().putString("cases", new JSONArray(list).toString())
                .putInt("counter", sp(c).getInt("counter", 0) + 1).apply();
        pushRemote(c, o);
        return o;
    }

    // ------------------------------------------------------------ sync (Firebase RTDB REST)
    private static String endpoint(Context c, String suffix) {
        String url = Prefs.syncUrl(c).replaceAll("/+$", "");
        String code = Prefs.syncCode(c).replaceAll("[^A-Za-z0-9_-]", "");
        if (url.isEmpty() || code.isEmpty()) return null;
        return url + "/bonk/" + code + "/cases" + suffix + ".json";
    }

    private static void pushRemote(Context c, JSONObject o) {
        String ep = endpoint(c, "/" + o.optString("id"));
        if (ep == null) return;
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(ep).openConnection();
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                con.setConnectTimeout(6000);
                con.setReadTimeout(6000);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = con.getOutputStream()) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
                con.getResponseCode();
                con.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private static List<JSONObject> fetchRemote(Context c) {
        List<JSONObject> out = new ArrayList<>();
        String ep = endpoint(c, "");
        if (ep == null) return out;
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(ep).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                String body = buf.toString("UTF-8").trim();
                if (!body.isEmpty() && !body.equals("null")) {
                    JSONObject all = new JSONObject(body);
                    Iterator<String> keys = all.keys();
                    while (keys.hasNext()) out.add(all.getJSONObject(keys.next()));
                }
            }
            con.disconnect();
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------ weekly report
    static String weeklyReport(List<JSONObject> cases, String characterName) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        String start = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());

        int kicks = 0, allowed = 0;
        double minutes = 0;
        Map<String, Integer> byApp = new HashMap<>(), byDevice = new HashMap<>();
        int[] byHour = new int[24];
        List<JSONObject> week = new ArrayList<>();
        for (JSONObject o : cases) {
            if (o.optString("ts").compareTo(start) < 0) continue;
            week.add(o);
            String v = o.optString("verdict");
            if (v.equals("allowed")) { allowed++; continue; }
            kicks++;
            minutes += o.optDouble("minutes", 0);
            inc(byApp, o.optString("app"));
            inc(byDevice, o.optString("device"));
            String ts = o.optString("ts");
            if (ts.length() >= 13) {
                try { byHour[Integer.parseInt(ts.substring(11, 13))]++; } catch (NumberFormatException ignored) {}
            }
        }
        int worstHour = -1;
        for (int h = 0; h < 24; h++) if (byHour[h] > 0 && (worstHour < 0 || byHour[h] > byHour[worstHour])) worstHour = h;

        StringBuilder sb = new StringBuilder();
        sb.append("CONFIDENTIAL\nWeekly investigation report\nFiled by ").append(characterName).append("\n\n");
        sb.append("Cases this week: ").append(kicks).append('\n');
        sb.append("Minutes scrolled before getting caught: ").append(Math.round(minutes)).append('\n');
        sb.append("Prime suspect: ").append(top(byApp)).append('\n');
        sb.append("Most dangerous hour: ").append(worstHour < 0 ? "-" : hour(worstHour)).append('\n');
        sb.append("Passes granted after interrogation: ").append(allowed).append("\n\n");
        sb.append("By device: ").append(byDevice.isEmpty() ? "-" : byDevice.toString()).append("\n\n");
        sb.append("CASE LOG\n");
        Collections.reverse(week);
        int shown = 0;
        for (JSONObject o : week) {
            if (shown++ >= 25) break;
            sb.append("#").append(o.optInt("n", 0)).append("  ")
              .append(o.optString("ts").replace('T', ' '), 0, Math.min(16, o.optString("ts").length())).append("  ")
              .append(o.optString("device")).append("  ").append(o.optString("app")).append("  ")
              .append(o.optDouble("minutes", 0)).append(" min  ").append(o.optString("verdict").toUpperCase(Locale.US));
            String r = o.optString("reason");
            if (!r.isEmpty()) sb.append("  \"").append(r).append('"');
            sb.append('\n');
        }
        if (week.isEmpty()) sb.append("No cases. Clean record.\n");
        return sb.toString();
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static String top(Map<String, Integer> m) {
        String best = "-";
        int n = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        return best;
    }

    private static String hour(int h) {
        return ((h % 12) == 0 ? 12 : h % 12) + (h < 12 ? " am" : " pm");
    }
}
